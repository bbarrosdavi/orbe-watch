package io.orbe.watch

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.orbe.watch.dados.AcaoToque
import io.orbe.watch.gesto.Janela
import io.orbe.watch.ui.Calibracao
import io.orbe.watch.dados.AgenteInfo
import io.orbe.watch.dados.Ajustes
import io.orbe.watch.dados.AltoFalante
import io.orbe.watch.dados.Cofre
import io.orbe.watch.dados.Espera
import io.orbe.watch.dados.Foco
import io.orbe.watch.dados.Historico
import io.orbe.watch.dados.Ligacao
import io.orbe.watch.dados.Microfone
import io.orbe.watch.dados.Ola
import io.orbe.watch.dados.Ponte
import io.orbe.watch.dados.Protocolo
import io.orbe.watch.dados.RedeLocal
import io.orbe.watch.dados.SessaoInfo
import io.orbe.watch.dados.Sincronia
import io.orbe.watch.gesto.Picos
import io.orbe.watch.gesto.Sacudida
import io.orbe.watch.gesto.ServicoSacudida
import io.orbe.watch.orbe.Estado
import io.orbe.watch.orbe.Instancias
import io.orbe.watch.orbe.OrbeCena
import io.orbe.watch.orbe.Retrato
import io.orbe.watch.orbe.Skin
import io.orbe.watch.ui.Tema
import io.orbe.watch.ui.rgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** O que vale na tela: os ajustes do relógio já cruzados com a aparência do PC. */
data class Aparencia(
    val skin: Skin = Skin.OFANIM,
    val glitch: Boolean = true,
    val linhas: Boolean = true,
    val tema: Tema = Tema.Padrao,
    /** a instância em tela, num orbe de agente com instâncias (Instancias); 0 nos outros */
    val instancia: Int = 0,
    /** o fundo do menu também atrás dos orbes (pedido nos ajustes, e com o papel de parede do PC) */
    val fundo: Boolean = true,
) {
    /** A cor da figura e a do anel: a da instância, ou as do tema. */
    fun corFigura(): FloatArray = Instancias.cores[Instancias.cor(instancia)] ?: tema.accent.rgb()
    fun corAnel(): FloatArray = Instancias.cores[Instancias.cor(instancia)] ?: tema.anel.rgb()
    /** O tom da massa escura da figura: preto, com ou sem o fundo (o vidro escurece o papel com preto). */
    fun corFundo(): FloatArray = floatArrayOf(0f, 0f, 0f)

    companion object {
        /** o véu preto sobre o vidro na página do orbe, para o fundo não competir com ele; o menu fica sem */
        const val ESCURO = 0.55f
    }
}

class OrbeViewModel(app: Application) : AndroidViewModel(app) {
    val cena = OrbeCena()
    private val cofre = Cofre(app)
    private val microfone = Microfone()
    // antes do init: o collect da ligação roda já na construção (Main.immediate) e passa por ela
    private val trava = Any()
    private val vibrador = app.getSystemService(Vibrator::class.java)

    private val _ajustes = MutableStateFlow(Ajustes())
    val ajustes: StateFlow<Ajustes> = _ajustes
    private val _retrato = MutableStateFlow(Retrato())
    val retrato: StateFlow<Retrato> = _retrato

    /**
     * O orbe (skin e instância) que estava em tela quando a sessão abriu; null
     * sem sessão. Rolando para outro, a sessão segue, mas só o dono a mostra.
     */
    private val _dono = MutableStateFlow<Pair<Skin, Int>?>(null)

    /** O histórico aberto pela ação Histórico dos toques; null fechado. */
    private val _historico = MutableStateFlow<Historico?>(null)
    val historico: StateFlow<Historico?> = _historico
    val dono: StateFlow<Pair<Skin, Int>?> = _dono

    /**
     * as sessões do Claude Code abertas no PC, cada uma na vaga dela (com mais de
     * um orbe do Claude, as vagas se alternam entre eles: [Instancias.vaga]);
     * null sem ponte, ou com uma que não as conta
     */
    private val _sessoes = MutableStateFlow<List<SessaoInfo>?>(null)
    val sessoes: StateFlow<List<SessaoInfo>?> = _sessoes
    /** falar num orbe do Claude sem sessão abre uma no PC */
    private val _abreClaude = MutableStateFlow(false)
    val abreClaude: StateFlow<Boolean> = _abreClaude

    /**
     * o relógio tem alto-falante (ou fone pareado) para tocar a resposta. A
     * pergunta vai ao audioserver: com ele caído, feita aqui, a tela abria travada
     */
    private val _temSaidaDeSom = MutableStateFlow(false)
    val temSaidaDeSom: StateFlow<Boolean> = _temSaidaDeSom
    private val altoFalante = AltoFalante(
        aoNivel = { nivel, tom -> cena.comando("level $nivel $tom") },
        aoAcabar = {
            ponte.enviar("voz acabou")
            // a resposta tocou com o orbe fora da tela (a tela não pôde abrir): a
            // espera acabou. Uma etapa dita no meio do trabalho não acaba nada.
            if (aoFundo && !naTela && !pcTrabalhando()) viewModelScope.launch { largarFundo() }
        },
    )
    private val ponte: Ponte = Ponte(
        viewModelScope, Build.MODEL ?: "relógio", ::linhaDaPonte, ::olaDaPonte, ::configDaPonte,
        aoVoz = ::vozDaPonte, aoAudio = { altoFalante.tocar(it) },
        querVoz = { _temSaidaDeSom.value && _ajustes.value.voz },
        querVozPc = { _ajustes.value.vozPc },
        aoAjustes = ::ajustesDaPonte,
        aoSessoes = { _sessoes.value = it },
        aoHistorico = { h -> if (_historico.value != null) _historico.value = h },
        aoFoco = ::focoDaPonte,
        aoEsperas = { _esperas.value = it },
    )
    val ligacao: StateFlow<Ligacao> = ponte.estado
    /** a ponte em casa vai pelo Wi-Fi do relógio, não pelo celular */
    private val rede = RedeLocal(app) { wifi -> viewModelScope.launch { redeMudou(wifi) } }
    private var esperaWifi: Job? = null

    /** os agentes instalados no PC (vêm no "ola"): cada orbe da lista tem um deles */
    private val _agentesPc = MutableStateFlow<List<AgenteInfo>>(emptyList())
    val agentesPc: StateFlow<List<AgenteInfo>> = _agentesPc

    // os agentes do PC dizem quais orbes têm instâncias
    val aparencia: StateFlow<Aparencia> = combine(ajustes, _agentesPc) { a, _ -> aparenciaDe(a) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Aparencia())

    private var carregado = false
    private var naTela = false

    /** O microfone daqui acompanha uma sessão aberta ([iniciarEscuta]), ou o "trigger" está a caminho. */
    private val _emEscuta = MutableStateFlow(false)

    /**
     * Em sessão: aberta no PC (ouvindo, pensando, falando, trabalhando) ou com a
     * escuta daqui a caminho. A tela não apaga em sessão, com qualquer ajuste.
     */
    val emSessao: StateFlow<Boolean> = combine(_retrato, _emEscuta) { r, e -> r.visivel || e }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** O relógio escureceu com o app aberto e ele ficou no ambiente (o orbe parado e apagado). */
    private val _emAmbiente = MutableStateFlow(false)
    val emAmbiente: StateFlow<Boolean> = _emAmbiente
    /** mudanças feitas antes de o disco responder */
    private val pendentes = ArrayList<(Ajustes) -> Ajustes>()

    init {
        viewModelScope.launch(Dispatchers.IO) { _temSaidaDeSom.value = AltoFalante.temSaida(app) }
        cena.aoMudar = {
            if (!it.visivel) _dono.value = null
            else if (_dono.value == null) aparenciaDe(_ajustes.value).let { ap -> _dono.value = ap.skin to ap.instancia }
            acertarDono(aparenciaDe(_ajustes.value))
            _retrato.value = it
            seguirEscuta(it)
            if (aoFundo) acompanharFundo(it)
        }
        viewModelScope.launch {
            // o que mudou antes de o disco responder (o endereço vindo pelo adb) vale por cima
            _ajustes.value = pendentes.fold(cofre.fluxo.first()) { a, f -> f(a) }
            carregado = true
            aplicar(_ajustes.value)
            if (pendentes.isNotEmpty()) gravarJa()
            pendentes.clear()
            if (_ajustes.value.sacudida) ServicoSacudida.ligar(getApplication())
            if (naTela) conectar()
        }
        viewModelScope.launch {
            // a sessão que acaba recomeça a contagem do orbe parado (a ponte que dorme)
            emSessao.collect { mexeuEm = android.os.SystemClock.uptimeMillis() }
        }
        viewModelScope.launch {
            // sem a ponte ninguém avisa que a sessão fechou: o orbe volta a esperar
            ligacao.collect {
                if (it !is Ligacao.Conectada) {
                    _sessoes.value = null
                    altoFalante.cortar()
                    pararEscuta()
                    adormecer()
                } else if (querOuvir) {
                    iniciarEscuta()
                }
            }
        }
    }

    // o olá do PC (com a imagem do fundo em base64, ~40 KB) e o tema dele, lidos
    // uma vez por texto: a aparência é refeita a cada linha do agente, e o parse
    // de novo a cada vez enchia a memória de objetos grandes
    // anulável de propósito: o init (o combine) chega aqui antes deste campo ser inicializado
    private var olaLido: Triple<String, io.orbe.watch.dados.Ola?, Tema>? = null

    private fun olaDe(texto: String): Pair<io.orbe.watch.dados.Ola?, Tema> {
        val l = olaLido
        if (l != null && (l.first === texto || l.first == texto)) return l.second to l.third
        val pc = texto.takeIf { it.isNotEmpty() }?.let(Protocolo::ola)
        val tema = pc?.let { Tema.de(it.tema, it.papel, Tema.imagemDoFundo(it.papelId, it.papelImagem)) } ?: Tema.Padrao
        olaLido = Triple(texto, pc, tema)
        return pc to tema
    }

    private fun aparenciaDe(a: Ajustes): Aparencia {
        val (pc, tema) = olaDe(a.pc)
        val skin = Skin.de(if (a.seguirPc && pc != null) pc.orbe.skin else a.skin)
        val instancia = if (temInstancias(agenteDe(skin, a))) a.instancia.coerceAtLeast(0) else 0
        // sem o papel de parede não há vidro a pôr atrás do orbe: ele fica no preto
        val fundo = a.fundo && tema.papel.isNotEmpty()
        return if (a.seguirPc && pc != null) Aparencia(skin, pc.orbe.glitch, pc.orbe.glitch, tema, instancia, fundo)   // no PC as linhas vêm com o glitch
        else Aparencia(skin, a.glitchDe(skin), a.linhas, tema, instancia, fundo)
    }

    /** Leva os ajustes para a cena (quem desenha lê dela). */
    private fun aplicar(a: Ajustes) {
        val ap = aparenciaDe(a)
        cena.skin = ap.skin
        cena.glitch = ap.glitch
        cena.varredura = ap.linhas
        cena.tamanho = a.tamanhoDe(ap.skin).toDouble()
        cena.corTema = ap.corFigura()
        cena.accent = ap.corAnel()
        acertarDono(ap)
        io.orbe.watch.gl.Motor.ritmo(a.quadrosParado, a.quadrosAtivo)
    }

    /** O orbe em tela mostra a sessão só se foi dele que ela abriu. */
    private fun acertarDono(ap: Aparencia) {
        val d = _dono.value
        cena.alheia = d != null && d != (ap.skin to ap.instancia)
    }

    // a ponte também muda os ajustes (a aparência do PC chega na thread da rede)
    @Synchronized
    private fun mudar(f: (Ajustes) -> Ajustes) {
        if (!carregado) pendentes.add(f)
        val novo = f(_ajustes.value)
        if (novo == _ajustes.value) return
        _ajustes.value = novo
        aplicar(novo)
        if (carregado) agendarGravacao()
        enviarAgente()
    }

    // ── gravar os ajustes: no máximo uma vez a cada GRAVAR_MS, sempre o valor mais novo ──
    //
    // Rolar a lista de orbes muda a skin a cada página, e cada mudança gravava o
    // arquivo inteiro. Agora a primeira mudança marca uma gravação para daqui a
    // pouco, e as seguintes só esperam por ela; sair da frente grava na hora.

    private var gravacao: Job? = null

    // a ponte também muda os ajustes, na thread da rede: as duas sob o cadeado do mudar
    @Synchronized
    private fun agendarGravacao() {
        if (gravacao?.isActive == true) return
        gravacao = escritas.launch {
            delay(GRAVAR_MS)
            cofre.gravar(_ajustes.value)
        }
    }

    /** Grava já o que estiver pendente (o orbe saindo da frente, o app fechando). */
    @Synchronized
    private fun gravarJa() {
        gravacao?.cancel()
        gravacao = escritas.launch { cofre.gravar(_ajustes.value) }
    }

    /**
     * Mudança nos ajustes que o app do PC também edita: ganha a hora de agora
     * e vai para lá (vale a mais nova dos dois lados).
     */
    private fun mudarSinc(f: (Ajustes) -> Ajustes) {
        mudar { f(it).copy(t = System.currentTimeMillis()) }
        ponte.enviar(Protocolo.ajustes(_ajustes.value.sincronia()))
    }

    /** O PC mandou os ajustes: ficam se forem mais novos que os daqui. */
    private fun ajustesDaPonte(s: Sincronia) {
        if (s.t <= _ajustes.value.t) return
        val sacudia = _ajustes.value.sacudida
        mudar { it.com(s) }
        // a chave da sacudida mexida no PC liga e desliga o serviço daqui
        val sacode = _ajustes.value.sacudida
        if (sacode != sacudia) {
            if (sacode) ServicoSacudida.ligar(getApplication()) else ServicoSacudida.desligar(getApplication())
        }
    }

    // o último "agente", a última "vaga" e o último "orbe" que a ponte desta conexão recebeu; null = mandar de novo
    private var agenteEnviado: String? = null
    private var vagaEnviada: Int? = null
    private var orbeEnviado: String? = null

    /**
     * A ponte fica sabendo do agente do orbe em tela (a sessão aberta daqui usa
     * ele), num agente com instâncias da vaga dele (a sessão que ele mostra), e
     * da skin e da cor dele (o orbe do PC pode seguir o daqui).
     */
    private fun enviarAgente() {
        if (ligacao.value !is Ligacao.Conectada) return
        val a = _ajustes.value
        val ap = aparenciaDe(a)
        val id = a.agentes[ap.skin.id].orEmpty()
        if (id != agenteEnviado && ponte.enviar("agente $id".trim())) agenteEnviado = id
        val grupo = skinsDoAgente(agenteDe(ap.skin, a), a)
        val j = grupo.indexOf(ap.skin)
        val vaga = if (j >= 0) Instancias.vaga(ap.instancia, j, grupo.size) else -1
        if (vaga != vagaEnviada && ponte.enviar(if (vaga < 0) "vaga" else "vaga $vaga")) vagaEnviada = vaga
        val orbe = "orbe ${ap.skin.id} ${Instancias.corHex(ap.instancia)}"
        if (orbe != orbeEnviado && ponte.enviar(orbe)) orbeEnviado = orbe
    }

    private fun adormecer() {
        cena.comando("hold 0")
        cena.comando("hide")
    }

    // ── ajustes ──

    fun servidor(v: String) {
        mudar { it.copy(servidor = v.trim()) }
        if (naTela) conectar()
    }

    fun token(v: String) {
        mudar { it.copy(token = v.trim().lowercase()) }
        if (naTela) conectar()
    }

    /** Escolher o avatar no relógio solta o orbe do PC: fica o que o relógio escolheu. */
    fun skin(s: Skin) = mudarSinc { it.copy(seguirPc = false, skin = s.id, instancia = it.instanciaDe(s), glitch = aparencia.value.glitch, linhas = aparencia.value.linhas) }

    /** O glitch do orbe em tela: cada orbe guarda o seu. */
    fun glitch(v: Boolean) = mudarSinc {
        val sk = aparencia.value.skin
        it.copy(seguirPc = false, glitches = it.glitches + (sk.id to v), linhas = aparencia.value.linhas, skin = sk.id)
    }

    fun linhas(v: Boolean) = mudarSinc { it.copy(seguirPc = false, linhas = v, glitch = aparencia.value.glitch, skin = aparencia.value.skin.id) }

    fun seguirPc(v: Boolean) = mudarSinc { it.copy(seguirPc = v) }

    /**
     * Rolou a lista para outra skin: como escolher no menu, solta o orbe do PC;
     * o orbe novo entra pela instância em que ficou.
     */
    fun girar(s: Skin) {
        val a = _ajustes.value
        if (a.seguirPc) mudarSinc { it.copy(seguirPc = false, skin = s.id, instancia = it.instanciaDe(s), glitch = aparencia.value.glitch, linhas = aparencia.value.linhas) }
        else mudar { it.copy(skin = s.id, instancia = it.instanciaDe(s)) }        // a skin em tela não vai ao PC, só o agente dela
    }

    /** Dois dedos levaram a outra instância do orbe da [skin]: a sessão do PC que ele mostra, guardada para a volta. */
    fun instancia(k: Int, skin: Skin = aparencia.value.skin) = mudar {
        val i = k.coerceAtLeast(0)
        it.copy(instancia = i, instancias = it.instancias + (skin.id to i))
    }

    /** os orbes cuja resposta espera a vez de falar, na ordem (o primeiro aparece no canto) */
    private val _esperas = MutableStateFlow<List<Espera>>(emptyList())
    val esperas: StateFlow<List<Espera>> = _esperas

    /**
     * A resposta de um orbe em segundo plano tomou a vez: o relógio passa a ele,
     * na instância da vaga dele, e vem para a frente (não por cima da Hina).
     */
    private fun focoDaPonte(f: Foco) {
        val skin = Skin.entries.firstOrNull { it.id == f.skin } ?: return
        val a = _ajustes.value
        val grupo = skinsDoAgente(agenteDe(skin, a), a)
        val k = if (f.vaga >= 0 && grupo.isNotEmpty()) f.vaga / grupo.size else 0
        if (a.seguirPc) mudarSinc { it.copy(seguirPc = false, skin = skin.id, instancia = k, glitch = aparencia.value.glitch, linhas = aparencia.value.linhas) }
        else mudar { it.copy(skin = skin.id, instancia = k) }
        if (!naTela) ServicoEspera.trazer(getApplication())
    }

    /** O fundo do menu atrás dos orbes. */
    fun fundo(v: Boolean) = mudarSinc { it.copy(fundo = v) }

    fun fundoMenu(v: Boolean) = mudar { it.copy(fundoMenu = v) }

    /** O tamanho do orbe em tela: cada skin guarda o seu. */
    fun tamanho(v: Float) {
        val skin = aparencia.value.skin.id
        mudarSinc { it.copy(tamanhos = it.tamanhos + (skin to v.coerceIn(Ajustes.TAMANHO_MIN, Ajustes.TAMANHO_MAX))) }
    }

    fun texto(v: Boolean) = mudarSinc { it.copy(texto = v) }

    fun microfone(v: Boolean) = mudarSinc { it.copy(microfone = v) }

    /** A ponte fica sabendo ao conectar se o relógio toca a resposta: mudou, reconecta. */
    fun voz(v: Boolean) {
        mudarSinc { it.copy(voz = v) }
        if (!v) altoFalante.cortar()
        if (naTela) conectar(forcar = true)
    }

    /** Também vai no "ola": mudou, reconecta. */
    fun vozPc(v: Boolean) {
        mudarSinc { it.copy(vozPc = v) }
        if (naTela) conectar(forcar = true)
    }

    fun vibrar(v: Boolean) = mudarSinc { it.copy(vibrar = v) }

    fun etapas(v: Boolean) = mudarSinc { it.copy(etapas = v) }

    fun proximoIdiomaEtapas() = mudarSinc { a ->
        val l = Ajustes.IDIOMAS_ETAPAS
        a.copy(idiomaEtapas = l[(l.indexOf(a.idiomaEtapas) + 1) % l.size])
    }

    /** O agente do orbe da [skin]: o escolhido, ou o Claude (o padrão). */
    fun agenteDe(skin: Skin, a: Ajustes = _ajustes.value): String = a.agentes[skin.id].orEmpty().ifEmpty { "claude" }

    /** O agente tem uma sessão por instância do orbe: o Claude, e os que o PC roda numa janela do terminal. */
    fun temInstancias(agente: String): Boolean =
        agente == "claude" || _agentesPc.value.any { it.id == agente && it.instancias }

    /** As skins cujos orbes são do [agente], na ordem da lista; vazia se ele não tem instâncias. */
    fun skinsDoAgente(agente: String, a: Ajustes = _ajustes.value): List<Skin> =
        if (!temInstancias(agente)) emptyList() else a.skins().filter { agenteDe(it, a) == agente }

    /** O agente seguinte para o orbe da [skin], em roda: o padrão (Claude) e os do PC. */
    fun proximoAgente(skin: Skin) {
        val ids = listOf("") + _agentesPc.value.map { it.id }.filter { it != "claude" }
        val atual = _ajustes.value.agentes[skin.id].orEmpty().let { if (it == "claude") "" else it }
        val prox = ids[(ids.indexOf(atual) + 1).mod(ids.size)]
        mudarSinc { it.copy(agentes = it.agentes + (skin.id to prox)) }
    }

    fun pediuMicrofone() = mudar { it.copy(pediuMicrofone = true) }

    /** A chave da sacudida liga e desliga o serviço que escuta o pulso. */
    fun sacudida(v: Boolean) {
        mudarSinc { it.copy(sacudida = v) }
        if (v) ServicoSacudida.ligar(getApplication()) else ServicoSacudida.desligar(getApplication())
    }

    fun calibrarSacudida(p: Picos) = mudarSinc { it.copy(sacudidaFora = p.fora, sacudidaDentro = p.dentro) }

    fun sacudidaPadrao() = calibrarSacudida(Picos(Sacudida.FORA_MIN, Sacudida.DENTRO_MIN))

    /** Com o orbe aberto, a sacudida só para fora sai dele. */
    fun sair(v: Boolean) = mudarSinc { it.copy(sair = v) }

    fun batidas(v: Boolean) = mudar { it.copy(batidas = v) }

    /** As calibrações das batidas: as janelas das tentativas de cada uma (o modelo sai delas). */
    fun calibrarBatidas(tipo: Calibracao, janelas: List<Janela>) = mudar { a ->
        val t = Janela.texto(janelas)
        when (tipo) {
            Calibracao.FRACA -> a.copy(batidaFracas = t)
            Calibracao.FORTE -> a.copy(batidaFortes = t)
            Calibracao.NADA -> a.copy(batidaNada = t)
            else -> a
        }
    }

    fun batidasPadrao() = mudar { it.copy(batidaFracas = "", batidaFortes = "", batidaNada = "") }

    /** Passos da lista de orbes pedidos pelas ações (+1 o seguinte, -1 o anterior); o OrbeApp leva à lista, como a coroa. */
    val passosLista = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /**
     * Um comando das batidas (só com o app aberto): 0 a 3 os toques fracos, 4 a 7
     * os que terminam em estalo ([io.orbe.watch.gesto.Comando.indice]). Só vibra se ele acontece: sem a ponte, uma ação de
     * sessão não faz nada e o relógio fica quieto (antes vibrava na detecção e o
     * comando morria calado).
     */
    fun batida(i: Int, podeGravar: Boolean, forte: Boolean) {
        val acao = _ajustes.value.batidasAcoes.getOrNull(i) ?: return
        val lig = ligacao.value
        val ponte = when (lig) {
            is Ligacao.Conectada -> "conectada"
            is Ligacao.Conectando -> "conectando"
            is Ligacao.Falha -> "caída"
            else -> "desligada"
        }
        val vai = acao != AcaoToque.NADA && acao != AcaoToque.FALAR && (!acao.precisaPonte || lig is Ligacao.Conectada)
        android.util.Log.i("OrbeBatida", "ação ${acao.id}: ponte $ponte, sessão ${if (_retrato.value.visivel) "aberta" else "fechada"}${if (_retrato.value.travado) " (live)" else ""}${if (vai) "" else ": não executa, não vibra"}")
        if (!vai) return
        avisarBatida(forte)
        executar(acao, podeGravar)
    }

    /** A voz do orbe (no PC) fala [texto]: as instruções da calibração. */
    fun dizer(texto: String) {
        ponte.enviar("dizer " + texto.replace('\n', ' '))
    }

    /** O intervalo entre toques passa à escolha seguinte (e volta ao começo). */
    fun proximoIntervalo() = mudar { a ->
        val l = io.orbe.watch.gesto.Batida.INTERVALOS
        a.copy(batidasIntervalo = l[(l.indexOf(a.batidasIntervalo) + 1) % l.size])
    }

    fun batidaAcao(i: Int, acao: AcaoToque) = mudar { a ->
        if (i !in a.batidasAcoes.indices) a else a.copy(batidasAcoes = a.batidasAcoes.toMutableList().also { it[i] = acao })
    }

    fun proximaBatida(i: Int) = mudar { a -> a.copy(batidasAcoes = proxima(a.batidasAcoes, i + 1, AcaoToque.CURTAS)) }

    /** O fora mínimo de sair, da calibração; 0 volta ao padrão. */
    fun calibrarSair(fora: Float) = mudarSinc { it.copy(sairFora = fora) }

    /** Leva a [skin] um lugar para cima (-1) ou para baixo (+1) na lista dos orbes. */
    fun mover(skin: Skin, passo: Int) = mudarSinc { a ->
        val lista = a.skins().toMutableList()
        val i = lista.indexOf(skin)
        val j = i + passo
        if (i < 0 || j !in lista.indices) return@mudarSinc a
        lista[i] = lista[j].also { lista[j] = skin }
        a.copy(ordem = lista.map { it.id })
    }

    // ── desempenho (aba Desempenho) ──

    fun telaAcesa(v: Boolean) = mudar { it.copy(telaAcesa = v) }

    fun proximoTempoTela() = mudar { it.copy(telaTempo = seguinte(Ajustes.TEMPOS_TELA, it.telaTempo)) }

    fun usarAmbiente(v: Boolean) = mudar { it.copy(ambiente = v) }

    fun proximosQuadrosParado() = mudar { it.copy(quadrosParado = seguinte(Ajustes.QUADROS_PARADO, it.quadrosParado)) }

    fun proximosQuadrosAtivo() = mudar { it.copy(quadrosAtivo = seguinte(Ajustes.QUADROS_ATIVO, it.quadrosAtivo)) }

    fun sensoresLote(v: Boolean) = mudar { it.copy(sensoresLote = v) }

    fun ponteParada(v: Boolean) {
        mudar { it.copy(ponteParada = v) }
        vigiarPonteParada()
    }

    fun esperaLeve(v: Boolean) = mudar { it.copy(esperaLeve = v) }

    fun proximaRede() {
        mudar { it.copy(rede = seguinte(Ajustes.REDES, it.rede)) }
        if (naTela) conectar(forcar = true)
    }

    private fun <T> seguinte(l: List<T>, v: T): T = l[(l.indexOf(v) + 1).mod(l.size)]

    /** Por onde o app sai agora: o Wi-Fi do relógio, o Bluetooth do celular ou a rede celular. */
    fun transporte(): String {
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java) ?: return "?"
        val c = cm.getNetworkCapabilities(cm.boundNetworkForProcess ?: cm.activeNetwork) ?: return "sem rede"
        return when {
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth do celular"
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "rede celular"
            else -> "outra"
        }
    }

    /**
     * As chaves da aba Desempenho pelo adb, para medir sem tocar no relógio:
     *   adb shell am start -n io.orbe.watch/.MainActivity --es desempenho "telaAcesa=0,telaTempo=30,quadrosParado=15"
     */
    fun desempenhoPorAdb(texto: String) {
        val rede = _ajustes.value.rede
        mudar { a ->
            var b = a
            for (par in texto.split(',', ';')) {
                val kv = par.split('=', limit = 2)
                if (kv.size != 2) continue
                val k = kv[0].trim()
                val v = kv[1].trim()
                val sim = v == "1" || v.equals("true", ignoreCase = true)
                val n = v.toIntOrNull()
                b = when (k) {
                    "telaAcesa" -> b.copy(telaAcesa = sim)
                    "telaTempo" -> if (n in Ajustes.TEMPOS_TELA) b.copy(telaTempo = n!!) else b
                    "ambiente" -> b.copy(ambiente = sim)
                    "quadrosParado" -> if (n in Ajustes.QUADROS_PARADO) b.copy(quadrosParado = n!!) else b
                    "quadrosAtivo" -> if (n in Ajustes.QUADROS_ATIVO) b.copy(quadrosAtivo = n!!) else b
                    "sensoresLote" -> b.copy(sensoresLote = sim)
                    "ponteParada" -> b.copy(ponteParada = sim)
                    "esperaLeve" -> b.copy(esperaLeve = sim)
                    "rede" -> if (v in Ajustes.REDES) b.copy(rede = v) else b
                    else -> {
                        android.util.Log.w(io.orbe.watch.gl.Diagnostico.TAG, "chave de desempenho desconhecida: $k")
                        b
                    }
                }
            }
            b
        }
        android.util.Log.i(io.orbe.watch.gl.Diagnostico.TAG, "ajustes pelo adb: $texto")
        if (_ajustes.value.rede != rede && naTela) conectar(forcar = true)
        if (!_ajustes.value.ponteParada) _ponteDormindo.value = false
        vigiarPonteParada()
    }

    /**
     * O relógio escureceu com o app aberto ([entrou] true) ou voltou. No ambiente
     * o orbe fica parado e apagado; sem sessão a ponte sai (a volta reconecta) e
     * a sacudida passa ao serviço, que a lê como com a tela apagada; em sessão
     * tudo segue, e com o agente trabalhando a espera segura a resposta como
     * se o orbe tivesse saído da tela.
     */
    fun ambiente(entrou: Boolean) {
        if (_emAmbiente.value == entrou) return
        _emAmbiente.value = entrou
        cena.ambiente = entrou
        if (entrou) {
            if (pedidoNoAr()) esperarAoFundo()
            else if (!emSessao.value) soltarPonte()
        } else {
            if (aoFundo) {
                aoFundo = false
                tetoFundo?.cancel()
                ServicoEspera.desligar(getApplication())
            }
            if (carregado && naTela) conectar()
        }
    }

    // ── a ponte que sai com o orbe parado ([Ajustes.ponteParada]) ──

    private var ponteParadaJob: Job? = null
    /** o último toque, giro da coroa ou fim de sessão (uptime): a contagem do orbe parado parte dele */
    @Volatile private var mexeuEm = 0L
    private val _ponteDormindo = MutableStateFlow(false)
    /** A ponte saiu pelo orbe parado: o rodapé diz "dormindo" e o toque reconecta. */
    val ponteDormindo: StateFlow<Boolean> = _ponteDormindo

    /**
     * Sem sessão por [Ajustes.PONTE_PARADA_MS] desde o último [mexeu], a ponte
     * sai. Uma corrotina só, que confere o prazo quando ele vence: o toque não
     * recomeça nada, só anda o [mexeuEm].
     */
    private fun vigiarPonteParada() {
        if (!_ajustes.value.ponteParada || !naTela) {
            ponteParadaJob?.cancel()
            return
        }
        if (ponteParadaJob?.isActive == true) return
        ponteParadaJob = viewModelScope.launch {
            while (true) {
                val falta = mexeuEm + Ajustes.PONTE_PARADA_MS - android.os.SystemClock.uptimeMillis()
                if (falta > 0) {
                    delay(falta)
                    continue
                }
                if (emSessao.value || aoFundo) {
                    mexeuEm = android.os.SystemClock.uptimeMillis()
                    continue
                }
                if (ligacao.value !is Ligacao.SemServidor) {
                    android.util.Log.i(io.orbe.watch.gl.Diagnostico.TAG, "ponte dormindo: orbe parado")
                    soltarPonte()
                    _ponteDormindo.value = true
                }
                break
            }
        }
    }

    /** Um toque, a coroa, a sacudida: a contagem do orbe parado recomeça, e a ponte que dormia volta. */
    fun mexeu() {
        mexeuEm = android.os.SystemClock.uptimeMillis()
        if (_ponteDormindo.value && naTela) {
            _ponteDormindo.value = false
            conectar()
            vigiarPonteParada()
        }
    }

    // ── ponte ──

    fun entrou() {
        naTela = true
        mexeuEm = android.os.SystemClock.uptimeMillis()
        _ponteDormindo.value = false
        if (aoFundo) {
            aoFundo = false
            tetoFundo?.cancel()
            ServicoEspera.desligar(getApplication())
        }
        if (carregado) conectar()
        vigiarPonteParada()
    }

    /** O orbe saiu da tela; [fechando]: o app fechou de vez (o ViewModel e a ponte vão junto). */
    fun saiu(fechando: Boolean = false) {
        naTela = false
        ponteParadaJob?.cancel()
        if (gravacao?.isActive == true) gravarJa()
        pararEscuta()
        // fora da tela, os toques contados não valem mais
        contagem?.cancel()
        toques = 0
        largar()
        // com um pedido esperando a resposta, a ponte fica: a resposta traz o orbe de volta.
        // Fechando de vez não há o que esperar: a ponte morre com o ViewModel
        if (pedidoNoAr() && !fechando) esperarAoFundo() else soltarPonte()
    }

    private fun soltarPonte() {
        altoFalante.cortar()
        esperaWifi?.cancel()
        ponte.desligar()        // a sessão continua no PC; na volta, a ponte conta em que pé está
        rede.soltar()
    }

    // ── a resposta com o orbe fora da tela ──
    //
    // Saiu do orbe (o botão, a sacudida para fora, a tela que apagou) com o
    // agente pensando: o ServicoEspera segura o relógio acordado e a ponte
    // segue. A resposta (a voz que chega, ou a sessão que volta a ouvir) acende
    // a tela e traz o orbe para a frente, falando. A sessão que fecha sem
    // resposta, a voz que acaba sem a tela ter vindo ou o teto encerram a espera.

    @Volatile private var aoFundo = false
    private var tetoFundo: Job? = null

    /** O agente está pensando num pedido: a resposta ainda vem. */
    private fun pedidoNoAr(): Boolean =
        ligacao.value is Ligacao.Conectada && _retrato.value.visivel && pcTrabalhando()

    /**
     * O estado que o PC mandou por último ("show", "state", "line"). O do
     * retrato não serve aqui: a voz tocada no relógio o põe em "speaking" pelo
     * nível, e as etapas ditas no meio do trabalho também tocam.
     */
    @Volatile private var estadoPc = Estado.IDLE

    private fun pcTrabalhando() = estadoPc == Estado.THINKING || estadoPc == Estado.TOOLS

    private fun acompanharPc(linha: String) {
        val l = linha.trim()
        val op = l.substringBefore(' ')
        val arg = l.substringAfter(' ', "").trim()
        when (op) {
            "show", "state" -> {
                val e = Estado.de(arg.ifEmpty { "listening" })
                if (e < 0) return
                estadoPc = e
            }
            "line" -> {
                if (estadoPc != Estado.THINKING) estadoPc = Estado.TOOLS
                return
            }
            "hide" -> {
                estadoPc = Estado.IDLE
                return
            }
            else -> return
        }
        // a resposta: o PC passou a falar ou voltou a ouvir (as etapas vêm em "tools")
        if (aoFundo && !pcTrabalhando()) trazer()
    }

    private fun esperarAoFundo() {
        aoFundo = true
        trouxe = false
        ServicoEspera.ligar(getApplication(), leve = _ajustes.value.esperaLeve)
        tetoFundo?.cancel()
        tetoFundo = viewModelScope.launch {
            delay(ServicoEspera.TETO_MS)
            if (aoFundo && !naTela) largarFundo()
        }
    }

    private fun largarFundo() {
        aoFundo = false
        tetoFundo?.cancel()
        ServicoEspera.desligar(getApplication())
        if (!naTela) soltarPonte()
    }

    private fun acompanharFundo(r: Retrato) {
        // a ponte que cai ou reconecta esconde o orbe e logo repete o que o PC
        // mostra: só a sessão que segue fechada encerra a espera
        if (!r.visivel) viewModelScope.launch {
            delay(CONFIRMA_FECHOU)
            if (aoFundo && !_retrato.value.visivel && ligacao.value is Ligacao.Conectada) largarFundo()
        }
    }

    /** A resposta chegou: a tela acende e o orbe vem para a frente (uma vez por espera). */
    @Volatile private var trouxe = false
    private fun trazer() {
        if (trouxe) return
        trouxe = true
        ServicoEspera.trazer(getApplication())
    }

    private fun conectar(forcar: Boolean = false) {
        val a = _ajustes.value
        _ponteDormindo.value = false
        // "auto": a LAN só responde pelo Wi-Fi do relógio; a tailnet e o resto vão pela rede
        // que o sistema escolher (com o celular perto, o Bluetooth dele, sem ligar o Wi-Fi)
        val local = when (a.rede) {
            "wifi" -> true
            "celular" -> false
            else -> Protocolo.local(a.servidor)
        }
        if (local) rede.prender() else rede.soltar()
        esperaWifi?.cancel()
        if (local && !rede.noWifi) {
            // o Wi-Fi do relógio dorme e leva uns segundos para voltar: espera por ele
            // antes de sair pelo celular, para a conexão não nascer na rede que cai
            esperaWifi = viewModelScope.launch {
                delay(ESPERA_WIFI)
                ponte.ligar(a.servidor, a.token, forcar)
            }
            return
        }
        ponte.ligar(a.servidor, a.token, forcar)
    }

    /**
     * O Wi-Fi chegou ou caiu e a conexão aberta ficou na rede velha. No meio de
     * uma sessão ela fica até cair sozinha (religar cortaria a escuta e a voz);
     * a próxima já nasce na rede nova.
     */
    private fun redeMudou(wifi: Boolean) {
        if (!naTela) return
        val ocupado = escutando || segurando || _retrato.value.visivel
        if (esperaWifi?.isActive == true || !wifi || !ocupado) conectar(forcar = true)
    }

    private fun linhaDaPonte(linha: String) {
        cena.comando(linha)
        acompanharPc(linha)
    }

    private fun olaDaPonte(ola: Ola, bruto: String) {
        // a ponte repete em seguida o que o orbe do PC está mostrando
        adormecer()
        _agentesPc.value = ola.agentes
        _sessoes.value = ola.sessoes
        _abreClaude.value = ola.abreClaude
        agenteEnviado = null
        vagaEnviada = null
        orbeEnviado = null
        mudar { it.copy(pc = comPapel(ola, bruto, it.pc)) }
        // os ajustes daqui vão ao PC; se os de lá forem mais novos, a ponte devolve os dela
        ponte.enviar(Protocolo.ajustes(_ajustes.value.sincronia()))
        enviarAgente()
    }

    private fun configDaPonte(ola: Ola, bruto: String) = mudar { it.copy(pc = comPapel(ola, bruto, it.pc)) }

    /** O olá a guardar: se a ponte veio sem o papel de parede (o PC sem o Pillow, outro sistema), fica o de antes. */
    private fun comPapel(ola: Ola, bruto: String, antes: String): String {
        if (ola.papel.isNotEmpty()) return bruto
        val papel = Protocolo.ola(antes)?.papel.orEmpty()
        return if (papel.isEmpty()) bruto else Protocolo.guardar(ola.copy(papel = papel))
    }

    /** A resposta em voz: a taxa do áudio que vem, o fim dela, ou o corte. */
    private fun vozDaPonte(arg: String) {
        val taxa = arg.toIntOrNull()
        when {
            taxa != null && taxa in 8000..48000 -> altoFalante.abrir(taxa)
            arg == "fim" -> altoFalante.fim()
            arg == "corta" -> altoFalante.cortar()
        }
    }

    /** Abre ou fecha a sessão de voz no PC (o atalho de teclado do orbe). */
    fun alternarSessao() {
        ponte.enviar("toggle")
    }

    /** A chave do menu: o live é a sessão travada, com o microfone daqui acompanhando. */
    fun travar(v: Boolean, podeGravar: Boolean) {
        if (v) entrarNoLive(podeGravar) else ponte.enviar("release")
    }

    fun live(v: Boolean) = mudarSinc { it.copy(live = v) }

    /** A ação seguinte, em roda, para [n] toques curtos (1 a 4). */
    fun proximaAcao(n: Int) = mudarSinc { a -> a.copy(toques = proxima(a.toques, n, AcaoToque.CURTAS)) }

    /** A ação seguinte, em roda, para [n] toques com o último segurado (1 a 4). */
    fun proximaSegurar(n: Int) = mudarSinc { a -> a.copy(segurar = proxima(a.segurar, n, AcaoToque.entries)) }

    private fun proxima(l: List<AcaoToque>, n: Int, roda: List<AcaoToque>): List<AcaoToque> {
        if (n - 1 !in l.indices) return l
        return l.toMutableList().also { it[n - 1] = roda[(roda.indexOf(it[n - 1]) + 1) % roda.size] }
    }

    /** Pede à ponte as sessões passadas do agente do orbe em tela. */
    private fun abrirHistorico() {
        // o mesmo comando que abre fecha
        if (_historico.value != null) {
            fecharHistorico()
            tremer()
            return
        }
        if (ligacao.value !is Ligacao.Conectada) return
        enviarAgente()
        _historico.value = Historico(carregando = true)
        if (!ponte.enviar("historico")) _historico.value = Historico(erro = "sem a ponte")
        tremer()
    }

    fun fecharHistorico() {
        _historico.value = null
    }

    /**
     * Retoma uma sessão passada no orbe em tela. Num agente com instâncias ela
     * vai para uma instância sem sessão: a em tela, se está livre, ou a do fim.
     */
    fun retomar(id: String) {
        _historico.value = null
        val a = _ajustes.value
        val ap = aparenciaDe(a)
        val agente = agenteDe(ap.skin, a)
        val grupo = skinsDoAgente(agente, a)
        val j = grupo.indexOf(ap.skin)
        if (j >= 0) {
            val vagas = _sessoes.value.orEmpty().filter { it.agente == agente }.map { it.vaga }
            val ocupadas = Instancias.doOrbe(vagas, j, grupo.size)
            if (ap.instancia in ocupadas) instancia((ocupadas.maxOrNull() ?: -1) + 1)
        }
        enviarAgente()
        ponte.enviar("retomar $id")
        tremer()
    }

    // ── toque no orbe ──
    //
    // Os toques são contados aqui, numa janela que pausa com o dedo na tela.
    // Soltos todos curtos, viram a ação de [Ajustes.toques]; o último segurado,
    // a de [Ajustes.segurar]. Falar é segurar para falar: o "touch down" vai
    // quando fica claro que o dedo não está só arrastando a tela para o menu
    // (como o orbe.qml faz destravado para mover), e o daemon fecha a fala no
    // "touch up".

    private val preRolo = ArrayList<ByteArray>()     // a fala desde que o dedo encostou
    private var transmitindo = false
    private var segurando = false                    // "touch down" enviado, falta o "touch up"
    private var dedo = false                         // dedo no orbe: a escuta espera ele sair

    /**
     * Dedo encostou: a janela dos toques espera ele sair, os olhos vão para ele
     * e, se segurá-lo for falar, o microfone já começa a guardar.
     */
    fun toqueBaixo(x: Float, y: Float, podeGravar: Boolean) {
        dedo = true
        contagem?.cancel()
        desligarMicDaEscuta()           // o dedo assume a fala; a escuta volta quando ele sair
        olhar(x, y)
        val lig = ligacao.value
        val fala = _ajustes.value.segura(toques + 1) == AcaoToque.FALAR
        if (fala && podeGravar && _ajustes.value.microfone && lig is Ligacao.Conectada && lig.microfone) {
            synchronized(trava) {
                preRolo.clear()
                transmitindo = false
            }
            microfone.iniciar(::blocoDoMicrofone)
        }
    }

    fun olhar(x: Float, y: Float) {
        cena.olharX = x.toDouble()
        cena.olharY = y.toDouble()
    }

    // a fala vai em mensagens de AUDIO_JUNTOS blocos (90 ms), não uma por bloco de 30 ms:
    // um terço dos envios pelo rádio; a ponte do PC remonta os quadros de qualquer tamanho
    private val juntos = ByteArray(Microfone.BLOCO * AUDIO_JUNTOS)
    private var nJuntos = 0

    private fun blocoDoMicrofone(pcm: ByteArray, n: Int) {
        synchronized(trava) {
            if (transmitindo) {
                if (nJuntos + n > juntos.size) enviarJuntos()
                pcm.copyInto(juntos, nJuntos, 0, n)
                nJuntos += n
                if (nJuntos >= juntos.size) enviarJuntos()
            } else {
                preRolo.add(pcm.copyOf(n))
                if (preRolo.size > 34) preRolo.removeAt(0)      // 1 s
            }
        }
    }

    /** Manda o que a fala juntou (sob a [trava]): antes do "touch up" e ao parar a escuta, nada fica para trás. */
    private fun enviarJuntos() {
        if (nJuntos > 0) ponte.enviarAudio(juntos, nJuntos)
        nJuntos = 0
    }

    /** Parado além do tempo de segurar: o último dos toques contados, segurado ([Ajustes.segurar]). */
    fun toqueSegurou(podeGravar: Boolean) {
        val n = toques + 1
        toques = 0
        val acao = _ajustes.value.segura(n)
        if (acao != AcaoToque.FALAR) {
            soltarMicrofone()
            mostrarToque()
            executar(acao, podeGravar)
            return
        }
        altoFalante.cortar()            // quem fala por cima não espera a rede para o orbe calar
        segurando = true
        cena.toque = true
        tremer()
        synchronized(trava) {
            ponte.enviar("touch down")
            for (b in preRolo) ponte.enviarAudio(b, b.size)
            preRolo.clear()
            transmitindo = true
        }
    }

    private var toques = 0
    private var contagem: Job? = null

    /** Solto antes de andar e antes do tempo de segurar: toque curto, contado. */
    fun toqueCurto(podeGravar: Boolean) {
        largar()
        mostrarToque()
        // chegou ao maior número de toques que faz algo: não espera outro
        val teto = _ajustes.value.maisToques()
        if (teto == 0) {
            toques = 0
            return
        }
        if (++toques >= teto) {
            val n = toques
            toques = 0
            executar(_ajustes.value.toque(n), podeGravar)
            return
        }
        esperarToques(podeGravar)
    }

    /** A janela dos toques corre: sem outro dedo nela, os contados viram a ação deles. */
    private fun esperarToques(podeGravar: Boolean) {
        contagem?.cancel()
        contagem = viewModelScope.launch {
            delay(JANELA_TOQUES)
            val n = toques
            toques = 0
            executar(_ajustes.value.toque(n), podeGravar)
        }
    }

    /** O dedo já saiu (ou a ação não é falar): o orbe ainda cresce um instante, para o toque ser visto. */
    private fun mostrarToque() {
        cena.toque = true
        viewModelScope.launch {
            delay(140)
            if (!segurando) cena.toque = false
        }
    }

    private fun executar(acao: AcaoToque, podeGravar: Boolean) {
        when (acao) {
            AcaoToque.ABRIR -> toqueSessao(live = false, podeGravar)
            AcaoToque.LIVE -> toqueSessao(live = true, podeGravar)
            AcaoToque.ENCERRAR -> encerrar()
            AcaoToque.HISTORICO -> abrirHistorico()
            AcaoToque.PROXIMO -> passosLista.tryEmit(1)
            AcaoToque.ANTERIOR -> passosLista.tryEmit(-1)
            AcaoToque.RACIOCINIO -> {
                enviarAgente()
                ponte.enviar("raciocinio")
            }
            AcaoToque.FALAR, AcaoToque.NADA -> Unit
        }
    }

    /**
     * Cada ação faz só a sua, sem invadir a dos outros toques: fechar é só do
     * Encerrar. Abrir: fechada, abre (ou já no live, com a opção); aberta,
     * interrompe a fala ou o raciocínio, e ouvindo não faz nada. Live: liga o
     * live (abrindo, se precisar) ou, ligado, desliga, e a sessão segue.
     */
    private fun toqueSessao(live: Boolean, podeGravar: Boolean) {
        if (ligacao.value !is Ligacao.Conectada) return
        val r = _retrato.value
        val aberta = r.visivel || escutando
        val o = when {
            !aberta -> "abre${if (live || _ajustes.value.live) " no live" else ""}"
            live && r.travado -> "solta o live"
            live -> "entra no live"
            r.estado == Estado.SPEAKING || r.estado == Estado.THINKING || r.estado == Estado.TOOLS -> "interrompe"
            else -> "nada (sessão ouvindo)"
        }
        android.util.Log.i("OrbeBatida", "sessão: $o")
        when {
            !aberta -> abrir(live || _ajustes.value.live, podeGravar)
            live && r.travado -> {
                ponte.enviar("release")
                tremer()
            }
            live -> entrarNoLive(podeGravar)
            r.estado == Estado.SPEAKING || r.estado == Estado.THINKING || r.estado == Estado.TOOLS -> {
                altoFalante.cortar()
                ponte.enviar("interromper")
            }
        }
    }

    private fun abrir(live: Boolean, podeGravar: Boolean) {
        if (!iniciarEscuta(podeGravar)) {
            ponte.enviar("trigger")         // sem o microfone daqui, ouve o do PC
            tremer()
        }
        if (live) ponte.enviar("hold")
    }

    /** O live é a sessão travada; a fala passa a vir daqui se a sessão ainda não ouvia o relógio. */
    private fun entrarNoLive(podeGravar: Boolean) {
        if (escutando || !iniciarEscuta(podeGravar)) {
            if (!escutando && !_retrato.value.visivel) ponte.enviar("trigger")
            tremer()
        }
        ponte.enviar("hold")
    }

    /** Encerrar: fecha a sessão e, com o Claude no orbe, a sessão dele no PC. */
    private fun encerrar() {
        contagem?.cancel()
        altoFalante.cortar()
        pararEscuta()
        ponte.enviar("encerrar")
        tremer()
        viewModelScope.launch {
            delay(120)
            tremer()
        }
    }

    /** Dedo solto depois de segurar: fim da fala. */
    fun toqueSolto() = largar()

    /**
     * Virou arrasto: nada vai para o daemon além de fechar o que abriu, e os
     * toques contados antes voltam a esperar a janela.
     */
    fun toqueCancelado(podeGravar: Boolean) {
        largar()
        if (toques > 0) esperarToques(podeGravar)
    }

    private fun soltarMicrofone() {
        microfone.parar()
        synchronized(trava) {
            enviarJuntos()
            transmitindo = false
            preRolo.clear()
        }
    }

    private fun largar() {
        soltarMicrofone()
        cena.toque = false
        cena.olharX = Double.NaN
        cena.olharY = Double.NaN
        if (segurando) {
            segurando = false
            ponte.enviar("touch up")
            tremer()
        }
        dedo = false
        seguirEscuta(_retrato.value)    // a sessão aberta sem dedo volta a ouvir daqui
    }

    // ── aberto pela sacudida (HinaWatch): a sessão abre e o microfone fica com ela ──
    //
    // O "trigger" abre a sessão no PC como o atalho do teclado; a fala vai
    // inteira, sem dedo, e o daemon decide o fim pelo silêncio. O microfone
    // acompanha o orbe: ouvindo, transmite; pensando ou falando, descansa (a
    // voz da resposta no alto-falante do relógio não volta como fala).

    private var querOuvir = false
    private var escutando = false
    private var micDaEscuta = false
    private var sessaoVista = false             // a sessão já apareceu: sumir depois disso é fechar

    /** Pedido pela sacudida: abre a sessão assim que a ponte estiver conectada. */
    fun ouvir(podeGravar: Boolean) {
        if (!podeGravar || !_ajustes.value.microfone) return
        // já ouvindo, ou com a sessão aberta: outro "trigger" fecharia a sessão no PC
        if (querOuvir || escutando || _retrato.value.visivel) return
        querOuvir = true
        if (ligacao.value is Ligacao.Conectada) iniciarEscuta(podeGravar)
    }

    /** "trigger" com o microfone daqui acompanhando a sessão; false se não dá para ouvir daqui. */
    private fun iniciarEscuta(podeGravar: Boolean = true): Boolean {
        querOuvir = false
        val lig = ligacao.value
        if (!podeGravar || !_ajustes.value.microfone || lig !is Ligacao.Conectada || !lig.microfone) return false
        synchronized(trava) {
            escutando = true
            sessaoVista = false
        }
        _emEscuta.value = true
        ponte.enviar("trigger")
        ligarMicDaEscuta()
        tremer()
        return true
    }

    private fun ligarMicDaEscuta() {
        synchronized(trava) {
            if (!escutando || micDaEscuta || dedo) return
            micDaEscuta = true
            preRolo.clear()
            transmitindo = true
            microfone.iniciar(::blocoDoMicrofone)
        }
    }

    private fun desligarMicDaEscuta() {
        synchronized(trava) {
            if (!micDaEscuta) return
            micDaEscuta = false
            enviarJuntos()
            transmitindo = false
            microfone.parar()
        }
    }

    private fun pararEscuta() {
        querOuvir = false
        desligarMicDaEscuta()
        synchronized(trava) { escutando = false }
        _emEscuta.value = false
    }

    private fun seguirEscuta(r: Retrato) {
        if (!escutando || segurando || dedo) return
        when {
            r.visivel -> {
                sessaoVista = true
                if (r.estado == Estado.LISTENING) ligarMicDaEscuta() else desligarMicDaEscuta()
            }
            sessaoVista -> pararEscuta()        // a sessão fechou no PC
        }
    }

    /**
     * O aviso de um comando das batidas, na hora em que ele sai: um clique para
     * os toques, dois para os que terminam em estalo.
     */
    fun avisarBatida(forte: Boolean) {
        // a chave Vibrar vale aqui também (antes o aviso das batidas a ignorava)
        if (!_ajustes.value.vibrar) return
        // o motor de vibração balança o relógio: o detector fica surdo enquanto vibra
        io.orbe.watch.gesto.TelaTocada.vibrandoAte = android.os.SystemClock.elapsedRealtime() + if (forte) 250 else 150
        try {
            vibrador?.vibrate(
                if (forte) VibrationEffect.createWaveform(longArrayOf(0, 25, 60, 25), -1)
                else VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
            )
        } catch (e: Exception) {
            // relógio sem motor: segue sem vibrar
        }
    }

    private fun tremer() {
        if (!_ajustes.value.vibrar) return
        try {
            vibrador?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (e: Exception) {
            // relógio sem motor: segue sem vibrar
        }
    }

    override fun onCleared() {
        if (aoFundo) ServicoEspera.desligar(getApplication())
        microfone.parar()
        altoFalante.cortar()
        ponte.desligar()
        rede.soltar()
    }

    private companion object {
        /** as gravações dos ajustes: fora do ViewModel, para a última terminar mesmo com o app fechando */
        val escritas = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        /** o intervalo mínimo entre duas gravações dos ajustes */
        const val GRAVAR_MS = 300L
        /** blocos de 30 ms do microfone por mensagem da ponte */
        const val AUDIO_JUNTOS = 3
        /** quanto a ponte em casa espera o Wi-Fi acordar antes de sair pelo celular */
        const val ESPERA_WIFI = 4_000L
        /** toques dentro disto contam juntos (com o dedo fora); cada número tem a sua ação ([Ajustes.toques], [Ajustes.segurar]) */
        const val JANELA_TOQUES = 400L
        /** com o orbe fora da tela, quanto a sessão escondida espera para contar como fechada */
        const val CONFIRMA_FECHOU = 2_000L
    }
}
