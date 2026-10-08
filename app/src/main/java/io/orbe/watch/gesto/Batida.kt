package io.orbe.watch.gesto

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * O dedo na tela e a própria vibração balançam o relógio como uma batida:
 * marcam até quando (ms desde o boot, a base dos sensores) o detector fica surdo.
 */
object TelaTocada {
    /** o dedo na tela: até aqui nada conta, e o comando em curso cai */
    @Volatile var surdoAte = 0L
    /** a vibração do aviso: até aqui nada conta, mas o comando em curso segue (ele é o que vibrou) */
    @Volatile var vibrandoAte = 0L
    /** a última batida reconhecida (ms desde o boot): o estalo gira o pulso e a sacudida de sair não pode valer nela */
    @Volatile var ultimoTrancoMs = 0L
}

/** A força de uma batida: o toque fraco (dedo médio no dedão, ou a ponta dos dedos na mesa) ou o estalo. */
enum class Forca { FRACA, FORTE }

/**
 * Uma sequência de batidas: [vezes] movimentos (1 a 4); com [forca] FORTE o
 * último foi o estalo (os anteriores, toques fracos), com FRACA todos foram toques.
 */
data class Comando(val forca: Forca, val vezes: Int) {
    /** a posição na lista das ações: 0 a 3 os toques, 4 a 7 os que terminam em estalo */
    val indice get() = (if (forca == Forca.FORTE) 4 else 0) + vezes - 1
}

const val AMOSTRAS = 24                     // ~240 ms a 100 Hz: o toque cabe, e a janela fecha cedo
const val ANTES = 6                 // amostras antes do pico na janela
const val DIMENSAO = AMOSTRAS * 6

/**
 * A janela de um gesto: [AMOSTRAS] leituras alinhadas no pico, a aceleração e o
 * giro sem a parte lenta (3 eixos cada). [forma] é isso normalizado (cada
 * sensor com o mesmo peso, comprimento 1): o perfil do movimento; [aceleracao]
 * e [giro] são os picos (a força).
 */
class Janela(val forma: FloatArray, val aceleracao: Float, val giro: Float) {
    fun texto(): String = buildString {
        append("%.3f".format(java.util.Locale.ROOT, aceleracao)).append(' ').append("%.3f".format(java.util.Locale.ROOT, giro))
        for (v in forma) append(' ').append("%.4f".format(java.util.Locale.ROOT, v))
    }

    companion object {
        fun de(t: String): Janela? {
            val p = t.trim().split(' ').mapNotNull { it.toFloatOrNull() }
            if (p.size != 2 + DIMENSAO) return null
            return Janela(p.subList(2, p.size).toFloatArray(), p[0], p[1])
        }

        fun lista(t: String): List<Janela> = t.split('|').mapNotNull { if (it.isBlank()) null else de(it) }
        fun texto(l: List<Janela>): String = l.joinToString("|") { it.texto() }
    }
}

/**
 * A semelhança de duas formas com folga de alinhamento: o maior produto com uma
 * delas deslocada até [FOLGA] amostras (o pico de um toque cai uma ou duas
 * amostras para o lado, e sem a folga a mesma forma parecia outra).
 */
private fun parecidas(a: FloatArray, b: FloatArray): Float {
    var melhor = -1f
    for (d in -FOLGA..FOLGA) {
        var s = 0f
        for (c in 0 until 6) {
            val o = c * AMOSTRAS
            for (t in 0 until AMOSTRAS) {
                val u = t + d
                if (u in 0 until AMOSTRAS) s += a[o + t] * b[o + u]
            }
        }
        if (s > melhor) melhor = s
    }
    return melhor
}

private const val FOLGA = 3

/**
 * A forma sem a orientação do pulso: em cada instante, a intensidade da
 * aceleração e a do giro (a raiz da soma dos três eixos), cada uma com o mesmo
 * peso. O mesmo estalo com o relógio virado para cima, para você ou para fora
 * cai em eixos diferentes, mas tem a mesma intensidade.
 */
internal fun semOrientacao(f: FloatArray): FloatArray {
    val m = FloatArray(2 * AMOSTRAS)
    for (t in 0 until AMOSTRAS) {
        var a = 0f
        var g = 0f
        for (c in 0 until 3) { val v = f[c * AMOSTRAS + t]; a += v * v }
        for (c in 3 until 6) { val v = f[c * AMOSTRAS + t]; g += v * v }
        m[t] = sqrt(a); m[AMOSTRAS + t] = sqrt(g)
    }
    for (o in intArrayOf(0, AMOSTRAS)) {
        var n = 0f
        for (t in 0 until AMOSTRAS) n += m[o + t] * m[o + t]
        val k = sqrt(n).coerceAtLeast(1e-6f) * 1.41421f
        for (t in 0 until AMOSTRAS) m[o + t] /= k
    }
    return m
}

/** [parecidas] para as formas sem orientação (2 canais). */
internal fun parecidasSemOrientacao(a: FloatArray, b: FloatArray): Float {
    var melhor = -1f
    for (d in -FOLGA..FOLGA) {
        var s = 0f
        for (c in 0 until 2) {
            val o = c * AMOSTRAS
            for (t in 0 until AMOSTRAS) {
                val u = t + d
                if (u in 0 until AMOSTRAS) s += a[o + t] * b[o + u]
            }
        }
        if (s > melhor) melhor = s
    }
    return melhor
}

/**
 * O perfil aprendido de uma força: as próprias tentativas como exemplos (a
 * semelhança de um gesto é a do exemplo mais parecido, então cada posição do
 * braço calibrada vale por si, sem virar uma média que não parece com nenhuma),
 * o limiar de semelhança (quanto cada exemplo se parece com o vizinho mais
 * próximo entre os outros, com folga) e a faixa da força (do mais fraco ao mais
 * forte das tentativas, com folga).
 */
class Perfil(
    private val exemplos: List<FloatArray>,
    val limiar: Float,
    private val aceleracao: ClosedFloatingPointRange<Float>,
    private val giro: ClosedFloatingPointRange<Float>,
) {
    fun parecenca(j: Janela) = vizinhos(j.forma, exemplos)

    private val exemplosSemOrientacao by lazy { exemplos.map(::semOrientacao) }

    /** a semelhança sem a orientação do pulso (os dois exemplos mais parecidos, como em [parecenca]) */
    fun parecencaSemOrientacao(j: Janela): Float {
        val f = semOrientacao(j.forma)
        var a = -1f
        var b = -1f
        for (e in exemplosSemOrientacao) {
            val x = parecidasSemOrientacao(f, e)
            if (x > a) { b = a; a = x } else if (x > b) b = x
        }
        return if (b < -0.5f) a else (a + b) / 2
    }

    /** a força da janela na faixa das tentativas, com folga para os lados */
    fun naFaixa(j: Janela) = j.aceleracao in aceleracao && j.giro in giro

    /** a força ([Batida.forca]) mais baixa e a mais alta da faixa das tentativas, com a folga */
    val forcaMin: Float get() = aceleracao.start + giro.start
    val forcaMax: Float get() = aceleracao.endInclusive + giro.endInclusive

    companion object {
        private const val FOLGA_FORCA = 1.4f

        /**
         * A semelhança pelos dois exemplos mais parecidos (a média deles): um
         * exemplo só parecido por acaso não basta, e cada posição tem cinco.
         */
        fun vizinhos(f: FloatArray, exemplos: List<FloatArray>): Float {
            var a = -1f
            var b = -1f
            for (e in exemplos) {
                val s = parecidas(f, e)
                if (s > a) { b = a; a = s } else if (s > b) b = s
            }
            return if (b < -0.5f) a else (a + b) / 2
        }

        fun de(tentativas: List<Janela>): Perfil? {
            if (tentativas.size < 3) return null
            val formas = tentativas.map { it.forma }
            // cada exemplo contra o mais parecido dos outros; o pior (o segundo pior
            // com bastante exemplo, para um só esquisito não afrouxar tudo) dá o limiar
            val proximos = formas.indices.map { a ->
                vizinhos(formas[a], formas.filterIndexed { i, _ -> i != a })
            }.sorted()
            // o décimo percentil (com muitas posições, o mínimo afrouxava demais), com
            // folga e um teto: os estalos da calibração saem quase iguais entre si, e
            // sem teto o limiar ia a 0,90 e recusava estalos de verdade (0,70 a 0,90);
            // o que separa o estalo do resto é a força, que a faixa já cobra
            val base = proximos[proximos.size / 10]
            val limiar = (base - 0.08f).coerceIn(0.5f, 0.7f)
            val a = tentativas.map { it.aceleracao }
            val g = tentativas.map { it.giro }
            return Perfil(
                formas, limiar,
                a.min() / FOLGA_FORCA..a.max() * FOLGA_FORCA,
                g.min() / FOLGA_FORCA..g.max() * FOLGA_FORCA,
            )
        }
    }
}

/**
 * O que o relógio aprendeu dos gestos: o perfil do toque fraco, o do estalo e
 * exemplos do que não é batida (digitar, tocar a tela, mexer o braço).
 */
class ModeloBatida(val fraca: Perfil?, val forte: Perfil?, val nada: List<Janela>, val gatilho: Float) {

    /**
     * A força do gesto, ou null (não é batida); o texto diz por quê. A classe sai
     * da força quando ela só cabe numa; em sequência ([emSequencia]) o toque
     * fraco tem a folga [FOLGA_SEQUENCIA].
     */
    fun classificar(j: Janela, emSequencia: Boolean = false): Pair<Forca?, String> {
        if (fraca == null && forte == null) return null to "sem calibração"
        val forca = Batida.forca(j)
        // a força decide a classe quando só cabe numa: um estalo de 30 m/s² nunca é toque
        // fraco, e um toque nunca é estalo. Antes a classe era a de forma mais parecida, e
        // estalos de força 20 e 30 eram julgados (e recusados) como toque fraco
        val pelaForca = when {
            // na faixa do estalo e acima de qualquer toque, ou o dobro do toque mais forte (o estalo
            // mais fraco que os da calibração, já que você estala em intensidades diferentes)
            forte != null && (fraca == null || forca > fraca.forcaMax) &&
                (forca >= forte.forcaMin || (fraca != null && forca >= fraca.forcaMax * FORTE_SOBRE_FRACA)) -> Forca.FORTE
            fraca != null && fraca.naFaixa(j) && (forte == null || forca < forte.forcaMin) -> Forca.FRACA
            else -> null
        }
        val candidatos = listOfNotNull(
            fraca?.takeIf { pelaForca != Forca.FORTE }?.let { Forca.FRACA to it },
            forte?.takeIf { pelaForca != Forca.FRACA }?.let { Forca.FORTE to it },
        )
        // o estalo se compara sem a orientação do pulso; o toque fraco segue eixo a eixo (calibrado assim)
        val (f, p, s) = candidatos.map { (f, p) ->
            Triple(f, p, if (f == Forca.FORTE) p.parecencaSemOrientacao(j) else p.parecenca(j))
        }.maxBy { it.third }
        val ruido = (if (f == Forca.FORTE) {
            val fj = semOrientacao(j.forma)
            nada.maxOfOrNull { parecidasSemOrientacao(semOrientacao(it.forma), fj) }
        } else {
            nada.maxOfOrNull { parecidas(it.forma, j.forma) }
        } ?: -1f) + MARGEM_NADA
        val limiar = when {
            // o estalo que a força já separou: a forma só confirma (estalos de várias
            // intensidades saem de 0,37 a 0,68 contra os da calibração, e o limiar dela ia ao teto 0,70)
            f == Forca.FORTE && pelaForca == Forca.FORTE -> minOf(p.limiar, LIMIAR_FORTE_PELA_FORCA)
            // o toque fraco volta ao limiar calibrado e à folga do segundo toque em diante (o piso
            // de 0,55 recusava os toques de verdade, que davam 0,50 a 0,54 no relógio)
            f == Forca.FRACA && emSequencia -> (p.limiar - FOLGA_SEQUENCIA).coerceAtLeast(0.4f)
            else -> p.limiar
        }
        return when {
            s < limiar -> null to "forma %.2f abaixo de %.2f (%s)".format(s, limiar, f)
            // o estalo separado pela força não tem teto: mais forte que os da calibração ainda é estalo
            !(f == Forca.FORTE && pelaForca == Forca.FORTE) && !p.naFaixa(j) -> null to "força fora da faixa do %s".format(f)
            ruido >= s -> null to "mais parecido com o que não é batida (%.2f contra %.2f)".format(ruido, s)
            else -> f to "forma %.2f: %s%s%s".format(s, f, if (pelaForca != null) " (pela força)" else "", if (emSequencia) " (em sequência)" else "")
        }
    }

    val calibrado get() = fraca != null || forte != null

    companion object {
        const val GATILHO_PADRAO = 0.6f
        /** o limiar de forma do estalo quando a força já o separou de tudo o mais */
        const val LIMIAR_FORTE_PELA_FORCA = 0.40f
        /** quanto o toque seguinte de uma sequência pode ser menos parecido que o primeiro */
        const val FOLGA_SEQUENCIA = 0.1f
        /** quantas vezes o teto de força do toque fraco já é estalo, mesmo abaixo da faixa calibrada dele */
        const val FORTE_SOBRE_FRACA = 2f
        /** o que não é batida ganha empate com folga: parecido quase igual com os dois, recusa */
        const val MARGEM_NADA = 0.05f

        /**
         * As tentativas sem as que são tremor: força abaixo de um quarto da mediana
         * (a calibração do estalo pegou 0,4 e 0,8 junto de estalos de 22, e o
         * perfil passou a aceitar tremor como estalo).
         */
        fun semTremor(l: List<Janela>): List<Janela> {
            if (l.size < 3) return l
            val mediana = l.map { Batida.forca(it) }.sorted()[l.size / 2]
            return l.filter { Batida.forca(it) >= mediana / 4f }
        }
        fun de(fracasTodas: List<Janela>, fortesTodas: List<Janela>, nada: List<Janela>): ModeloBatida {
            val fracas = semTremor(fracasTodas)
            val fortes = semTremor(fortesTodas)
            val forcas = (fracas + fortes).map { it.aceleracao + it.giro }
            val gatilho = if (forcas.isEmpty()) GATILHO_PADRAO else (forcas.min() * 0.7f).coerceIn(0.3f, 3f)
            return ModeloBatida(Perfil.de(fracas), Perfil.de(fortes), nada, gatilho)
        }
    }
}

/**
 * Batidas no pulso pelo perfil do movimento: um candidato é um pulso no
 * acelerômetro ou no giroscópio (sem a parte lenta: gravidade e gesto do braço)
 * com o pulso quieto antes; a janela dele ([Janela]) vai ao [modelo], que diz se
 * é o toque fraco, o estalo ou nada. Em [coletando] (a calibração), toda janela
 * sai, sem comando e em qualquer postura.
 *
 * Os comandos: toques fracos a menos de [juntarMs] um do outro somam (até
 * [MAX_VEZES]); o estalo fecha a sequência na hora; a pressão do dedo antes do
 * estalo e o rebote depois dele são do estalo; um toque além do máximo cancela
 * (digitar); com o dedo na tela ou a vibração, nada conta. Vale
 * em qualquer posição do braço: o perfil já separa o gesto do resto.
 */
class Batida(
    var modelo: ModeloBatida?,
    private val coletando: Boolean = false,
    private val gatilhoColeta: Float = 0.45f,
    /** o intervalo máximo entre dois toques da mesma sequência: também a espera até o comando sair */
    private val juntarMs: Long = JUNTAR_MS,
) {
    // a parte lenta (filtro de primeira ordem) da aceleração e do giro
    private val lenta = FloatArray(6)
    private var temLenta = false
    private val giroAgora = FloatArray(3)
    private val leitura = FloatArray(6)
    // as últimas leituras sem a parte lenta: 6 canais, num anel
    private val anel = Array(6) { FloatArray(ANEL) }
    private var n = 0L
    private var media = 0f
    // o candidato em andamento
    private var picoEm = -1L
    private var picoE = 0f
    private var picoMs = 0L
    private var surdoAte = 0L
    // os comandos
    private var pendente: Forca? = null
    private var vezes = 0
    private var ultimaMs = 0L
    private var ultimoEstaloMs = 0L
    private val janelas = ArrayList<Janela>()
    private val comandos = ArrayList<Comando>()
    private val registros = ArrayList<String>()

    fun zerar() {
        temLenta = false; n = 0; media = 0f; picoEm = -1; pendente = null; vezes = 0
        janelas.clear(); comandos.clear(); registros.clear()
    }

    /** O giroscópio (rad/s): a última leitura entra com a próxima do acelerômetro. */
    fun giro(x: Float, y: Float, z: Float) {
        giroAgora[0] = x; giroAgora[1] = y; giroAgora[2] = z
    }

    /** Uma leitura do acelerômetro (m/s²) no instante [ms]. */
    fun acel(ms: Long, x: Float, y: Float, z: Float) {
        // a leitura no array de sempre: são 100 por segundo, sem alocar
        val v = leitura
        v[0] = x; v[1] = y; v[2] = z; v[3] = giroAgora[0]; v[4] = giroAgora[1]; v[5] = giroAgora[2]
        if (!temLenta) { v.copyInto(lenta); temLenta = true; return }
        val k = (n % ANEL).toInt()
        var ea = 0f
        var eg = 0f
        for (c in 0 until 6) {
            lenta[c] += (v[c] - lenta[c]) * LENTA
            val r = v[c] - lenta[c]
            anel[c][k] = r
            if (c < 3) ea += r * r else eg += r * r
        }
        val e = sqrt(ea) + sqrt(eg)
        n++
        val gatilho = if (coletando) gatilhoColeta else (modelo?.gatilho ?: ModeloBatida.GATILHO_PADRAO)

        if (ms < TelaTocada.surdoAte) {
            picoEm = -1; pendente = null; vezes = 0
            media += (e - media) * 0.05f
            return
        }
        if (ms < TelaTocada.vibrandoAte) {
            // a média não bebe o tremor do motor: senão a segunda batida, logo depois, é recusada
            if (picoEm >= 0 || e >= gatilho) registros += "pulso %.2f perdido na vibração".format(e)
            picoEm = -1
            if (pendente != null && ms - ultimaMs > juntarMs) soltar()
            return
        }
        if (picoEm < 0) {
            if (e >= gatilho && media < gatilho * QUIETO && ms >= surdoAte) {
                picoEm = n - 1; picoE = e; picoMs = ms
            } else {
                media += (e - media) * 0.05f
            }
        } else {
            if (e > picoE && n - 1 - picoEm <= 6) { picoEm = n - 1; picoE = e; picoMs = ms }
            // um pulso bem maior depois do pico (o candidato era resto da vibração ou
            // tremor): o candidato passa a ser ele, senão a batida de verdade se perde
            else if (e > picoE * 1.5f && e >= gatilho) {
                registros += "candidato trocado: %.2f por %.2f".format(picoE, e)
                picoEm = n - 1; picoE = e; picoMs = ms
            }
            if (n - 1 - picoEm >= AMOSTRAS - ANTES - 1) {
                fecharJanela()
                picoEm = -1
                surdoAte = ms + REFRATARIO_MS
                media = 0f
            }
        }
        // com um candidato aberto (pico já visto, janela ainda enchendo) o comando
        // espera: o candidato pode ser o toque seguinte da mesma sequência
        if (pendente != null && picoEm < 0 && ms - ultimaMs > juntarMs) soltar()
    }

    private fun fecharJanela() {
        val forma = FloatArray(DIMENSAO)
        var aMax = 0f
        var gMax = 0f
        for (t in 0 until AMOSTRAS) {
            val idx = picoEm - ANTES + t
            if (idx < 0 || idx > n - 1 || n - 1 - idx >= ANEL) continue
            val k = (idx % ANEL).toInt()
            var a2 = 0f
            var g2 = 0f
            for (c in 0 until 6) {
                val r = anel[c][k]
                forma[c * AMOSTRAS + t] = r
                if (c < 3) a2 += r * r else g2 += r * r
            }
            aMax = max(aMax, sqrt(a2)); gMax = max(gMax, sqrt(g2))
        }
        // os dois sensores pesam igual na forma (o giro, em rad/s, é menor que a aceleração)
        var na = 0f
        var ng = 0f
        for (i in 0 until 3 * AMOSTRAS) na += forma[i] * forma[i]
        for (i in 3 * AMOSTRAS until DIMENSAO) ng += forma[i] * forma[i]
        na = sqrt(na).coerceAtLeast(1e-6f) * RAIZ2
        ng = sqrt(ng).coerceAtLeast(1e-6f) * RAIZ2
        for (i in 0 until 3 * AMOSTRAS) forma[i] /= na
        for (i in 3 * AMOSTRAS until DIMENSAO) forma[i] /= ng
        val j = Janela(forma, aMax, gMax)
        if (coletando) { janelas += j; return }
        val m = modelo ?: return
        val (f, motivo) = m.classificar(j, emSequencia = pendente != null && picoMs - ultimaMs <= juntarMs)
        registros += "acel %.1f giro %.1f: %s".format(aMax, gMax, motivo)
        if (f != null) { janelas += j; TelaTocada.ultimoTrancoMs = picoMs; contar(picoMs, f) }
    }

    private fun contar(ms: Long, f: Forca) {
        if (f == Forca.FORTE) {
            // o toque logo antes é a pressão do dedo do próprio estalo
            if (vezes > 0 && ms - ultimaMs <= PRE_ESTALO_MS) vezes--
            comandos += Comando(Forca.FORTE, vezes + 1)
            pendente = null; vezes = 0; ultimoEstaloMs = ms
            return
        }
        if (ms - ultimoEstaloMs <= PRE_ESTALO_MS) return // o rebote do estalo
        pendente = Forca.FRACA
        vezes++
        ultimaMs = ms
        if (vezes > MAX_VEZES) {
            registros += "toque além de $MAX_VEZES: comando cancelado (rajada)"
            pendente = null; vezes = 0; surdoAte = ms + SURDO_MS
        }
    }

    private fun soltar() {
        pendente?.let { comandos += Comando(it, vezes) }
        pendente = null
        vezes = 0
    }

    /** As janelas desde a última vez: na coleta, todas; no uso, as que contaram. */
    fun tirarJanelas(): List<Janela> = if (janelas.isEmpty()) emptyList() else janelas.toList().also { janelas.clear() }

    fun tirarComandos(): List<Comando> = if (comandos.isEmpty()) emptyList() else comandos.toList().also { comandos.clear() }

    /** O que se decidiu de cada candidato, para o log. */
    // vazias (quase sempre: são lidas a cada amostra, 100 por segundo), sem criar lista nova
    fun tirarRegistros(): List<String> = if (registros.isEmpty()) emptyList() else registros.toList().also { registros.clear() }

    companion object {
        private const val ANEL = 64
        private const val LENTA = 0.12f          // ~2 Hz a 100 Hz: abaixo disso é o braço e a gravidade
        private const val QUIETO = 0.5f          // a energia média antes do candidato abaixo da metade do gatilho
        private const val REFRATARIO_MS = 40L
        private const val SURDO_MS = 400L
        private const val PRE_ESTALO_MS = 350L
        private const val RAIZ2 = 1.41421f
        const val MAX_VEZES = 4
        const val JUNTAR_MS = 500L               // os toques rápidos do Davi: 300 a 450 ms (pico a pico)
        /** as escolhas do intervalo nos ajustes */
        val INTERVALOS = listOf(300L, 400L, 500L, 600L, 800L, 1000L)

        /** um número só para comparar a força de duas janelas */
        fun forca(j: Janela) = j.aceleracao + j.giro
    }
}
