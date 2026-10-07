package io.orbe.watch

import io.orbe.watch.gl.RecortePos
import io.orbe.watch.orbe.Uniformes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecortePosTest {
    private fun avatar(sep: Double, rajada: Boolean, k: Double, rb: Double = 60.0, lim: Double = 90.0) = Uniformes().apply {
        v4("modo", 0.0, 1.0, 0.0, 0.0)
        v2("centro", 113.5, 80.0)
        v4("corA", 0.0, 0.95, 0.95, 0.4)
        v4("glt", sep, if (rajada) 1.0 else 0.0, 3.0, k)
        v4("geo2", rb, lim, 0.0, 1.0)
        zero4("sombra")
    }

    // a célula com o texto embaixo: o quadrado de 140 px em volta de (113,5; 80)
    private val celula = doubleArrayOf(113.5, 80.0, 70.0, 70.0)

    @Test
    fun semGlitchORecorteEACelulaComFolga() {
        val r = RecortePos.calcular(avatar(sep = 0.0, rajada = false, k = 0.0), celula, 227.0, 227.0)!!
        assertArrayEquals(doubleArrayOf(37.5, 4.0, 189.5, 156.0), r, 1e-9)
    }

    @Test
    fun aAberracaoEAsFaixasAlarganNaHorizontal() {
        val r = RecortePos.calcular(avatar(sep = 3.0, rajada = false, k = 1.0), celula, 227.0, 227.0)!!
        assertTrue(r[0] <= 113.5 - 70 - 3 && r[2] >= 113.5 + 70 + 3)
        val raj = RecortePos.calcular(avatar(sep = 3.0, rajada = true, k = 1.0), celula, 227.0, 227.0)!!
        // com a rajada as faixas andam 36 px: o recorte vai de uma borda à outra do item,
        // e o pé (o texto) continua de fora
        assertEquals(0.0, raj[0], 1e-9)
        assertEquals(227.0, raj[2], 1e-9)
        assertTrue(raj[3] < 227.0)
    }

    @Test
    fun aSombraQueCobreTudoDispensaORecorte() {
        val u = avatar(sep = 0.0, rajada = false, k = 0.0).apply { v4("sombra", 400.0, 0.5, 0.0, 1.0) }
        assertNull(RecortePos.calcular(u, celula, 227.0, 227.0))
    }

    @Test
    fun osCacosFicamDentroDoCirculoDoCentro() {
        // rajada fraca (k pequeno): os cacos, não as faixas, decidem a altura
        val r = RecortePos.calcular(avatar(sep = 0.0, rajada = true, k = 0.1, rb = 40.0, lim = 200.0), celula, 227.0, 227.0)!!
        val rc = minOf(200.0, 1.3 * 40.0)
        assertTrue(r[3] >= 80.0 + rc + 3)
        assertTrue(r[2] >= 113.5 + rc + 28 * 0.5 + 1)
    }

    @Test
    fun aJanelaTemALinhaZeroEmBaixo() {
        val r = doubleArrayOf(10.0, 20.0, 30.0, 50.0)
        // 2 px por px lógico, alvo de 454 x 454
        assertArrayEquals(intArrayOf(20, 454 - 100, 40, 60), RecortePos.emPx(r, 454, 454, 227.0, 227.0, invertido = true))
        assertArrayEquals(intArrayOf(20, 40, 40, 60), RecortePos.emPx(r, 454, 454, 227.0, 227.0, invertido = false))
    }
}
