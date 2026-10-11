package io.orbe.watch.gl

import io.orbe.watch.orbe.Uniformes
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Até onde o segundo passe (pos.frag) pode pintar, pelos uniforms do quadro:
 * fora disso ele devolve zero, que é o que o glClear já deixou. O scissor nesse
 * retângulo tira os pixels vazios sem mudar nenhum: a figura com o texto embaixo
 * ocupa uma fração da tela, e o passe rodava as quatro leituras da máscara em
 * todos os 454 x 454.
 *
 * Os alcances, do pos.frag:
 *  - a máscara mora na célula da figura ([recorte]; o primeiro passe é recortado a ela);
 *  - a aberração copia a máscara deslocada de |glt.x| na horizontal (no anel, |glt.x| * k);
 *  - as faixas da rajada, de até 36 * k na horizontal (nas linhas da própria figura);
 *    as bandas do anel, de |b.z| * k * modo.y;
 *  - os cacos da rajada, num círculo de raio min(lim, 1,3 R) em volta do centro, com
 *    até 28 * max(0,5, k) para a direita e 2 px para baixo;
 *  - a sombra, num círculo de raio sombra.x em volta do centro (desligada no relógio).
 */
internal object RecortePos {
    /** folga para o filtro linear da máscara (que pode estar a 37,5% da resolução) e o antisserrilhado */
    private const val FOLGA = 6.0

    /**
     * O retângulo (x0, y0, x1, y1) em px lógicos, com y para baixo como no pos.frag,
     * ou null se ele cobre o item inteiro (não vale o scissor).
     */
    fun calcular(pos: Uniformes, recorte: DoubleArray, w: Double, h: Double): DoubleArray? {
        val modo = pos["modo"] ?: return null
        val glt = pos["glt"] ?: return null
        val centro = pos["centro"] ?: return null
        val k = glt[3].toDouble()
        var x0 = recorte[0] - recorte[2]
        var x1 = recorte[0] + recorte[2]
        var y0 = recorte[1] - recorte[3]
        var y1 = recorte[1] + recorte[3]
        var lado = 0.0                       // deslocamentos horizontais da máscara
        if (modo[0] < 0.5f) {
            // avatares
            val corA = pos["corA"]
            if (corA != null && corA[3] > 0f) lado = max(lado, abs(glt[0].toDouble()))
            if (glt[1] > 0.5f) {
                lado = max(lado, 36.0 * k)
                val geo2 = pos["geo2"] ?: return null
                val rc = min(geo2[1].toDouble(), 1.3 * geo2[0].toDouble())
                val cx = centro[0].toDouble()
                val cy = centro[1].toDouble()
                x0 = min(x0, cx - rc - 1)
                x1 = max(x1, cx + rc + 28.0 * max(0.5, k) + 1)
                y0 = min(y0, cy - rc - 1)
                y1 = max(y1, cy + rc + 3)
            }
        } else {
            // o anel
            if (glt[0] != 0f && k > 0) lado = max(lado, abs(glt[0] * k))
            if (k > 0) for (b in 0 until 4) {
                val bd = pos["banda$b"] ?: continue
                if (bd[3] >= 0.5f) lado = max(lado, abs(bd[2] * k * modo[1]))
            }
        }
        x0 -= lado
        x1 += lado
        val sombra = pos["sombra"]
        if (sombra != null && sombra[3] > 0.5f && sombra[0] > 1f) {
            val r = sombra[0].toDouble()
            x0 = min(x0, centro[0] - r)
            x1 = max(x1, centro[0] + r)
            y0 = min(y0, centro[1] - r)
            y1 = max(y1, centro[1] + r)
        }
        x0 = max(0.0, x0 - FOLGA)
        y0 = max(0.0, y0 - FOLGA)
        x1 = min(w, x1 + FOLGA)
        y1 = min(h, y1 + FOLGA)
        if (x0 <= 0.0 && y0 <= 0.0 && x1 >= w && y1 >= h) return null
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /**
     * O retângulo em px do alvo, como o glScissor pede (x, y de baixo, largura,
     * altura): na janela ([invertido]) a linha 0 é a de baixo da tela; num FBO de
     * prévia, a de cima (o y lógico cresce com a linha).
     */
    fun emPx(r: DoubleArray, w: Int, h: Int, wl: Double, hl: Double, invertido: Boolean): IntArray? {
        val sx = w / wl
        val sy = h / hl
        val px0 = floor(r[0] * sx).toInt().coerceIn(0, w)
        val px1 = ceil(r[2] * sx).toInt().coerceIn(0, w)
        val py0: Int
        val py1: Int
        if (invertido) {
            py0 = (h - ceil(r[3] * sy).toInt()).coerceIn(0, h)
            py1 = (h - floor(r[1] * sy).toInt()).coerceIn(0, h)
        } else {
            py0 = floor(r[1] * sy).toInt().coerceIn(0, h)
            py1 = ceil(r[3] * sy).toInt().coerceIn(0, h)
        }
        if (px1 <= px0 || py1 <= py0) return null
        return intArrayOf(px0, py0, px1 - px0, py1 - py0)
    }
}
