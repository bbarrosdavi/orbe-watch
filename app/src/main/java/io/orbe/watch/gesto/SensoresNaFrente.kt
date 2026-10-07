package io.orbe.watch.gesto

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Acelerômetro e giroscópio do app na frente, numa inscrição só: o sair (a
 * sacudida para fora, MainActivity) e as batidas (OrbeApp) leem daqui.
 *
 * Antes cada um se inscrevia nos dois sensores. O Android roda cada sensor na
 * menor taxa pedida e entrega todas as amostras a todos os ouvintes dele (a
 * taxa é só um pedido), então o sair já lia as amostras na taxa das batidas
 * quando elas estavam ligadas. Aqui é igual: a inscrição usa o menor período e
 * o menor lote dos leitores, e cada amostra vai a todos eles, na mesma ordem.
 * Muda que ela vem por uma conexão com o serviço de sensores e acorda uma
 * thread, não duas, e o sair deixa a thread da tela.
 *
 * Para gravar as amostras cruas (o teste de regressão dos detectores):
 *   adb shell setprop log.tag.OrbeSensores VERBOSE   (vale na próxima inscrição)
 * Cada linha: "a t_ns x y z" ou "g t_ns x y z".
 */
object SensoresNaFrente {
    private const val TAG = "OrbeSensores"

    /** Uma amostra: o tipo do sensor, o instante (ns desde o boot) e os três eixos. */
    fun interface Leitor {
        fun ler(tipo: Int, tNs: Long, x: Float, y: Float, z: Float)
    }

    /**
     * Um leitor e o que ele pede: os períodos em µs (0 = o mais rápido; os
     * SENSOR_DELAY_UI e GAME são 66667 e 20000) e o lote máximo, em µs (0 = cada
     * amostra na hora).
     */
    class Inscricao internal constructor(
        internal val leitor: Leitor,
        internal val acelUs: Int,
        internal val giroUs: Int,
        internal val loteUs: Int,
    )

    private val inscricoes = CopyOnWriteArrayList<Inscricao>()
    private var linha: HandlerThread? = null
    private var maos: Handler? = null
    private var sensores: SensorManager? = null
    // o que está pedido ao sistema agora: (acel µs, giro µs, lote µs), null = nada
    private var pedido: Triple<Int, Int, Int>? = null
    @Volatile private var despejar = false

    private val ouvinte = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tipo = event.sensor.type
            val v = event.values
            if (despejar) Log.v(TAG, "%s %d %.5f %.5f %.5f".format(if (tipo == Sensor.TYPE_ACCELEROMETER) "a" else "g", event.timestamp, v[0], v[1], v[2]))
            for (i in inscricoes) i.leitor.ler(tipo, event.timestamp, v[0], v[1], v[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Synchronized
    fun inscrever(ctx: Context, leitor: Leitor, acelUs: Int, giroUs: Int, loteUs: Int = 0): Inscricao {
        if (sensores == null) sensores = ctx.applicationContext.getSystemService(SensorManager::class.java)
        val i = Inscricao(leitor, acelUs, giroUs, loteUs)
        inscricoes += i
        refazer()
        return i
    }

    @Synchronized
    fun sair(i: Inscricao) {
        if (inscricoes.remove(i)) refazer()
    }

    /** Roda [r] na thread dos sensores, na ordem das amostras (zerar um detector, por exemplo). */
    @Synchronized
    fun naLinha(r: Runnable) {
        maos?.post(r) ?: r.run()
    }

    /** A inscrição no sistema acompanha os leitores: o menor período e o menor lote, ou nenhuma. */
    private fun refazer() {
        val s = sensores ?: return
        val novo = if (inscricoes.isEmpty()) null
        else Triple(inscricoes.minOf { it.acelUs }, inscricoes.minOf { it.giroUs }, inscricoes.minOf { it.loteUs })
        if (novo == pedido) return
        s.unregisterListener(ouvinte)
        pedido = novo
        if (novo == null) {
            linha?.quitSafely()
            linha = null
            maos = null
            return
        }
        val h = maos ?: HandlerThread("sensores").also { it.start(); linha = it }.let { Handler(it.looper) }.also { maos = it }
        despejar = Log.isLoggable(TAG, Log.VERBOSE)
        s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { s.registerListener(ouvinte, it, novo.first, novo.third, h) }
        s.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { s.registerListener(ouvinte, it, novo.second, novo.third, h) }
        Log.i(TAG, "inscrito: acel ${novo.first} µs, giro ${novo.second} µs, lote ${novo.third} µs, ${inscricoes.size} leitor(es)")
    }

    // os períodos que o SensorManager usa para SENSOR_DELAY_UI, GAME e FASTEST
    const val UI_US = 66_667
    const val GAME_US = 20_000
    const val MAIS_RAPIDO_US = 0
    /** o lote dos gestos com o app na frente, com "Sensores em lote" ligado */
    const val LOTE_US = 60_000
}
