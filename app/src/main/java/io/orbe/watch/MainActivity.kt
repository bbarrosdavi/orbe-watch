package io.orbe.watch

import io.orbe.watch.dados.RegistroGestos
import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.core.view.ViewConfigurationCompat
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.input.RemoteInputIntentHelper
import io.orbe.watch.gesto.Gesto
import io.orbe.watch.gesto.Sacudida
import io.orbe.watch.gesto.SensoresNaFrente
import io.orbe.watch.gesto.ServicoSacudida
import io.orbe.watch.gl.Diagnostico
import io.orbe.watch.gl.Motor
import io.orbe.watch.ui.Campo
import io.orbe.watch.ui.GiroCoroa
import io.orbe.watch.ui.OrbeApp
import kotlin.math.sqrt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * O Orbe no relógio: o mesmo orbe do desktop, desenhado aqui com os shaders do
 * orbe-qt, ligado ao daemon pela ponte (orbe_relogio.py).
 *
 * O endereço e o token também entram pelo adb, para não digitar no pulso:
 *   adb shell am start -n io.orbe.watch/.MainActivity --es servidor 192.168.0.10 --es token abcd2345
 * Com --ez ouvir true (o que a sacudida manda), abre já numa sessão de voz
 * pelo microfone do relógio. Aberto, uma sacudida do pulso só para fora o
 * manda para trás, como a Hina aberta no HinaWatch.
 */
class MainActivity : ComponentActivity() {
    private val vm: OrbeViewModel by viewModels()
    private var campo = Campo.SERVIDOR

    private val teclado = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val dados = r.data ?: return@registerForActivityResult
        val texto = RemoteInput.getResultsFromIntent(dados)?.getCharSequence(CHAVE)?.toString()?.trim().orEmpty()
        if (texto.isEmpty()) return@registerForActivityResult
        when (campo) {
            Campo.SERVIDOR -> vm.servidor(texto)
            Campo.TOKEN -> vm.token(texto)
        }
    }

    private val permissao = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** O menu pedido pelo adb (--es menu gaveta, ou o nome de uma aba), para conferir sem tocar no relógio. */
    private val menuPedido = mutableStateOf<String?>(null)

    /**
     * Com o orbe aberto, o primeiro pico forte para fora sai na hora, sem esperar
     * a volta do pulso. Lê do [SensoresNaFrente], na thread dos sensores; o que
     * mexe na tela volta para a principal.
     */
    private val sair = object : SensoresNaFrente.Leitor {
        val sacudida = Sacudida(paradoMs = Sacudida.PARADO_MS).apply { sairNaHora = true }
        var gy = 0f
        var gz = 9.8f

        override fun ler(tipo: Int, tNs: Long, x: Float, y: Float, z: Float) {
            // calibrando, as sacudidas são de propósito e não saem
            if (ServicoSacudida.calibrando) {
                sacudida.zerar()
                return
            }
            when (tipo) {
                Sensor.TYPE_ACCELEROMETER -> {
                    gy += (y - gy) * ALFA_GRAVIDADE
                    gz += (z - gz) * ALFA_GRAVIDADE
                    sacudida.gravidade(gy, gz)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // o fora calibrado vale na próxima sacudida, sem religar os sensores
                    sacudida.sairForaMin = vm.ajustes.value.sairFora
                    val gesto = sacudida.ler(tNs / 1_000_000, x, sqrt(x * x + y * y + z * z))
                    sacudida.tirarResumo()?.let { RegistroGestos.registrar(TAG, "na tela: $it") }
                    if (gesto == Gesto.SAIR) {
                        if (!vm.ajustes.value.batidas) janela.post { sairPelaSacudida() }
                        else {
                            // o estalo gira o pulso como a sacudida de sair: espera a janela
                            // da batida fechar (~270 ms do pico) e só sai se não foi estalo
                            val em = SystemClock.elapsedRealtime()
                            janela.postDelayed({
                                val t = io.orbe.watch.gesto.TelaTocada.ultimoTrancoMs
                                if (kotlin.math.abs(t - em) < 500) RegistroGestos.registrar(TAG, "sair ignorado: era batida")
                                else sairPelaSacudida()
                            }, 350)
                        }
                    }
                }
            }
        }
    }

    private val janela = android.os.Handler(android.os.Looper.getMainLooper())

    private fun sairPelaSacudida() {
        RegistroGestos.registrar(TAG, "sair: sacudida para fora")
        ServicoSacudida.saiuEm = SystemClock.uptimeMillis()
        // o relógio volta ao mostrador; o onStop fecha a ponte e corta a voz
        moveTaskToBack(true)
    }

    private var inscricaoSair: SensoresNaFrente.Inscricao? = null

    /** [lote]: null desliga; senão, o lote máximo da inscrição em µs (0 = cada amostra na hora). */
    private fun ligarSair(lote: Int?) {
        inscricaoSair?.let(SensoresNaFrente::sair)
        inscricaoSair = null
        if (lote == null) return
        // as taxas de sempre do sair: acelerômetro no ritmo da tela, giroscópio no de jogo
        SensoresNaFrente.naLinha { sair.sacudida.zerar() }
        inscricaoSair = SensoresNaFrente.inscrever(this, sair, SensoresNaFrente.UI_US, SensoresNaFrente.GAME_US, lote)
    }

    // o dedo na tela manda tranco pelo pulso: as batidas ficam surdas enquanto ele
    // está lá e um pouco depois de sair
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        io.orbe.watch.gesto.TelaTocada.surdoAte = android.os.SystemClock.elapsedRealtime() + 400
        mexeu()
        return super.dispatchTouchEvent(ev)
    }

    // ── a tela: acesa com a chave, sempre em sessão, e pelo tempo escolhido depois do último toque ──
    //
    // Abaixar ou virar o pulso ainda apaga: o ungaze do Wear OS põe o relógio para
    // dormir como o botão ("Going to sleep due to sleep_button" junto do GESTURE
    // UNGAZE no log), e isso passa por cima da trava. Com o ambiente, o app fica.

    private var mexeuEm = 0L
    private var telaPresa = false
    private val reavaliarTela = Runnable { aplicarTela() }

    private fun mexeu() {
        mexeuEm = SystemClock.uptimeMillis()
        // presa pelo tempo, a conta segue no reavaliar já marcado; solta, o toque volta a prender
        if (!telaPresa && vm.ajustes.value.telaTempo > 0) aplicarTela()
        vm.mexeu()
    }

    private fun aplicarTela() {
        val a = vm.ajustes.value
        val resta = if (a.telaTempo > 0) mexeuEm + a.telaTempo * 1000L - SystemClock.uptimeMillis() else 0L
        val presa = a.telaAcesa || vm.emSessao.value || resta > 0
        if (presa != telaPresa) {
            telaPresa = presa
            if (presa) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        janela.removeCallbacks(reavaliarTela)
        if (!a.telaAcesa && resta > 0) janela.postDelayed(reavaliarTela, resta)
        Diagnostico.estado = "presa=${if (presa) 1 else 0} ambiente=${if (vm.emAmbiente.value) 1 else 0}"
    }

    // ── o ambiente: o relógio escurece com o orbe aberto e o app fica nele ──

    private var protegerTela = false
    private var passoAmbiente = 0

    private val ambiente = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            if (!vm.ajustes.value.ambiente) {
                // sem o ambiente, escurecer é sair: o relógio volta ao mostrador, como antes
                Log.i(TAG, "ambiente desligado nos ajustes: de volta ao mostrador")
                moveTaskToBack(true)
                return
            }
            Log.i(TAG, "ambiente: entrou (proteção da tela: ${ambientDetails.burnInProtectionRequired})")
            protegerTela = ambientDetails.burnInProtectionRequired
            passoAmbiente = 0
            // a sacudida passa ao serviço, que a lê como com a tela apagada (em lote, acordada pela inclinação)
            ServicoSacudida.orbeNaFrente = false
            vm.ambiente(true)
            Motor.congelar(true)
            aplicarTela()
        }

        override fun onUpdateAmbient() {
            if (protegerTela) {
                // a cada minuto, um passo de poucos px em volta do centro: nenhum pixel aceso fica no lugar
                val d = DESLOCAMENTOS[passoAmbiente++ % DESLOCAMENTOS.size]
                vm.cena.deslocX = d.first
                vm.cena.deslocY = d.second
            }
            Motor.umQuadro()
        }

        override fun onExitAmbient() {
            Log.i(TAG, "ambiente: saiu")
            vm.cena.deslocX = 0.0
            vm.cena.deslocY = 0.0
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) ServicoSacudida.orbeNaFrente = true
            vm.ambiente(false)
            Motor.congelar(false)
            mexeuEm = SystemClock.uptimeMillis()
            aplicarTela()
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambiente)
        provisionar(intent)
        setContent {
            OrbeApp(
                vm,
                podeGravar = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                pedirMicrofone = { permissao.launch(Manifest.permission.RECORD_AUDIO) },
                editar = ::editar,
                menuPedido = menuPedido,
                coroa = coroa,
            )
        }
        // os sensores do sair só com o orbe na frente, fora do ambiente, e a chave ligada
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    combine(vm.ajustes.map { it.sair to it.sensoresLote }, vm.emAmbiente) { (sair, lote), amb ->
                        if (sair && !amb) (if (lote) SensoresNaFrente.LOTE_US else 0) else null
                    }.distinctUntilChanged().collect(::ligarSair)
                } finally {
                    ligarSair(null)
                }
            }
        }
        // a tela: a chave, o tempo e a sessão; a sessão que acaba conta como o último toque
        lifecycleScope.launch {
            var antes = false
            combine(vm.ajustes.map { it.telaAcesa to it.telaTempo }.distinctUntilChanged(), vm.emSessao) { _, s -> s }
                .collect { sessao ->
                    if (antes && !sessao) mexeuEm = SystemClock.uptimeMillis()
                    antes = sessao
                    aplicarTela()
                }
        }
    }

    /**
     * A coroa direto da janela, antes das Views: no Compose o giro só chega a quem
     * tem o foco, e o VerticalPager do Wear e a superfície GL o tiram do OrbeApp
     * quando o carrossel troca de página (medido no TicWatch Pro 5).
     */
    private val coroa = MutableSharedFlow<GiroCoroa>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // a coroa também é mexer: a tela conta o tempo a partir dela, e a ponte que dormia acorda
        mexeu()
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            // os mesmos pixels que o Compose dá em verticalScrollPixels
            val px = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) *
                ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(this), this)
            coroa.tryEmit(GiroCoroa(ev.eventTime, px, ev.deviceId))
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        provisionar(intent)
    }

    // ── memória: 10 s fora da frente, sai o que o orbe em tela não usa; o sistema pedindo, sai tudo ──

    private val liberarFora = Runnable {
        Motor.liberar(tudo = false)
        io.orbe.watch.ui.Previas.limpar()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // abaixo de UI_HIDDEN (que vem sempre ao sair) é o sistema apertado de memória
        @Suppress("DEPRECATION")
        when {
            // fora da frente: sai tudo, as máscaras inclusive
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.i(TAG, "memória: o sistema pediu (nível $level)")
                janela.removeCallbacks(liberarFora)
                Motor.liberar(tudo = true)
                io.orbe.watch.ui.Previas.limpar()
            }
            // na frente, com o relógio apertado (1 GB): só o que o orbe em tela não usa, sem
            // fazer ele recarregar atlas e máscara no meio da animação
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.i(TAG, "memória: o sistema pediu com o orbe na frente (nível $level)")
                Motor.liberar(tudo = false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        janela.removeCallbacks(liberarFora)
        ServicoSacudida.orbeNaFrente = true
        mexeuEm = SystemClock.uptimeMillis()
        Diagnostico.verificar(this)
        Motor.retomar()
        vm.entrou()
    }

    override fun onStop() {
        ServicoSacudida.orbeNaFrente = false
        // fora da frente nada desenha: a orbe-gl dorme até o orbe voltar
        Motor.pausar()
        janela.postDelayed(liberarFora, LIBERAR_FORA_MS)
        vm.saiu(fechando = isFinishing)
        super.onStop()
    }

    private fun provisionar(i: Intent?) {
        i?.getStringExtra("servidor")?.let(vm::servidor)
        i?.getStringExtra("token")?.let(vm::token)
        // a sacudida do HinaWatch abre o orbe já ouvindo
        if (i?.getBooleanExtra(EXTRA_OUVIR, false) == true) {
            vm.ouvir(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
        // para medir num relógio de verdade: --ef qualidade 0.75 trava a resolução da máscara
        i?.getFloatExtra("qualidade", -1f)?.takeIf { it >= 0f }?.let { Motor.qualidadeFixa = it }
        i?.getStringExtra("menu")?.let { menuPedido.value = it }
        // o orbe em tela pelo id da skin (anel, ofanim, serafim_gravura...), para medir cada um
        i?.getStringExtra("skin")?.let { id -> io.orbe.watch.orbe.Skin.entries.firstOrNull { it.id == id }?.let(vm::skin) }
        // a ação de uma batida pelo adb: --es batida "4=live" (0-3 toques, 4-7 terminando em estalo)
        i?.getStringExtra("batida")?.split('=')?.takeIf { it.size == 2 }?.let { (n, id) ->
            val k = n.toIntOrNull(); val a = io.orbe.watch.dados.AcaoToque.de(id)
            if (k != null && a != null) vm.batidaAcao(k, a)
        }
        // as chaves da aba Desempenho, para medir sem tocar no relógio (ver OrbeViewModel.desempenhoPorAdb)
        i?.getStringExtra("desempenho")?.let(vm::desempenhoPorAdb)
        Diagnostico.verificar(this)
    }

    /** O teclado do relógio (ou o ditado, ou o do celular pareado) para um campo de texto. */
    private fun editar(c: Campo) {
        campo = c
        val pedido = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val entrada = RemoteInput.Builder(CHAVE).setLabel(c.rotulo).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(pedido, listOf(entrada))
        teclado.launch(pedido)
    }

    companion object {
        private const val CHAVE = "texto"
        private const val TAG = "OrbeGesto"
        private const val ALFA_GRAVIDADE = 0.2f
        /** abre já numa sessão de voz: a sacudida (do orbe ou do HinaWatch) */
        const val EXTRA_OUVIR = "ouvir"
        /** fora da frente por isto, sai da memória o que o orbe em tela não usa (a resposta que volta logo não paga nada) */
        private const val LIBERAR_FORA_MS = 10_000L
        /** os passos do deslocamento do ambiente (px lógicos), em volta do centro */
        private val DESLOCAMENTOS = listOf(0.0 to 0.0, 3.0 to 0.0, 3.0 to 3.0, 0.0 to 3.0, -3.0 to 3.0, -3.0 to 0.0, -3.0 to -3.0, 0.0 to -3.0, 3.0 to -3.0)
    }
}
