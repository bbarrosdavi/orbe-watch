package io.orbe.watch.gl

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.orbe.watch.gesto.ServicoSacudida
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Os números do desempenho, uma linha no log a cada 10 s, só quando pedidos:
 *
 *   adb shell setprop log.tag.OrbeDesempenho DEBUG     (liga na próxima abertura do orbe)
 *   adb shell setprop log.tag.OrbeDesempenho INFO      (desliga em até 1 s)
 *
 * O padrão de qualquer tag já é INFO: por isso quem liga é o DEBUG. Desligado,
 * nada roda: nenhum relógio, e os contadores não contam.
 *
 * A linha: vsyncs que acordaram a orbe-gl, quadros de orbe desenhados, chamadas
 * de uniform e de desenho, quadros de prévia, o laço médio de um quadro do Motor
 * (com a espera pela GPU), a CPU de cada thread do app na janela, a memória e a
 * corrente da bateria (média de uma leitura por segundo; só vale fora do carregador).
 */
internal object Diagnostico {
    const val TAG = "OrbeDesempenho"
    private const val AMOSTRA_MS = 1_000L
    private const val AMOSTRAS_POR_LINHA = 10

    /** Os contadores contam: escritos na orbe-gl, zerados aqui a cada linha. */
    @JvmField @Volatile var ligado = false

    private val vsyncs = AtomicInteger()
    private val quadros = AtomicInteger()
    private val lacos = AtomicInteger()
    private val chamadas = AtomicLong()
    private val previas = AtomicInteger()
    private val lacoNs = AtomicLong()

    /** O que o app está fazendo agora (tela, ambiente), para ler junto dos números. */
    @Volatile var estado: String = ""

    fun vsync() {
        if (ligado) vsyncs.incrementAndGet()
    }

    /** Um quadro do Motor que desenhou [orbes] orbes em [ns]. */
    fun laco(orbes: Int, ns: Long) {
        if (!ligado || orbes <= 0) return
        quadros.addAndGet(orbes)
        lacos.incrementAndGet()
        lacoNs.addAndGet(ns)
    }

    fun gl(n: Int = 1) {
        if (ligado) chamadas.addAndGet(n.toLong())
    }

    fun previa() {
        if (ligado) previas.incrementAndGet()
    }

    // ── o relógio da janela, numa thread própria (dorme com a CPU: o postDelayed anda no uptime) ──

    private var fio: Handler? = null
    private var linha: HandlerThread? = null
    private var app: Context? = null
    private var amostras = 0
    private var desde = 0L
    private var correnteSoma = 0L
    private var correnteN = 0
    private var cargaAntes = Int.MIN_VALUE
    private val cpuAntes = HashMap<Int, Long>()
    private var primeira = true
    private val msPorTique: Double by lazy { 1000.0 / Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1) }

    /** Liga se a tag foi liberada (chamado ao abrir o orbe e a cada pedido pelo adb). */
    @Synchronized
    fun verificar(ctx: Context) {
        if (fio != null || !Log.isLoggable(TAG, Log.DEBUG)) return
        app = ctx.applicationContext
        val t = HandlerThread("orbe-diag").apply { start() }
        linha = t
        fio = Handler(t.looper).also { it.post { comecar() } }
        ligado = true
        Log.i(TAG, "diagnóstico ligado")
    }

    @Synchronized
    private fun parar() {
        ligado = false
        fio = null
        linha?.quitSafely()
        linha = null
        Log.i(TAG, "diagnóstico desligado")
    }

    private fun comecar() {
        zerar()
        primeira = true
        cpuPorThread()
        primeira = false
        cargaAntes = bateria()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        fio?.postDelayed(::amostra, AMOSTRA_MS)
    }

    private fun bateria(): BatteryManager? = app?.getSystemService(BatteryManager::class.java)

    private fun amostra() {
        val h = fio ?: return
        if (!Log.isLoggable(TAG, Log.DEBUG)) {
            parar()
            return
        }
        bateria()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let {
            if (it != Int.MIN_VALUE && it != 0) {
                correnteSoma += it
                correnteN++
            }
        }
        if (++amostras >= AMOSTRAS_POR_LINHA) relatar()
        h.postDelayed(::amostra, AMOSTRA_MS)
    }

    private fun relatar() {
        val s = (SystemClock.uptimeMillis() - desde) / 1000.0
        val v = vsyncs.getAndSet(0)
        val q = quadros.getAndSet(0)
        val l = lacos.getAndSet(0)
        val g = chamadas.getAndSet(0)
        val p = previas.getAndSet(0)
        val ln = lacoNs.getAndSet(0)
        val cpu = cpuPorThread()
        val mem = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val pss = mem.getMemoryStat("summary.total-pss")?.toIntOrNull()?.div(1024)
        val graf = mem.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024)
        val bm = bateria()
        val carga = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        val carregando = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        // µA: o sinal (descarga negativa ou positiva) é do aparelho; o relatório anota qual
        val i = if (correnteN > 0) "%.1f".format(correnteSoma / correnteN / 1000.0) else "-"
        val dq = if (carga != Int.MIN_VALUE && cargaAntes != Int.MIN_VALUE) "%.2f".format((carga - cargaAntes) / 1000.0) else "-"
        val tela = app?.getSystemService(PowerManager::class.java)?.isInteractive == true
        val cpuTexto = GRUPOS.joinToString(" ") { "$it=${cpu[it] ?: 0}" }
        Log.i(
            TAG,
            "desempenho: %.1fs vsync=%d quadros=%d gl=%d previas=%d laco=%s cpu(%s)ms mem(pss=%sMB graf=%sMB) bateria(I=%smA dQ=%smAh carregando=%d) tela=%d frente=%d %s".format(
                s, v, q, g, p, if (l > 0) "%.1fms".format(ln / l / 1e6) else "-", cpuTexto,
                pss ?: "-", graf ?: "-", i, dq, if (carregando) 1 else 0,
                if (tela) 1 else 0, if (ServicoSacudida.orbeNaFrente) 1 else 0, estado,
            ),
        )
        cargaAntes = carga
        zerar()
    }

    private fun zerar() {
        amostras = 0
        desde = SystemClock.uptimeMillis()
        correnteSoma = 0
        correnteN = 0
    }

    private val GRUPOS = listOf("main", "render", "gl", "batidas", "sensores", "mic", "voz", "rede", "binder", "outras")

    /** Grupo da thread pelo nome (o kernel corta em 15 caracteres). */
    private fun grupo(tid: Int, nome: String): String = when {
        tid == Process.myPid() -> "main"
        nome == "RenderThread" || nome.startsWith("hwuiTask") -> "render"
        nome == "orbe-gl" -> "gl"
        nome == "batidas" -> "batidas"
        nome == "sensores" -> "sensores"
        nome.startsWith("orbe-mic") -> "mic"
        nome.startsWith("orbe-voz") -> "voz"
        nome.startsWith("OkHttp") || nome.startsWith("Okio") -> "rede"
        nome.startsWith("binder:") || nome.startsWith("HwBinder") -> "binder"
        else -> "outras"
    }

    /**
     * A CPU (ms) de cada grupo desde a última chamada: utime + stime de cada
     * thread em /proc/self/task, pela diferença por tid (uma thread nova entra
     * inteira; a que morreu no meio perde o último pedaço).
     */
    private fun cpuPorThread(): Map<String, Long> {
        val sai = HashMap<String, Long>()
        val vistos = HashSet<Int>()
        File("/proc/self/task").listFiles()?.forEach { d ->
            val tid = d.name.toIntOrNull() ?: return@forEach
            val stat = try {
                File(d, "stat").readText()
            } catch (e: Exception) {
                return@forEach
            }
            val a = stat.indexOf('(')
            val b = stat.lastIndexOf(')')
            if (a < 0 || b < a) return@forEach
            val campos = stat.substring(b + 2).split(' ')
            // depois do nome: estado é o campo 3; utime o 14 e stime o 15
            val tiques = (campos.getOrNull(11)?.toLongOrNull() ?: 0) + (campos.getOrNull(12)?.toLongOrNull() ?: 0)
            vistos += tid
            val antes = cpuAntes.put(tid, tiques)
            if (primeira) return@forEach
            val dif = tiques - (antes ?: 0)
            val g = grupo(tid, stat.substring(a + 1, b))
            sai[g] = (sai[g] ?: 0) + (dif * msPorTique).toLong()
        }
        cpuAntes.keys.retainAll(vistos)
        return sai
    }
}
