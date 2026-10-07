package io.orbe.watch.dados

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import io.orbe.watch.gesto.Sacudida
import io.orbe.watch.orbe.Skin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** O que um número de toques no orbe faz: todos curtos ([Ajustes.toques]) ou o último segurado ([Ajustes.segurar]). */
enum class AcaoToque(val id: String, val nome: String) {
    /** fechado, abre (ou já no live, com a opção); aberto, entra no live; no live, interrompe, e só ouvindo, fecha */
    ABRIR("abrir", "Abrir sessão"),
    /** o live liga (abrindo, se precisar) ou, ligado, fecha */
    LIVE("live", "Ligar ou desligar live"),
    /** fecha a sessão e, com o Claude no orbe, a sessão dele no PC */
    ENCERRAR("encerrar", "Encerrar sessão"),
    /** as sessões passadas do agente do orbe, para retomar uma */
    HISTORICO("historico", "Abrir histórico"),
    /** segurar para falar: a fala vai enquanto o dedo fica (só no toque segurado) */
    FALAR("falar", "Falar enquanto segura"),
    /** o orbe seguinte da lista (como a coroa para baixo) */
    PROXIMO("proximo", "Próximo orbe"),
    /** o orbe anterior da lista */
    ANTERIOR("anterior", "Orbe anterior"),
    /** o raciocínio do agente do orbe passa ao nível seguinte (vale na próxima sessão dele) */
    RACIOCINIO("raciocinio", "Trocar raciocínio"),
    NADA("nada", "Nada");

    companion object {
        fun de(id: String?): AcaoToque? = entries.firstOrNull { it.id == id }
        /** um, dois e três toques como sempre foram; quatro, nada */
        val PADRAO = listOf(ABRIR, LIVE, ENCERRAR, NADA)
        /** segurar fala, como sempre foi; tocar e segurar abre o histórico */
        val PADRAO_SEGURAR = listOf(FALAR, HISTORICO, NADA, NADA)
        /** as que os toques curtos ciclam (falar só segurando) */
        val CURTAS = entries.filter { it != FALAR }
        /** 1 a 4 toques fracos; depois estalo, toca e estala, 2 toques e estala, 3 toques e estala */
        val PADRAO_BATIDAS = listOf(PROXIMO, ANTERIOR, NADA, NADA, LIVE, ENCERRAR, NADA, NADA)
    }
}

/** O que o relógio guarda. A config do daemon (agente, voz, conversa) fica no PC, no app do Orbe. */
data class Ajustes(
    val servidor: String = "",
    val token: String = "",
    /** avatar, glitch e linhas vêm do orbe do PC; escolher um avatar aqui desliga */
    val seguirPc: Boolean = true,
    val skin: String = "ofanim",
    /** a instância em tela no orbe do Claude: a sessão da vaga dela (Instancias); 0 = o próprio orbe */
    val instancia: Int = 0,
    /** a última instância de cada orbe, pela skin: voltar a ele é voltar a ela */
    val instancias: Map<String, Int> = emptyMap(),
    /** o glitch de cada orbe que ainda não tem o seu ([glitches]) */
    val glitch: Boolean = true,
    /** o glitch de cada orbe, pela skin: cada um guarda o seu */
    val glitches: Map<String, Boolean> = emptyMap(),
    /** as linhas de varredura (o tubo de TV), nas skins desenhadas */
    val linhas: Boolean = true,
    /** o fundo do menu (o papel de parede do PC borrado) também atrás dos orbes */
    val fundo: Boolean = true,
    /** o papel de parede borrado nos menus; desligado, os menus ficam no preto */
    val fundoMenu: Boolean = true,
    /** escala do orbe na tela: 1,0 enche o mostrador. É a de cada orbe que ainda não tem a sua ([tamanhos]) */
    val tamanho: Float = 1f,
    /** a escala de cada orbe, pela skin: cada um guarda a sua */
    val tamanhos: Map<String, Float> = emptyMap(),
    /** as linhas do raciocínio, abaixo do orbe */
    val texto: Boolean = true,
    /** segurando o orbe, a fala vem do microfone do relógio */
    val microfone: Boolean = true,
    /** a resposta toca no relógio, quando quem serve a ponte manda a voz para cá */
    val voz: Boolean = true,
    /** tocando aqui, a resposta toca também no PC (quando a ponte é o daemon) */
    val vozPc: Boolean = false,
    val vibrar: Boolean = true,
    /** o orbe fala as etapas do agente enquanto ele trabalha (a descrição de cada ferramenta) */
    val etapas: Boolean = false,
    /** a língua das etapas: [IDIOMAS_ETAPAS] */
    val idiomaEtapas: String = "pt",
    /** a permissão do microfone já foi pedida uma vez (depois disso, só pela chave do menu) */
    val pediuMicrofone: Boolean = false,
    /** o último "ola" da ponte: o app abre com a aparência do PC antes de conectar */
    val pc: String = "",
    /** o agente de cada orbe da lista, pela skin; "" = o padrão (Claude Code) */
    val agentes: Map<String, String> = emptyMap(),
    /** quando os ajustes que o PC também edita mudaram aqui por último (ms; 0 = nunca) */
    val t: Long = 0,
    /** uma sacudida do pulso, com a tela acesa, abre o orbe já ouvindo (duas são do HinaWatch) */
    val sacudida: Boolean = true,
    /** um toque no orbe fechado abre já no modo live (os turnos seguem sem tocar); sem isto, o segundo toque entra */
    val live: Boolean = false,
    /** o que 1, 2, 3 e 4 toques curtos no orbe fazem */
    val toques: List<AcaoToque> = AcaoToque.PADRAO,
    /** o que 1, 2, 3 e 4 toques fazem com o último segurado (1 = só segurar) */
    val segurar: List<AcaoToque> = AcaoToque.PADRAO_SEGURAR,
    /** limiares da sacudida em rad/s (fora e dentro); a calibração troca */
    val sacudidaFora: Float = Sacudida.FORA_MIN,
    val sacudidaDentro: Float = Sacudida.DENTRO_MIN,
    /** a ordem dos orbes na lista, pelas skins; vazia = a de [Skin.entries] */
    val ordem: List<String> = emptyList(),
    /** com o orbe aberto, uma sacudida do pulso só para fora sai dele (como no HinaWatch) */
    val sair: Boolean = true,
    /** o fora mínimo da sacudida de sair, em rad/s; 0 = sem calibrar, vale o padrão (o do HinaWatch, [Sacudida.FORA_MIN]) */
    val sairFora: Float = 0f,
    /** com o app aberto, batidas no pulso: fraca troca de orbe, o estalo abre e fecha o live */
    val batidas: Boolean = false,
    /**
     * As calibrações das batidas: as janelas (o perfil do movimento) das
     * tentativas do toque fraco, das do estalo e do que não é batida, em texto
     * ([io.orbe.watch.gesto.Janela.texto]); vazias = sem calibrar.
     */
    val batidaFracas: String = "",
    val batidaFortes: String = "",
    val batidaNada: String = "",
    /** a ação de cada combinação: toque fraco, dois fracos, estalo, dois estalos */
    val batidasAcoes: List<AcaoToque> = AcaoToque.PADRAO_BATIDAS,
    /** o intervalo máximo entre os toques de uma sequência (ms): também a espera até o comando sair */
    val batidasIntervalo: Long = io.orbe.watch.gesto.Batida.JUNTAR_MS,
    // ── desempenho (aba Desempenho): os padrões são o comportamento de antes ──
    /** com o app na frente a tela não apaga; em sessão ela nunca apaga, com ou sem esta chave */
    val telaAcesa: Boolean = true,
    /** sem [telaAcesa], fora de sessão: segundos de tela acesa depois do último toque; 0 = o tempo do sistema */
    val telaTempo: Int = 0,
    /** a tela que escurece com o app aberto deixa o orbe parado e apagado (ambiente), em vez de voltar ao mostrador */
    val ambiente: Boolean = true,
    /** quadros por segundo do orbe parado (sem sessão e sem dedo) e do orbe em sessão */
    val quadrosParado: Int = 20,
    val quadrosAtivo: Int = 30,
    /** os sensores dos gestos entregam em lote: menos despertares, um pouco mais de atraso no gesto */
    val sensoresLote: Boolean = false,
    /** sem sessão por [PONTE_PARADA_MS], a ponte e o Wi-Fi saem; tocar o orbe reconecta */
    val ponteParada: Boolean = false,
    /** a espera pela resposta com o orbe fora da tela não segura a CPU acordada */
    val esperaLeve: Boolean = false,
    /** por onde a ponte sai: [REDES] */
    val rede: String = "auto",
) {
    /** A ação de [n] toques curtos (1 a 4). */
    fun toque(n: Int): AcaoToque = toques.getOrNull(n - 1) ?: AcaoToque.NADA

    /** A ação de [n] toques com o último segurado (1 a 4). */
    fun segura(n: Int): AcaoToque = segurar.getOrNull(n - 1) ?: AcaoToque.NADA

    /**
     * O maior número de toques que faz algo, curtos ou com o último segurado:
     * chegou nele, não precisa esperar outro.
     */
    fun maisToques(): Int =
        (maxOf(toques.size, segurar.size) downTo 1).firstOrNull { toque(it) != AcaoToque.NADA || segura(it) != AcaoToque.NADA } ?: 0

    /** A instância em que o orbe da [skin] ficou. */
    fun instanciaDe(skin: Skin): Int = instancias[skin.id] ?: 0

    /** A escala do orbe da [skin]. */
    fun tamanhoDe(skin: Skin): Float = tamanhos[skin.id] ?: tamanho

    /** O glitch do orbe da [skin]. */
    fun glitchDe(skin: Skin): Boolean = glitches[skin.id] ?: glitch

    /** As skins na ordem da lista: as de [ordem] e, depois delas, as que faltarem, na ordem de sempre. */
    fun skins(): List<Skin> {
        val escolhidas = ordem.mapNotNull { id -> Skin.entries.firstOrNull { it.id == id } }.distinct()
        return escolhidas + Skin.entries.filter { it !in escolhidas }
    }

    /** O que vai e volta com o PC (a [Sincronia]). */
    fun sincronia() = Sincronia(
        t, agentes, voz, vozPc, microfone, vibrar, texto, glitch, linhas, tamanho, seguirPc, tamanhos,
        toques = toques.map { it.id }, segurar = segurar.map { it.id }, live = live, fundo = fundo, ordem = skins().map { it.id },
        sacudida = sacudida, sair = sair, sacudidaFora = sacudidaFora, sacudidaDentro = sacudidaDentro, sairFora = sairFora,
        etapas = etapas, idiomaEtapas = idiomaEtapas, glitches = glitches,
    )

    fun com(s: Sincronia) = copy(
        t = s.t, agentes = s.agentes, voz = s.voz, vozPc = s.vozPc, microfone = s.microfone, vibrar = s.vibrar,
        texto = s.texto, glitch = s.glitch, linhas = s.linhas, tamanho = s.tamanho.coerceIn(TAMANHO_MIN, TAMANHO_MAX), seguirPc = s.seguirPc,
        // o PC manda null no orbe que ainda segue o tamanho comum
        tamanhos = s.tamanhos.mapNotNull { (k, v) -> v?.let { k to it.coerceIn(TAMANHO_MIN, TAMANHO_MAX) } }.toMap(),
        // o PC manda null no orbe que segue o glitch comum
        glitches = s.glitches.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
        // o que o PC ainda não conhece (null) fica como está aqui
        toques = s.toques?.let { l -> lista(l, toques, AcaoToque.CURTAS) } ?: toques,
        segurar = s.segurar?.let { l -> lista(l, segurar, AcaoToque.entries) } ?: segurar,
        live = s.live ?: live,
        fundo = s.fundo ?: fundo,
        ordem = s.ordem ?: ordem,
        sacudida = s.sacudida ?: sacudida,
        sair = s.sair ?: sair,
        sacudidaFora = s.sacudidaFora?.let { if (it > 0f) it else Sacudida.FORA_MIN } ?: sacudidaFora,
        sacudidaDentro = s.sacudidaDentro?.let { if (it > 0f) it else Sacudida.DENTRO_MIN } ?: sacudidaDentro,
        sairFora = s.sairFora?.let { if (it > 0f) it else 0f } ?: sairFora,
        etapas = s.etapas,
        idiomaEtapas = s.idiomaEtapas.takeIf { it in IDIOMAS_ETAPAS } ?: idiomaEtapas,
    )

    companion object {
        /** As ações que vêm do PC: a que não se reconhece (ou não cabe ali) fica como estava. */
        private fun lista(l: List<String>, antes: List<AcaoToque>, validas: List<AcaoToque>) =
            List(AcaoToque.PADRAO.size) { i -> AcaoToque.de(l.getOrNull(i))?.takeIf { it in validas } ?: antes.getOrNull(i) ?: AcaoToque.NADA }

        /** as opções de [telaTempo], em segundos (0 = o tempo do sistema) */
        val TEMPOS_TELA = listOf(0, 30, 60, 120, 300)
        val QUADROS_PARADO = listOf(10, 15, 20, 30)
        val QUADROS_ATIVO = listOf(20, 30)
        /**
         * "auto": a LAN pelo Wi-Fi do relógio, o resto (a tailnet, um endereço público)
         * pela rede que o sistema escolher, que com o celular por perto é o Bluetooth
         * dele; "wifi": sempre pelo Wi-Fi; "celular": nunca prende o Wi-Fi
         */
        val REDES = listOf("auto", "wifi", "celular")
        /** quanto o orbe fica parado, sem sessão, até a ponte sair ([ponteParada]) */
        const val PONTE_PARADA_MS = 120_000L

        const val TAMANHO_MIN = 0.6f
        const val TAMANHO_MAX = 1.3f
        /** "pt": traduzidas para o português; "original": como o agente escreve */
        val IDIOMAS_ETAPAS = listOf("pt", "original")
    }
}

private val Context.guardado: DataStore<Preferences> by preferencesDataStore("ajustes")

/**
 * As chaves grandes (o olá do PC com o papel de parede, dezenas de KB, e as
 * calibrações das batidas) numa loja à parte: o DataStore reescreve o arquivo
 * inteiro a cada gravação, e cada página do carrossel gravava tudo isso junto.
 */
private val Context.guardadoGrande: DataStore<Preferences> by preferencesDataStore("ajustes_grandes")

class Cofre(private val ctx: Context) {
    private object K {
        val servidor = stringPreferencesKey("servidor")
        val token = stringPreferencesKey("token")
        val seguirPc = booleanPreferencesKey("seguir_pc")
        val skin = stringPreferencesKey("skin")
        val instancia = intPreferencesKey("instancia")
        val instancias = stringPreferencesKey("instancias")  // "skin=1;skin=0"
        val fundo = booleanPreferencesKey("fundo")
        val fundoMenu = booleanPreferencesKey("fundo_menu")
        val glitch = booleanPreferencesKey("glitch")
        val linhas = booleanPreferencesKey("linhas")
        val tamanho = floatPreferencesKey("tamanho")
        val tamanhos = stringPreferencesKey("tamanhos")    // "skin=1.0;skin=0.8"
        val glitches = stringPreferencesKey("glitches")    // "skin=1;skin=0"
        val texto = booleanPreferencesKey("texto")
        val microfone = booleanPreferencesKey("microfone")
        val voz = booleanPreferencesKey("voz")
        val vozPc = booleanPreferencesKey("voz_pc")
        val vibrar = booleanPreferencesKey("vibrar")
        val etapas = booleanPreferencesKey("etapas")
        val idiomaEtapas = stringPreferencesKey("idioma_etapas")
        val pediuMicrofone = booleanPreferencesKey("pediu_microfone")
        val pc = stringPreferencesKey("pc")
        val agentes = stringPreferencesKey("agentes")      // "skin=agente;skin=agente"
        val t = longPreferencesKey("t")
        val sacudida = booleanPreferencesKey("sacudida")
        val live = booleanPreferencesKey("live")
        val toques = stringPreferencesKey("toques")        // "abrir,live,encerrar,nada"
        val segurar = stringPreferencesKey("segurar")      // "falar,historico,nada,nada"
        val historico = booleanPreferencesKey("historico")  // a chave antiga dos quatro toques
        val sacudidaFora = floatPreferencesKey("sacudida_fora")
        val sacudidaDentro = floatPreferencesKey("sacudida_dentro")
        val ordem = stringPreferencesKey("ordem")          // "anel,serafim_gravura,..."
        val sair = booleanPreferencesKey("sair")
        val sairFora = floatPreferencesKey("sair_fora")
        val batidas = booleanPreferencesKey("batidas")
        val batidaFracas = stringPreferencesKey("batida_fracas")
        val batidaFortes = stringPreferencesKey("batida_fortes")
        val batidaNada = stringPreferencesKey("batida_nada")
        val batidasAcoes = stringPreferencesKey("batidas_acoes")
        val batidasIntervalo = longPreferencesKey("batidas_intervalo")
        val telaAcesa = booleanPreferencesKey("tela_acesa")
        val telaTempo = intPreferencesKey("tela_tempo")
        val ambiente = booleanPreferencesKey("ambiente")
        val quadrosParado = intPreferencesKey("quadros_parado")
        val quadrosAtivo = intPreferencesKey("quadros_ativo")
        val sensoresLote = booleanPreferencesKey("sensores_lote")
        val ponteParada = booleanPreferencesKey("ponte_parada")
        val esperaLeve = booleanPreferencesKey("espera_leve")
        val rede = stringPreferencesKey("rede")
    }

    private fun lerAgentes(s: String?): Map<String, String> =
        s.orEmpty().split(';').mapNotNull { par ->
            par.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] }
        }.toMap()

    /** Sem a lista guardada, o padrão; com a chave antiga ligada, quatro toques abrem o histórico. */
    private fun lerToques(s: String?, historico: Boolean): List<AcaoToque> {
        if (s == null) return if (historico) AcaoToque.PADRAO.dropLast(1) + AcaoToque.HISTORICO else AcaoToque.PADRAO
        return lerAcoes(s, AcaoToque.PADRAO)
    }

    private fun lerAcoes(s: String, padrao: List<AcaoToque>): List<AcaoToque> {
        val l = s.split(',')
        return List(padrao.size) { i -> AcaoToque.de(l.getOrNull(i)) ?: padrao[i] }
    }

    private fun lerInstancias(s: String?): Map<String, Int> =
        s.orEmpty().split(';').mapNotNull { par ->
            val p = par.split('=', limit = 2)
            val k = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            p[0].takeIf { it.isNotEmpty() }?.let { it to k.coerceAtLeast(0) }
        }.toMap()

    private fun lerTamanhos(s: String?): Map<String, Float> =
        s.orEmpty().split(';').mapNotNull { par ->
            val p = par.split('=', limit = 2)
            val v = p.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            p[0].takeIf { it.isNotEmpty() }?.let { it to v.coerceIn(Ajustes.TAMANHO_MIN, Ajustes.TAMANHO_MAX) }
        }.toMap()

    // o que a loja grande tem por último, para gravar só quando muda
    private var grandesGravados: List<String>? = null

    /** Uma chave grande: da loja grande, ou da pequena (onde morava antes da separação). */
    private fun grande(g: Preferences, p: Preferences, k: Preferences.Key<String>): String? = g[k] ?: p[k]

    val fluxo: Flow<Ajustes> = combine(ctx.guardado.data, ctx.guardadoGrande.data) { p, g ->
        val d = Ajustes()
        Ajustes(
            servidor = p[K.servidor] ?: d.servidor,
            token = p[K.token] ?: d.token,
            seguirPc = p[K.seguirPc] ?: d.seguirPc,
            skin = p[K.skin] ?: d.skin,
            instancia = p[K.instancia] ?: d.instancia,
            instancias = lerInstancias(p[K.instancias]),
            fundo = p[K.fundo] ?: d.fundo,
            fundoMenu = p[K.fundoMenu] ?: d.fundoMenu,
            glitch = p[K.glitch] ?: d.glitch,
            linhas = p[K.linhas] ?: d.linhas,
            tamanho = (p[K.tamanho] ?: d.tamanho).coerceIn(Ajustes.TAMANHO_MIN, Ajustes.TAMANHO_MAX),
            tamanhos = lerTamanhos(p[K.tamanhos]),
            glitches = p[K.glitches].orEmpty().split(';').mapNotNull { par ->
                val q = par.split('=', limit = 2)
                q[0].takeIf { it.isNotEmpty() && q.size == 2 }?.let { it to (q[1] == "1") }
            }.toMap(),
            texto = p[K.texto] ?: d.texto,
            microfone = p[K.microfone] ?: d.microfone,
            voz = p[K.voz] ?: d.voz,
            vozPc = p[K.vozPc] ?: d.vozPc,
            vibrar = p[K.vibrar] ?: d.vibrar,
            etapas = p[K.etapas] ?: d.etapas,
            idiomaEtapas = p[K.idiomaEtapas]?.takeIf { it in Ajustes.IDIOMAS_ETAPAS } ?: d.idiomaEtapas,
            pediuMicrofone = p[K.pediuMicrofone] ?: d.pediuMicrofone,
            pc = grande(g, p, K.pc) ?: d.pc,
            agentes = lerAgentes(p[K.agentes]),
            t = p[K.t] ?: d.t,
            sacudida = p[K.sacudida] ?: d.sacudida,
            live = p[K.live] ?: d.live,
            toques = lerToques(p[K.toques], p[K.historico] == true),
            segurar = p[K.segurar]?.let { lerAcoes(it, AcaoToque.PADRAO_SEGURAR) } ?: d.segurar,
            sacudidaFora = p[K.sacudidaFora] ?: d.sacudidaFora,
            sacudidaDentro = p[K.sacudidaDentro] ?: d.sacudidaDentro,
            ordem = p[K.ordem].orEmpty().split(',').filter { it.isNotEmpty() },
            sair = p[K.sair] ?: d.sair,
            sairFora = p[K.sairFora] ?: d.sairFora,
            batidas = p[K.batidas] ?: d.batidas,
            batidaFracas = grande(g, p, K.batidaFracas) ?: d.batidaFracas,
            batidaFortes = grande(g, p, K.batidaFortes) ?: d.batidaFortes,
            batidaNada = grande(g, p, K.batidaNada) ?: d.batidaNada,
            batidasAcoes = p[K.batidasAcoes]?.split(',')?.mapNotNull { AcaoToque.de(it) }?.takeIf { it.size == 8 } ?: d.batidasAcoes,
            batidasIntervalo = p[K.batidasIntervalo] ?: d.batidasIntervalo,
            telaAcesa = p[K.telaAcesa] ?: d.telaAcesa,
            telaTempo = p[K.telaTempo]?.takeIf { it in Ajustes.TEMPOS_TELA } ?: d.telaTempo,
            ambiente = p[K.ambiente] ?: d.ambiente,
            quadrosParado = p[K.quadrosParado]?.takeIf { it in Ajustes.QUADROS_PARADO } ?: d.quadrosParado,
            quadrosAtivo = p[K.quadrosAtivo]?.takeIf { it in Ajustes.QUADROS_ATIVO } ?: d.quadrosAtivo,
            sensoresLote = p[K.sensoresLote] ?: d.sensoresLote,
            ponteParada = p[K.ponteParada] ?: d.ponteParada,
            esperaLeve = p[K.esperaLeve] ?: d.esperaLeve,
            rede = p[K.rede]?.takeIf { it in Ajustes.REDES } ?: d.rede,
        )
    }

    suspend fun gravar(a: Ajustes) {
        val grandes = listOf(a.pc, a.batidaFracas, a.batidaFortes, a.batidaNada)
        if (grandes != grandesGravados) {
            ctx.guardadoGrande.edit { g ->
                g[K.pc] = a.pc
                g[K.batidaFracas] = a.batidaFracas
                g[K.batidaFortes] = a.batidaFortes
                g[K.batidaNada] = a.batidaNada
            }
            grandesGravados = grandes
        }
        ctx.guardado.edit { p ->
            p[K.servidor] = a.servidor
            p[K.token] = a.token
            p[K.seguirPc] = a.seguirPc
            p[K.skin] = a.skin
            p[K.instancia] = a.instancia
            p[K.instancias] = a.instancias.entries.joinToString(";") { "${it.key}=${it.value}" }
            p[K.fundo] = a.fundo
            p[K.fundoMenu] = a.fundoMenu
            p[K.glitch] = a.glitch
            p[K.linhas] = a.linhas
            p[K.tamanho] = a.tamanho
            p[K.tamanhos] = a.tamanhos.entries.joinToString(";") { "${it.key}=${it.value}" }
            p[K.glitches] = a.glitches.entries.joinToString(";") { "${it.key}=${if (it.value) 1 else 0}" }
            p[K.texto] = a.texto
            p[K.microfone] = a.microfone
            p[K.voz] = a.voz
            p[K.vozPc] = a.vozPc
            p[K.vibrar] = a.vibrar
            p[K.etapas] = a.etapas
            p[K.idiomaEtapas] = a.idiomaEtapas
            p[K.pediuMicrofone] = a.pediuMicrofone
            p.remove(K.pc)
            p[K.agentes] = a.agentes.entries.joinToString(";") { "${it.key}=${it.value}" }
            p[K.t] = a.t
            p[K.sacudida] = a.sacudida
            p[K.live] = a.live
            p[K.toques] = a.toques.joinToString(",") { it.id }
            p[K.segurar] = a.segurar.joinToString(",") { it.id }
            p.remove(K.historico)
            p[K.sacudidaFora] = a.sacudidaFora
            p[K.batidas] = a.batidas
            p.remove(K.batidaFracas)
            p.remove(K.batidaFortes)
            p.remove(K.batidaNada)
            p[K.batidasAcoes] = a.batidasAcoes.joinToString(",") { it.id }
            p[K.batidasIntervalo] = a.batidasIntervalo
            p[K.sacudidaDentro] = a.sacudidaDentro
            p[K.ordem] = a.ordem.joinToString(",")
            p[K.sair] = a.sair
            p[K.sairFora] = a.sairFora
            p[K.telaAcesa] = a.telaAcesa
            p[K.telaTempo] = a.telaTempo
            p[K.ambiente] = a.ambiente
            p[K.quadrosParado] = a.quadrosParado
            p[K.quadrosAtivo] = a.quadrosAtivo
            p[K.sensoresLote] = a.sensoresLote
            p[K.ponteParada] = a.ponteParada
            p[K.esperaLeve] = a.esperaLeve
            p[K.rede] = a.rede
        }
    }
}
