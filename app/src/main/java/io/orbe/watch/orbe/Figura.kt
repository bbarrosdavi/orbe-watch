package io.orbe.watch.orbe

import io.orbe.watch.orbe.Estado.IDLE
import io.orbe.watch.orbe.Estado.LISTENING
import io.orbe.watch.orbe.Estado.SPEAKING
import io.orbe.watch.orbe.Estado.THINKING
import io.orbe.watch.orbe.Estado.TOOLS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ophanim, Ophanim com asas e as skins de imagem: o lado com memória
 * do orbe-qt/comum/Figura.qml, portado linha a linha. Giro das rodas, travas em
 * quarto de volta, ondas da voz, relâmpagos, batida das asas e rajadas de
 * glitch andam aqui; o desenho é do figura.frag e do imagem.frag, e a cor e o
 * glitch são do pos.frag, os mesmos arquivos do desktop.
 *
 * As reações por estado estão descritas no Figura.qml. Mexeu lá, mexe aqui.
 */
class Figura(skinInicial: Skin) : Arte {
    override var skin: Skin = skinInicial
        set(v) {
            if (field != v) {
                field = v
                st = Memoria()
            }
        }
    var glitch = true
    /** linhas de varredura (o tubo de TV); nas skins de imagem não entram, riscam a hachura */
    var varredura = true
    var peso = 1.0                       // traço mais grosso e opaco (o orbe usa mais)
    val cor = floatArrayOf(1f, 1f, 1f)
    /** tom do fundo: a massa escura por baixo do traço (skins de imagem) */
    val corFundo = floatArrayOf(0.07f, 0.078f, 0.078f)
    var raioFixo = -1.0                  // R explícito; -1 = o maior que cabe
    var disco = -1.0                     // > 0: cabe também num disco desse raio
    var zoom = 1.0
    var alfa = 1.0
    var olharX = Double.NaN              // para onde os olhos olham; NaN = vagam
    var olharY = Double.NaN
    val mix = DoubleArray(5).also { it[IDLE] = 1.0 }
    var voz = 0.0
    var mic = 0.0
    var desperto = 1.0

    override val fx = Uniformes()
    override val pos = Uniformes()
    override val recorte = DoubleArray(4)

    private val rRef = 82.0              // raio em que o glitch foi afinado
    private var st = Memoria()

    private class Memoria {
        var t = 0.0
        var fase = 0.0
        var vozAnt = 0.0
        var glitchAte = 0.0
        var proxGlitch = 1.2
        val salto = DoubleArray(4)
        val semente = DoubleArray(4) { uni(0.0, 100.0) }
        var foco = 0.3
        var trava = 0.0
        var quarto = 0.0
        var quartoAlvo = 0.0
        var proxQuarto = 0.0
        val ondas = ArrayList<DoubleArray>()        // (nascimento, força)
        var ultimaOnda = -1.0
        val relampagos = ArrayList<DoubleArray>()   // (nascimento, aro, ângulo, aro, ângulo, semente)
        var giroRaios = 0.0
        var pupila = 1.0
        var clarao = 0.0
        var faseAsa = 0.0
        var abreAsa = 0.2
        var ampAsa = 0.0
        // olho e humana: as molas das peças (criadas na primeira vez) e a pupila que encara
        var pc: Partes? = null
        var encarar = 0.0
    }

    /**
     * As molas das peças da Humana e do Olho (imagem.frag): uma cadeia por
     * membro ou raio, com [juntas] ângulos cada, e os corpos da Humana.
     */
    private class Partes(val cadeias: Int, val juntas: Int, val corpos: Int) {
        val ang = Array(cadeias) { DoubleArray(3) }
        val vel = Array(cadeias) { DoubleArray(3) }
        val fase = DoubleArray(cadeias) { uni(0.0, TAU) }
        val sinal = DoubleArray(cadeias) { if (Random.nextBoolean()) 1.0 else -1.0 }
        val sem = DoubleArray(cadeias) { uni(0.0, 100.0) }
        val est = DoubleArray(cadeias)
        val vest = DoubleArray(cadeias)
        val corpo = DoubleArray(corpos)
        val vcorpo = DoubleArray(corpos)
        val fcorpo = DoubleArray(corpos) { uni(0.0, TAU) }
        // o Olho: os raios fluindo presos ao globo
        var fluxo = 0.0
        var amp = 0.0
        var vamp = 0.0
        var brilho = 0.0
    }

    private fun partes(): Partes {
        st.pc?.let { return it }
        val p = if (skin == Skin.HUMANA) Partes(27, 3, 8) else Partes(0, 0, 0)
        st.pc = p
        return p
    }

    fun raioQueCabe(w: Double, h: Double, d: Double): Double {
        val al = skin.alcance
        val r = min(w / 2 / al[0], h / 2 / al[1])
        return if (d > 0) min(r, d / al[2]) else r
    }

    private fun p(e: Int) = mix[e]

    private fun mistura(idle: Double, listening: Double, thinking: Double, tools: Double, speaking: Double): Double {
        var tot = 0.0
        var acc = 0.0
        if (mix[IDLE] > 0) { acc += mix[IDLE] * idle; tot += mix[IDLE] }
        if (mix[LISTENING] > 0) { acc += mix[LISTENING] * listening; tot += mix[LISTENING] }
        if (mix[THINKING] > 0) { acc += mix[THINKING] * thinking; tot += mix[THINKING] }
        if (mix[TOOLS] > 0) { acc += mix[TOOLS] * tools; tot += mix[TOOLS] }
        if (mix[SPEAKING] > 0) { acc += mix[SPEAKING] * speaking; tot += mix[SPEAKING] }
        return if (tot <= 0) idle else acc / tot
    }

    private fun agitacao() = lim01(p(THINKING) + 0.6 * p(TOOLS))

    override fun avancar(dt: Double) {
        val s = st
        s.t += dt
        // ondas da voz, uma por sílaba, em qualquer skin
        val subida = voz - s.vozAnt
        if (p(SPEAKING) > 0.3 && s.t - s.ultimaOnda > 0.12
            && (subida > 0.05 || (voz > 0.3 && s.t - s.ultimaOnda > 0.3))
        ) {
            s.ondas.add(doubleArrayOf(s.t, min(1.0, 0.35 + voz)))
            s.ultimaOnda = s.t
        }
        s.ondas.removeAll { s.t - it[0] >= 1.3 }
        when (skin) {
            Skin.SERAFIM_GRAVURA, Skin.SERAFIM_POSITIVO -> evoluirGravura(dt)
            Skin.OLHO, Skin.HUMANA -> evoluirPartes(dt)
            else -> {
                evoluirOfanim(dt)
                if (skin == Skin.OFANIM_ALADO) {
                    val falar = p(SPEAKING) * voz
                    s.faseAsa += dt * TAU * (mistura(0.3, 0.3, 2.6, 6.0, 1.6) + 1.2 * falar)
                }
            }
        }
        s.vozAnt = voz
    }

    private fun evoluirOfanim(dt: Double) {
        val s = st
        val t = s.t
        val ouvir = p(LISTENING)
        val pensar = p(THINKING)
        val ferr = p(TOOLS)
        val falar = p(SPEAKING) * voz
        var giro = mistura(1.0, 0.45, 3.4, 1.4, 1.1) + 1.8 * falar
        giro += (1 - desperto) * 5.0          // desdobrando: as rodas giram soltas
        s.fase += dt * giro
        s.giroRaios += dt * (0.07 + 0.5 * pensar + 0.25 * falar)
        val foco = mistura(0.3, 1.0, 0.0, 0.55, 0.85)
        s.foco += (foco - s.foco) * min(1.0, dt * 5)
        s.trava += (ferr - s.trava) * min(1.0, dt * 4)
        if (ferr > 0.4 && t >= s.proxQuarto) {
            s.quartoAlvo += doubleArrayOf(1.0, 1.0, -1.0)[sorteia(3)] * Math.PI / 2
            s.proxQuarto = t + uni(0.45, 0.9)
        }
        s.quarto += (s.quartoAlvo - s.quarto) * min(1.0, dt * 9)

        if (Random.nextDouble() < dt * (2.4 * ferr + 0.5 * pensar))
            s.relampagos.add(doubleArrayOf(t, sorteia(4).toDouble(), uni(0.0, TAU), sorteia(4).toDouble(), uni(0.0, TAU), Random.nextDouble() * 100))
        s.relampagos.removeAll { t - it[0] >= 0.12 }
        s.clarao = if (s.relampagos.isNotEmpty()) 1.0 else s.clarao * max(0.0, 1 - dt * 8)

        val alvoP = 1.0 + 0.25 * ouvir + 0.6 * ouvir * mic - 0.25 * pensar
        s.pupila += (alvoP - s.pupila) * min(1.0, dt * 8)
    }

    private fun evoluirGravura(dt: Double) {
        val s = st
        val falar = p(SPEAKING) * voz
        // a pose desenhada é a de ouvir; parado, as asas se recolhem
        val ab = mistura(0.55, 1.0, 0.85, 0.9, 0.95)
        s.abreAsa += (ab - s.abreAsa) * min(1.0, dt * 3)
        // o ciclo das articulações (as do meio abrem enquanto as de cima fecham
        // e as de baixo abrem; depois o contrário): amplitude e ritmo por estado,
        // como no Figura.qml
        val amp = mistura(0.45, 0.7, 1.0, 0.8, 0.75) + 0.25 * falar
        s.ampAsa += (amp - s.ampAsa) * min(1.0, dt * 3)
        s.faseAsa += dt * TAU * (mistura(0.10, 0.18, 0.35, 0.6, 0.28) + 0.25 * falar)
    }

    /**
     * A física das peças, como no Figura.qml: cada junta é uma mola, e a de fora
     * recebe o contrário da velocidade da de dentro (o chicote dos tentáculos).
     */
    private fun evoluirPartes(dt: Double) {
        val s = st
        val t = s.t
        val pc = partes()
        val ouvir = p(LISTENING)
        val ferr = p(TOOLS)
        val falar = p(SPEAKING) * voz
        val dG = suave(desperto)
        val humana = skin == Skin.HUMANA
        // a onda nascida neste quadro (uma por sílaba) chuta as bases
        val chute = if (s.ultimaOnda == s.t && s.ondas.isNotEmpty()) s.ondas.last()[1] else 0.0
        val amp = if (humana) mistura(0.16, 0.06, 0.30, 0.12, 0.18) else mistura(0.13, 0.05, 0.26, 0.10, 0.17)
        val fr = if (humana) mistura(0.32, 0.20, 0.26, 1.8, 0.7) else mistura(0.55, 0.40, 0.80, 2.4, 1.2)
        val ganho = if (humana) GANHO_MEMBRO else GANHO_RAIO
        val k = if (humana) K_MEMBRO else K_RAIO
        val c = if (humana) C_MEMBRO else C_RAIO
        val lim = if (humana) LIM_MEMBRO else LIM_RAIO
        val nj = pc.juntas
        // ao despertar, as peças vêm encolhidas e se abrem
        val enrola = (1 - dG) * 0.9
        // espasmo das ferramentas: uma junta qualquer leva um tranco
        if (ferr > 0.05 && Random.nextDouble() < ferr * dt * 4) pc.vel[sorteia(pc.cadeias)][sorteia(nj)] += uni(-4.0, 4.0)
        if (chute > 0) {
            for (i in 0 until pc.cadeias) {
                if (Random.nextDouble() < 0.7) pc.vel[i][0] += pc.sinal[i] * chute * uni(0.6, 1.6) * (if (humana) 1.2 else 1.8)
                if (!humana) pc.vest[i] += chute * uni(0.4, 1.2)
            }
        }
        val n = maxOf(1, kotlin.math.ceil(dt / 0.012).toInt())
        val h = dt / n
        repeat(n) {
            for (i in 0 until pc.cadeias) {
                val a = pc.ang[i]
                val v = pc.vel[i]
                val sg = pc.sinal[i]
                for (j in 0 until nj) {
                    val onda = sin(TAU * fr * t + pc.fase[i] - 1.1 * j)
                    val alvo = sg * (amp * ganho[j] * onda - enrola * ganho[j]) + amp * 0.35 * ruido(t * fr * 1.7, pc.sem[i] + j)
                    // o chicote: a junta de fora atrasa em relação à de dentro
                    val acc = k[j] * (alvo - a[j]) - c[j] * v[j] - (if (j > 0) 4.0 * v[j - 1] else 0.0)
                    v[j] += acc * h
                    a[j] = (a[j] + v[j] * h).coerceIn(-lim[j], lim[j])
                }
                if (!humana) {
                    val ae = mistura(0.0, 0.06, -0.03, 0.0, 0.04) - 0.55 * (1 - dG)
                    pc.vest[i] += (40 * (ae - pc.est[i]) - 6 * pc.vest[i]) * h
                    pc.est[i] = (pc.est[i] + pc.vest[i] * h).coerceIn(-0.6, 0.5)
                }
            }
            if (humana) {
                val ab = mistura(0.04, 0.02, 0.07, 0.03, 0.05)
                for (b in 0 until pc.corpos) {
                    val alvoB = ab * sin(TAU * 0.22 * t + pc.fcorpo[b]) + ab * 0.4 * ruido(t * 0.5, pc.fcorpo[b] * 7)
                    pc.vcorpo[b] += (14 * (alvoB - pc.corpo[b]) - 3.2 * pc.vcorpo[b]) * h
                    pc.corpo[b] = (pc.corpo[b] + pc.vcorpo[b] * h).coerceIn(-0.12, 0.12)
                }
            }
        }
        if (humana && chute > 0) for (b in 0 until pc.corpos) pc.vcorpo[b] += (if (Random.nextBoolean()) -1 else 1) * chute * 0.35
        if (!humana) {
            // os raios fluem presos ao olho: a ondulação corre do olho para fora,
            // com a amplitude numa mola que cada sílaba chuta
            val alvoA = mistura(2.0, 1.5, 3.5, 2.5, 3.0) + 3 * falar - 2 * (1 - dG)
            if (chute > 0) pc.vamp += chute * 12
            pc.vamp += (30 * (alvoA - pc.amp) - 7 * pc.vamp) * dt
            pc.amp = max(0.0, pc.amp + pc.vamp * dt)
            pc.fluxo += dt * (mistura(1.4, 1.0, 2.6, 3.6, 2.0) + 1.5 * falar)
            pc.brilho = mistura(0.25, 0.15, 0.4, 0.3, 0.45) + 0.5 * falar
        }
        // a pupila abre para ouvir e fecha para pensar; encarar a leva ao meio
        val dil = mistura(1.0, 1.15, 0.82, 0.78, 1.04) + 0.12 * ouvir * mic
        s.pupila += (dil - s.pupila) * min(1.0, dt * 5)
        s.encarar += (ouvir - s.encarar) * min(1.0, dt * 4)
    }

    // base (u, v) do plano do anel i, girando em eixos diferentes
    private val eixos = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.577, 0.577, 0.577),
    )

    private fun base(i: Int, u: DoubleArray, v: DoubleArray) {
        val s = st
        val t = s.t
        val sm = s.semente[i]
        val a = s.fase * (0.35 + 0.17 * i) * (if (i % 2 != 0) 1 else -1) + ruido(t * 0.4, sm) * 0.6 + s.salto[i]
        val b = 0.9 + i * 0.55 + ruido(t * 0.25, sm + 7) * 0.5
        var n0 = cos(a) * sin(b)
        var n1 = sin(a) * sin(b)
        var n2 = cos(b)
        if (s.trava > 0.01) {
            // travadas nos eixos, girando em quartos de volta (Ez 1:17)
            var e0 = eixos[i][0]
            var e1 = eixos[i][1]
            var e2 = eixos[i][2]
            var c = cos(s.quarto)
            var sn = sin(s.quarto)
            var x = e0 * c - e1 * sn
            var y = e0 * sn + e1 * c
            e0 = x; e1 = y
            c = cos(0.55); sn = sin(0.55)                 // inclinação para ler em 3D
            y = e1 * c - e2 * sn
            var z = e1 * sn + e2 * c
            e1 = y; e2 = z
            c = cos(0.45); sn = sin(0.45)
            x = e0 * c + e2 * sn
            z = -e0 * sn + e2 * c
            e0 = x; e2 = z
            val w = s.trava
            n0 = (1 - w) * n0 + w * e0
            n1 = (1 - w) * n1 + w * e1
            n2 = (1 - w) * n2 + w * e2
            val nl = norma(n0, n1, n2)
            n0 /= nl; n1 /= nl; n2 /= nl
        }
        val r0: Double
        val r1: Double
        val r2: Double
        if (abs(n2) < 0.9) { r0 = 0.0; r1 = 0.0; r2 = 1.0 } else { r0 = 1.0; r1 = 0.0; r2 = 0.0 }
        var u0 = n1 * r2 - n2 * r1
        var u1 = n2 * r0 - n0 * r2
        var u2 = n0 * r1 - n1 * r0
        val nu = norma(u0, u1, u2)
        u0 /= nu; u1 /= nu; u2 /= nu
        u[0] = u0; u[1] = u1; u[2] = u2
        v[0] = n1 * u2 - n2 * u1
        v[1] = n2 * u0 - n0 * u2
        v[2] = n0 * u1 - n1 * u0
    }

    private fun norma(a: Double, b: Double, c: Double): Double {
        val n = Math.sqrt(a * a + b * b + c * c)
        return if (n == 0.0) 1.0 else n
    }

    private fun ponto(u: DoubleArray, v: DoubleArray, r: Double, th: Double, cx: Double, cy: Double, sai: DoubleArray) {
        val c = cos(th)
        val s = sin(th)
        val z = c * u[2] + s * v[2]
        val k = r * (1 + z / 5)
        sai[0] = cx + (c * u[0] + s * v[0]) * k
        sai[1] = cy + (c * u[1] + s * v[1]) * k
    }

    private val us = Array(4) { DoubleArray(3) }
    private val vs = Array(4) { DoubleArray(3) }
    private val rs = DoubleArray(4)
    private val p0 = DoubleArray(2)
    private val p1 = DoubleArray(2)
    private val on = DoubleArray(16)

    // os nomes dos uniforms das asas e dos aros, montados uma vez: o quadro não aloca string
    private val nomesAsaA = Array(4) { "w${it}a" }
    private val nomesAsaB = Array(4) { "w${it}b" }
    private val nomesAroU = Array(4) { "a${it}u" }
    private val nomesAroV = Array(4) { "a${it}v" }

    private fun asaVec(slot: Int, rx: Double, ry: Double, ang: Double, comp: Double, lado: Double, abert: Double, olhos: Double) {
        fx.v4(nomesAsaA[slot], rx, ry, ang, comp)
        fx.v4(nomesAsaB[slot], lado, abert, olhos, 0.0)
    }

    // monta os uniforms do quadro
    override fun montar(w: Double, h: Double, cx: Double, cy: Double, cw: Double, ch: Double) {
        if (w < 10 || h < 10) return
        recorte[0] = cx; recorte[1] = cy; recorte[2] = cw / 2; recorte[3] = ch / 2
        val s = st
        val t = s.t
        val rb = (if (raioFixo > 0) raioFixo else raioQueCabe(cw, ch, disco)) * zoom
        val lim = min(cw, ch) / 2 - 1
        val k = rb / rRef
        val ouvir = p(LISTENING)
        val pensar = p(THINKING)
        val ferr = p(TOOLS)
        val falar = p(SPEAKING) * voz

        // rajadas de glitch; pensando, na cadência do anel: curtas e em sequência
        var rajada = false
        var sep = 0.0
        if (glitch) {
            if (t >= s.proxGlitch) {
                val ag = agitacao()
                s.glitchAte = t + (if (ag > 0.25) uni(0.05, 0.20) else uni(0.08, 0.28))
                s.proxGlitch = s.glitchAte + (if (ag > 0.25) uni(0.06, 0.75) / (0.4 + ag) else uni(0.9, 3.6))
                if (Random.nextDouble() < 0.5) {
                    s.salto[sorteia(4)] += (if (Random.nextDouble() < 0.5) -1 else 1) * uni(0.4, 1.4)
                }
            }
            rajada = t < s.glitchAte
            sep = (if (rajada) uni(4.0, 11.0) else 0.8 + 0.5 * abs(ruido(t, 1.0))) * max(0.6, k)
        }

        val temOlhar = !olharX.isNaN()
        var gx: Double
        var gy: Double
        val r: Double
        when (skin) {
            Skin.OLHO, Skin.HUMANA -> {
                val dP = suave(desperto)
                r = rb * (0.3 + 0.7 * dP)
                gx = if (temOlhar) olharX else cx + ruido(t * 0.6, 3.0) * r * 1.4
                gy = if (temOlhar) olharY else cy + ruido(t * 0.5, 9.0) * r * 0.8
                if (pensar > 0.05) {
                    // pensando, a pupila vasculha para cima
                    val vx = cx + ruido(t * 1.7, 21.0) * r * 2
                    val vy = cy - r * (0.6 + 0.6 * abs(ruido(t * 1.1, 4.0)))
                    gx = gx * (1 - pensar) + vx * pensar
                    gy = gy * (1 - pensar) + vy * pensar
                }
                val pc = partes()
                if (skin == Skin.OLHO) {
                    fx.v4("img", pc.amp, pc.fluxo, 0.025 * falar + 0.006 * sin(t * 1.1) * dP, 1.0)
                    fx.v4("img2", pc.brilho, s.encarar, 0.0, 0.0)
                } else {
                    fx.v4("img", 0.0, 0.0, 0.025 * falar + 0.006 * sin(t * 1.1) * dP, 1.0)
                    fx.zero4("img2")
                }
                // os ângulos (Mu) e, já feitos aqui uma vez por quadro, os senos e
                // cossenos que o imagem.frag do relógio usa (Mc, Md): sin/cos por pixel
                // custavam um terço do quadro do Rei dos Ratos na Adreno 504
                // os três vetores vão ao programa numa chamada cada (eram 144 por quadro)
                val mu = fx.vetor("Mu", 48)
                val mc = fx.vetor("Mc", 48)
                val md = fx.vetor("Md", 48)
                for (m in 0 until 48) {
                    when {
                        m < pc.cadeias -> pc.ang[m].let { a ->
                            val terceiro = if (pc.juntas > 2) a[2] else pc.est[m]
                            v4em(mu, m, a[0], a[1], terceiro, 0.0)
                            val a0 = a[0]
                            val a1 = a0 + a[1]
                            val a2 = a1 + terceiro
                            v4em(mc, m, cos(a0), sin(a0), cos(a1), sin(a1))
                            v4em(md, m, cos(a2), sin(a2), 0.0, 0.0)
                        }
                        m < pc.cadeias + pc.corpos -> {
                            val c = pc.corpo[m - pc.cadeias]
                            v4em(mu, m, c, 0.0, 0.0, 0.0)
                            v4em(mc, m, cos(c), sin(c), 0.0, 0.0)
                            v4em(md, m, 0.0, 0.0, 0.0, 0.0)
                        }
                        else -> {
                            v4em(mu, m, 0.0, 0.0, 0.0, 0.0)
                            v4em(mc, m, 0.0, 0.0, 0.0, 0.0)
                            v4em(md, m, 0.0, 0.0, 0.0, 0.0)
                        }
                    }
                }
            }
            Skin.SERAFIM_GRAVURA, Skin.SERAFIM_POSITIVO -> {
                val dG = suave(desperto)
                r = rb * (0.3 + 0.7 * dG)
                gx = if (temOlhar) olharX else cx + ruido(t * 0.6, 3.0) * r * 1.4
                gy = if (temOlhar) olharY else cy + ruido(t * 0.5, 9.0) * r * 0.8
                if (pensar > 0.05) {
                    // pensando, o olho do meio vasculha para cima
                    val vx = cx + ruido(t * 1.7, 21.0) * r * 2
                    val vy = cy - r * (0.6 + 0.6 * abs(ruido(t * 1.1, 4.0)))
                    gx = gx * (1 - pensar) + vx * pensar
                    gy = gy * (1 - pensar) + vy * pensar
                }
                // f: 0 com as do meio recolhidas, 1 abertas; as de cima fecham quando
                // elas abrem, as de baixo fecham quando elas recolhem. dobra: o
                // recolher do estado e do despertar, nas bases (ver imagem.frag)
                val fA = 0.5 * (1 - cos(s.faseAsa))
                val aA = s.ampAsa * dG
                val dobra = 1 - s.abreAsa * dG
                val grau = PI / 180
                fx.v4("img", 5 * grau * (2 * fA - 1) * aA - 0.25 * dobra, 24 * grau * fA * aA, 0.025 * falar, -7 * grau * fA * aA + 0.35 * dobra)
                fx.v4("img2", -16 * grau * fA * aA, -16 * grau * (1 - fA) * aA, -7 * grau * (1 - fA) * aA + 0.35 * dobra, 0.0)
            }
            else -> {
                val d = desperto
                r = rb * (0.25 + 0.75 * suave(d))
                val comp = 1.25 + 0.45 * falar - 0.18 * ouvir + 0.10 * pensar
                fx.v4("raios", 28.0, s.giroRaios, comp, (0.06 + 0.06 * pensar + 0.10 * falar + 0.15 * s.clarao) * suave(d))
                gx = if (temOlhar) olharX else cx + ruido(t * 0.6, 3.0) * r * 1.4
                gy = if (temOlhar) olharY else cy + ruido(t * 0.5, 9.0) * r * 0.8

                if (skin == Skin.OFANIM_ALADO) {
                    // "quando paravam, abaixavam as asas" (Ez 1:24); abertas para
                    // ouvir e batendo no turbilhão; retas e vibrando nas ferramentas
                    val deA = suave(desperto)
                    val sup = mistura(-0.22, 0.32, 0.55, 0.10, 0.40)
                    val inf = mistura(-1.05, -0.80, -0.62, -0.75, -0.72)
                    val ampA = mistura(0.04, 0.02, 0.32, 0.07, 0.10) + 0.30 * falar
                    val abA = mistura(0.25, 0.7, 1.0, 0.9, 0.85) * deA
                    val batA = sin(s.faseAsa)
                    var slot = 0
                    for (ld in LADOS) {
                        asaVec(slot++, cx + ld * 0.52 * r, cy - 0.22 * r, sup + ampA * batA, 0.88 * r * deA, ld, abA, 3.0)
                        asaVec(slot++, cx + ld * 0.46 * r, cy + 0.30 * r, inf - 0.5 * ampA * batA, 0.66 * r * deA, ld, abA * 0.8, 2.0)
                    }
                } else {
                    for (i in 0..3) fx.zero4(nomesAsaA[i])
                }

                // aros
                for (i in 0..3) {
                    base(i, us[i], vs[i])
                    val rr = r * (1 - i * 0.075) * (1 - 0.06 * ouvir * mic) *
                        (1 + 0.09 * falar * sin(t * 9 + i * 1.7)) *
                        (1 + 0.025 * pensar * sin(t * 2.2 + i))
                    rs[i] = rr
                    fx.v4(nomesAroU[i], us[i][0], us[i][1], us[i][2], rr)
                    fx.v4(nomesAroV[i], vs[i][0], vs[i][1], vs[i][2], 0.0)
                }
                // relâmpagos entre os olhos dos aros (Ez 1:14)
                val info = DoubleArray(4)
                for (j in 0..1) {
                    val rl = s.relampagos.getOrNull(j)
                    if (rl == null) {
                        fx.zero4("rel$j")
                        continue
                    }
                    val i0 = rl[1].toInt()
                    val i1 = rl[3].toInt()
                    ponto(us[i0], vs[i0], rs[i0], rl[2], cx, cy, p0)
                    ponto(us[i1], vs[i1], rs[i1], rl[4], cx, cy, p1)
                    fx.v4("rel$j", p0[0], p0[1], p1[0], p1[1])
                    info[2 * j] = 1 - (t - rl[0]) / 0.12
                    info[2 * j + 1] = rl[5]
                }
                fx.v4("relInfo", info[0], info[1], info[2], info[3])

                // olho central: pálpebra pelo estado, vasculha para cima quando pensa
                var abre = mistura(1.0, 1.15, 0.55, 0.7, 1.0)
                abre *= (if ((t % 6.3) > 0.18) 1.0 else 0.1) * lim01((d - 0.3) / 0.3)
                var zx = gx
                var zy = gy
                if (pensar > 0.05) {
                    val vx = cx + ruido(t * 1.7, 21.0) * r * 2
                    val vy = cy - r * (0.6 + 0.6 * abs(ruido(t * 1.1, 4.0)))
                    zx = gx * (1 - pensar) + vx * pensar
                    zy = gy * (1 - pensar) + vy * pensar
                }
                val dx = zx - cx
                val dy = zy - cy
                val dl = hypot(dx, dy).let { if (it == 0.0) 1.0 else it }
                fx.v2("nucleoDir", dx / dl, dy / dl)
                fx.v4("ofa", s.foco, s.pupila, s.clarao, abre)
                fx.v4("ofa2", s.fase, suave(d), if (skin == Skin.OFANIM_ALADO) 4.0 else 0.0, 0.0)
            }
        }

        fx.v2("tam", w, h)
        fx.v2("centro", cx, cy)
        fx.v2("olhar", gx, gy)
        // ondas da voz: "o ruído das suas asas, como o de muitas águas"
        on.fill(0.0)
        var n = 0
        for (o in s.ondas) {
            if (n >= 16) break
            val idade = (t - o[0]) / 1.3
            val ro = r * (0.55 + idade * 1.2)
            if (ro >= lim) continue
            on[n++] = ro
            on[n++] = o[1] * (1 - idade) * (1 - ro / lim) * 0.55
        }
        fx.v4("ondas0", on[0], on[1], on[2], on[3])
        fx.v4("ondas1", on[4], on[5], on[6], on[7])
        fx.v4("ondas2", on[8], on[9], on[10], on[11])
        fx.v4("ondas3", on[12], on[13], on[14], on[15])

        fx.v4("geo", r, lim, t, peso)
        fx.v4("est", ouvir, pensar, ferr, falar)
        fx.v4("est2", voz, mic, desperto, if (skin == Skin.OFANIM_ALADO) 1.0 else 0.0)
        fx.v4("lacos", 30.0, 24.0, 10.0, if (skin == Skin.OFANIM_ALADO) 4.0 else 0.0)

        // segundo passe
        pos.v2("tam", w, h)
        pos.v2("centro", cx, cy)
        pos.v4("cor", cor[0].toDouble(), cor[1].toDouble(), cor[2].toDouble(), alfa)
        // ciano e magenta do anel; na rajada, tão opacos quanto os dele
        val alfaGl = if (!glitch) 0.0 else if (rajada) 0.60 else 0.40
        pos.v4("corA", 0.00, 0.95, 0.95, alfaGl)
        pos.v4("corB", 1.00, 0.08, 0.55, alfaGl)
        pos.v4("glt", sep, if (rajada) 1.0 else 0.0, if (rajada) Random.nextDouble() * 1000 else 0.0, k)
        pos.v4("geo2", rb, lim, (t * 18) % 3, if (varredura && !skin.imagem) 1.0 else 0.0)
        pos.zero4("sombra")
        // nas gravuras recortadas a massa é preta, como no desenho (no tom do fundo
        // do tema ela levantava as sombras e lavava a imagem)
        if (skin.polar) pos.v4("corSombra", 0.0, 0.0, 0.0, 1.0)
        else pos.v4("corSombra", corFundo[0].toDouble(), corFundo[1].toDouble(), corFundo[2].toDouble(), 1.0)
        pos.v4("modo", 0.0, 1.0, 0.0, 0.0)
    }

    private companion object {
        // os nomes dos uniforms das juntas, feitos uma vez (o quadro não aloca)
        // as transformações das peças: no relógio o imagem.frag as lê do vetor de
        // uniforms Mu (indexado direto pela GPU); m0..m47 avulsos são do Qt
        val GANHO_MEMBRO = doubleArrayOf(0.45, 1.0, 1.25)
        val GANHO_RAIO = doubleArrayOf(0.7, 1.25)
        val K_MEMBRO = doubleArrayOf(26.0, 34.0, 42.0)
        val K_RAIO = doubleArrayOf(30.0, 38.0)
        val C_MEMBRO = doubleArrayOf(4.2, 4.8, 5.4)
        val C_RAIO = doubleArrayOf(4.6, 5.2)
        val LIM_MEMBRO = doubleArrayOf(0.32, 0.7, 0.8)
        val LIM_RAIO = doubleArrayOf(0.5, 0.75)
    }
}

/** os dois lados das asas, sem um array novo por quadro */
private val LADOS = doubleArrayOf(-1.0, 1.0)
