package io.orbe.watch.ui

import android.hardware.Sensor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import io.orbe.watch.gesto.Batida
import io.orbe.watch.gesto.Forca
import io.orbe.watch.gesto.Comando
import io.orbe.watch.gesto.Janela
import io.orbe.watch.gesto.ModeloBatida
import io.orbe.watch.gesto.SensoresNaFrente
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.LocalSwipeToDismissBackgroundScrimColor
import androidx.wear.compose.foundation.LocalSwipeToDismissContentScrimColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import io.orbe.watch.Aparencia
import io.orbe.watch.OrbeViewModel
import io.orbe.watch.dados.Ligacao
import io.orbe.watch.gesto.Picos
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * As duas páginas do relógio, lado a lado: o orbe e, arrastando para a
 * esquerda, o menu (a gaveta e as abas). Na vertical (dedo ou coroa) a lista
 * passa de orbe em orbe; no menu, a coroa rola a aba (a gaveta não rola). Voltar da aba
 * cai na gaveta, e da gaveta no orbe. No menu o pager não arrasta: puxar para a
 * direita volta um nível e para a esquerda volta ao orbe (TelaAjustes). O fundo
 * do menu fica parado atrás das duas páginas: só o conteúdo anda, sem a emenda
 * de um fundo no outro.
 */
@Composable
fun OrbeApp(
    vm: OrbeViewModel,
    podeGravar: () -> Boolean,
    pedirMicrofone: () -> Unit,
    editar: (Campo) -> Unit,
    /** o menu pedido pelo adb: "gaveta" ou o nome de uma aba; volta a null depois de abrir */
    menuPedido: MutableState<String?>,
    /** o giro da coroa, da MainActivity (fora do foco do Compose) */
    coroa: Flow<GiroCoroa>,
) {
    val aparencia by vm.aparencia.collectAsStateWithLifecycle()
    val ajustes by vm.ajustes.collectAsStateWithLifecycle()
    val retrato by vm.retrato.collectAsStateWithLifecycle()
    val ligacao by vm.ligacao.collectAsStateWithLifecycle()
    val historico by vm.historico.collectAsStateWithLifecycle()
    val agentesPc by vm.agentesPc.collectAsStateWithLifecycle()
    val listaHistorico = rememberTransformingLazyColumnState()
    // o projeto aberto no histórico (o cwd); null, a lista dos projetos
    var projetoHistorico by remember { mutableStateOf<String?>(null) }
    val redonda = LocalConfiguration.current.isScreenRound
    val paginas = rememberPagerState { 2 }
    val lista = rememberTransformingLazyColumnState()
    // a aba aberta no menu; null = a gaveta
    var aba by remember { mutableStateOf<Aba?>(null) }
    val escopo = rememberCoroutineScope()
    // a coroa no orbe: o giro cru vai para a lista dos orbes, que encaixa de orbe em orbe
    val coroaOrbe = remember { MutableSharedFlow<GiroCoroa>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    // os passos das ações próximo e anterior (toques e batidas): um orbe por passo
    val passosOrbe = remember { MutableSharedFlow<Int>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    // a calibração da sacudida cobre o menu até salvar ou voltar
    var calibrando by remember { mutableStateOf<Calibracao?>(null) }

    // o relógio escureceu com o app aberto: só o orbe, apagado, no preto
    val ambiente by vm.emAmbiente.collectAsStateWithLifecycle()

    // as prévias do menu esperam o orbe aquecer a cada abertura (3 s), ou o menu chegar perto:
    // antes, as 128 da gaveta saíam junto da primeira compilação do orbe
    var aquecido by remember { mutableStateOf(false) }
    val donoCiclo = LocalLifecycleOwner.current
    LaunchedEffect(donoCiclo) {
        donoCiclo.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            aquecido = false
            kotlinx.coroutines.delay(AQUECER_PREVIAS_MS)
            aquecido = true
        }
    }

    // as batidas, com o app aberto e fora das calibrações: 1 a 4 toques fracos,
    // ou toques que terminam em estalo, cada sequência com a sua ação
    val modelo = remember(ajustes.batidaFracas, ajustes.batidaFortes, ajustes.batidaNada) {
        ModeloBatida.de(Janela.lista(ajustes.batidaFracas), Janela.lista(ajustes.batidaFortes), Janela.lista(ajustes.batidaNada))
    }
    BatidasNoPulso(
        // no ambiente a CPU dorme entre os quadros e as batidas não chegariam inteiras
        ligado = ajustes.batidas && calibrando == null && modelo.calibrado && !ambiente,
        modelo = modelo,
        intervalo = ajustes.batidasIntervalo,
        lote = ajustes.sensoresLote,
    ) { c ->
        // vibra na execução (vm.batida), não na detecção: comando que não acontece não vibra
        vm.batida(c.indice, podeGravar(), forte = c.forca == Forca.FORTE)
    }
    // as ações de trocar de orbe (dos toques e das batidas) andam a lista como a coroa
    LaunchedEffect(vm) {
        vm.passosLista.collect { passo -> if (paginas.currentPage == 0 && historico == null) passosOrbe.tryEmit(passo) }
    }

    // a tela (acesa com a chave, sempre em sessão, ou pelo tempo dos ajustes) é da MainActivity

    // a ponte aceita a fala do relógio: é a hora de pedir o microfone, uma vez só
    val aceitaFala = (ligacao as? Ligacao.Conectada)?.microfone == true
    LaunchedEffect(aceitaFala, ajustes.microfone, ajustes.pediuMicrofone) {
        if (aceitaFala && ajustes.microfone && !ajustes.pediuMicrofone) {
            vm.pediuMicrofone()
            if (!podeGravar()) pedirMicrofone()
        }
    }

    CompositionLocalProvider(
        LocalTema provides aparencia.tema,
        LocalRolando provides (paginas.isScrollInProgress || lista.isScrollInProgress),
        LocalFundoMenu provides ajustes.fundoMenu,
        LocalAmbiente provides ambiente,
        // o histórico e as calibrações têm fundo próprio e cobrem o orbe inteiro
        LocalCoberto provides (historico != null || calibrando != null),
        LocalPreviasLiberadas provides (aquecido || paginas.currentPage == 1 || paginas.targetPage == 1),
    ) {
      Box(Modifier.fillMaxSize().background(Color.Black)) {
        // sem o fundo atrás do orbe, ele acende junto com a entrada do menu; no ambiente, só o preto
        if (!ambiente) FundoVidro(
            Modifier.graphicsLayer {
                val menu = (paginas.currentPage + paginas.currentPageOffsetFraction).coerceIn(0f, 1f)
                // o papel no orbe (fundo) e no menu (fundoMenu), cada um com a sua chave
                alpha = (if (aparencia.fundo) 1f - menu else 0f) + (if (ajustes.fundoMenu) menu else 0f)
            },
            menu = false,
        )
        // com o fundo atrás do orbe, um véu o escurece na página dele e some na entrada do menu
        if (aparencia.fundo && !ambiente) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = Aparencia.ESCURO * (1 - (paginas.currentPage + paginas.currentPageOffsetFraction)).coerceIn(0f, 1f)
                }.background(Color.Black),
            )
        }
        HorizontalPager(
            state = paginas,
            modifier = Modifier.fillMaxSize(),
            // as duas páginas ficam montadas: o menu pede as prévias das miniaturas
            // já na abertura, e não nasce no meio do arrasto
            beyondViewportPageCount = 1,
            // assentado no menu, os puxões são da TelaAjustes: o pager não leva ao orbe por conta própria
            userScrollEnabled = paginas.settledPage == 0,
        ) { pagina ->
            if (pagina == 0) {
                TelaOrbe(vm, redonda, podeGravar, abrirAjustes = { escopo.launch { paginas.animateScrollToPage(1) } }, coroa = coroaOrbe, passos = passosOrbe)
            } else {
                TelaAjustes(
                    vm, lista, aba,
                    abrir = { a ->
                        // cada aba abre do alto
                        if (a != null) escopo.launch { lista.scrollToItem(0) }
                        aba = a
                    },
                    redonda,
                    visivel = paginas.currentPage == 1 || paginas.targetPage == 1,
                    editar, pedirMicrofone, podeGravar,
                    aoOrbe = { escopo.launch { paginas.animateScrollToPage(0) } },
                    calibrar = { calibrando = it },
                )
            }
        }
        // arrastar para a direita numa calibração volta ao menu (como o histórico):
        // sem a caixa, o arraste ia ao sistema, que fechava o app inteiro
        if (calibrando != null) CompositionLocalProvider(
            LocalSwipeToDismissBackgroundScrimColor provides Color.Transparent,
            LocalSwipeToDismissContentScrimColor provides Color.Transparent,
        ) {
            BasicSwipeToDismissBox(onDismissed = { calibrando = null }) { fundo ->
                if (fundo) return@BasicSwipeToDismissBox
                when (calibrando) {
                    Calibracao.ABRIR -> TelaCalibracao(
                        Picos(ajustes.sacudidaFora, ajustes.sacudidaDentro), redonda,
                        salvar = {
                            vm.calibrarSacudida(it)
                            calibrando = null
                        },
                        padrao = {
                            vm.sacudidaPadrao()
                            calibrando = null
                        },
                    )
                    Calibracao.SAIR -> TelaCalibracaoSair(
                        ajustes.sairFora, redonda,
                        salvar = {
                            vm.calibrarSair(it)
                            calibrando = null
                        },
                        padrao = {
                            vm.calibrarSair(0f)
                            calibrando = null
                        },
                    )
                    Calibracao.FRACA, Calibracao.FORTE, Calibracao.NADA -> {
                        val tipo = calibrando!!
                        val n = Janela.lista(when (tipo) {
                            Calibracao.FRACA -> ajustes.batidaFracas
                            Calibracao.FORTE -> ajustes.batidaFortes
                            else -> ajustes.batidaNada
                        }).size
                        TelaCalibracaoBatida(
                            tipo, emUso = if (n > 0) "$n exemplos" else "sem calibrar", redonda,
                            salvar = {
                                vm.calibrarBatidas(tipo, it)
                                calibrando = null
                            },
                            padrao = {
                                vm.batidasPadrao()
                                calibrando = null
                            },
                            dizer = vm::dizer,
                        )
                    }
                    null -> Unit
                }
            }
        }
        historico?.let { h ->
            // arrastar para a direita fecha só o histórico e volta ao orbe: sem a
            // caixa, o arraste ia ao sistema, que fechava o app inteiro. Dentro
            // de um projeto, o arraste volta à lista dos projetos (a caixa nasce
            // de novo a cada nível, com o estado dela zerado)
            CompositionLocalProvider(
                LocalSwipeToDismissBackgroundScrimColor provides Color.Transparent,
                LocalSwipeToDismissContentScrimColor provides Color.Transparent,
            ) {
                key(projetoHistorico) {
                    BasicSwipeToDismissBox(onDismissed = { if (projetoHistorico != null) projetoHistorico = null else vm.fecharHistorico() }) { fundo ->
                        if (!fundo) {
                            TelaHistorico(h, nomeAgente(ajustes, aparencia.skin, agentesPc), listaHistorico, redonda,
                                projeto = projetoHistorico, abrirProjeto = { projetoHistorico = it },
                                retomar = vm::retomar, fechar = vm::fecharHistorico)
                        }
                    }
                }
            }
        }
        // o ambiente com o menu, o histórico ou uma calibração na tela: só o preto (o OLED apagado)
        if (ambiente && (paginas.currentPage != 0 || historico != null || calibrando != null)) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
      }
    }
    BackHandler(calibrando != null) { calibrando = null }
    BackHandler(calibrando == null && paginas.currentPage == 1) {
        if (aba != null) aba = null else escopo.launch { paginas.animateScrollToPage(0) }
    }
    BackHandler(historico != null) { if (projetoHistorico != null) projetoHistorico = null else vm.fecharHistorico() }
    // cada abertura do histórico (e cada nível dele) começa do alto, na lista dos projetos
    LaunchedEffect(historico == null) {
        projetoHistorico = null
        if (historico != null) listaHistorico.scrollToItem(0)
    }
    LaunchedEffect(projetoHistorico) { if (historico != null) listaHistorico.scrollToItem(0) }
    // com o histórico aberto a coroa rola ele; no orbe passa de orbe em orbe; no menu rola a aba
    val estado by rememberUpdatedState(Triple(historico != null, paginas.currentPage, aba))
    LaunchedEffect(coroa) {
        coroa.collect { g ->
            val (comHistorico, pagina, abaAberta) = estado
            when {
                comHistorico -> listaHistorico.dispatchRawDelta(g.delta)
                pagina == 0 -> coroaOrbe.tryEmit(g)
                abaAberta != null -> lista.dispatchRawDelta(g.delta)
            }
        }
    }
    LaunchedEffect(menuPedido.value) {
        val m = menuPedido.value ?: return@LaunchedEffect
        menuPedido.value = null
        paginas.scrollToPage(1)
        aba = Aba.entries.firstOrNull { it.name.equals(m, ignoreCase = true) }
    }
    // de volta ao orbe, o menu recomeça da gaveta na próxima visita
    LaunchedEffect(paginas.settledPage) {
        if (paginas.settledPage == 0) aba = null
    }
}

/** Quanto as prévias do menu esperam depois de o app abrir, se o menu não chegar perto antes. */
private const val AQUECER_PREVIAS_MS = 3_000L

/**
 * O detector de batidas ([Batida]) no acelerômetro e no giroscópio, no mais
 * rápido, enquanto o app está aberto e [ligado]; cada comando vai a [aoComando].
 * As amostras vêm do [SensoresNaFrente], a inscrição que o sair também usa.
 */
@Composable
private fun BatidasNoPulso(
    ligado: Boolean, modelo: ModeloBatida, intervalo: Long, lote: Boolean,
    aoComando: (Comando) -> Unit,
) {
    val ctx = LocalContext.current
    val acao = remember { arrayOf(aoComando) }
    acao[0] = aoComando
    val dono = LocalLifecycleOwner.current
    DisposableEffect(ligado, modelo, intervalo, lote, dono) {
        if (!ligado) return@DisposableEffect onDispose { }
        val batida = Batida(modelo, juntarMs = intervalo)
        val principal = Handler(Looper.getMainLooper())
        // as amostras chegam na thread dos sensores: na principal, cada engasgo do
        // Compose segurava as leituras na fila e atrasava o reconhecimento
        val leitor = SensoresNaFrente.Leitor { tipo, tNs, x, y, z ->
            when (tipo) {
                Sensor.TYPE_ACCELEROMETER -> {
                    batida.acel(tNs / 1_000_000, x, y, z)
                    for (r in batida.tirarRegistros()) Log.i("OrbeBatida", r)
                    batida.tirarJanelas()
                    // um aviso por comando (não por toque: ele caía em cima do toque seguinte),
                    // dado pelo vm.batida só se o comando acontece
                    for (c in batida.tirarComandos()) {
                        Log.i("OrbeBatida", "comando ${c.forca} x${c.vezes}")
                        principal.post { acao[0](c) }
                    }
                }
                Sensor.TYPE_GYROSCOPE -> batida.giro(x, y, z)
            }
        }
        var inscricao: SensoresNaFrente.Inscricao? = null
        // só com o app na frente: pausado, a inscrição sai
        val ciclo = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> if (inscricao == null) {
                    SensoresNaFrente.naLinha { batida.zerar() }
                    inscricao = SensoresNaFrente.inscrever(
                        ctx, leitor, SensoresNaFrente.MAIS_RAPIDO_US, SensoresNaFrente.MAIS_RAPIDO_US,
                        if (lote) SensoresNaFrente.LOTE_US else 0,
                    )
                }
                Lifecycle.Event.ON_PAUSE -> {
                    inscricao?.let(SensoresNaFrente::sair)
                    inscricao = null
                }
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(ciclo)
        onDispose {
            dono.lifecycle.removeObserver(ciclo)
            inscricao?.let(SensoresNaFrente::sair)
        }
    }
}
