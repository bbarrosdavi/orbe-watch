package io.orbe.watch

import io.orbe.watch.gesto.AMOSTRAS
import io.orbe.watch.gesto.ANTES
import io.orbe.watch.gesto.DIMENSAO
import io.orbe.watch.gesto.Forca
import io.orbe.watch.gesto.Janela
import io.orbe.watch.gesto.ModeloBatida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ModeloBatidaTest {
    /** Uma forma com um pulso no instante do pico, no canal [canalAcel] (0-2) e no [canalGiro] (3-5), normalizada como no detector. */
    private fun forma(canalAcel: Int, canalGiro: Int, ruido: Int): FloatArray {
        val f = FloatArray(DIMENSAO)
        f[canalAcel * AMOSTRAS + ANTES] = 1f
        f[canalAcel * AMOSTRAS + ANTES + 1] = 0.5f
        f[canalGiro * AMOSTRAS + ANTES] = 1f
        // uma variação pequena por tentativa, para os exemplos não saírem idênticos
        f[canalAcel * AMOSTRAS + ANTES + 3 + ruido % 4] += 0.2f
        val ra = sqrt((0 until 3 * AMOSTRAS).sumOf { (f[it] * f[it]).toDouble() }).toFloat() * 1.41421f
        val rg = sqrt((3 * AMOSTRAS until DIMENSAO).sumOf { (f[it] * f[it]).toDouble() }).toFloat() * 1.41421f
        for (i in 0 until 3 * AMOSTRAS) f[i] /= ra
        for (i in 3 * AMOSTRAS until DIMENSAO) f[i] /= rg
        return f
    }

    private fun toque(i: Int, a: Float = 1.5f, g: Float = 0.4f) = Janela(forma(0, 3, i), a, g)
    private fun estalo(i: Int, a: Float = 25f, g: Float = 2.5f) = Janela(forma(2, 5, i), a, g)

    private val modelo = ModeloBatida.de(List(6) { toque(it) }, List(6) { estalo(it, a = 24f + it, g = 2.2f) }, emptyList())

    @Test
    fun estaloComForcaDeEstaloEEstalo() {
        assertEquals(Forca.FORTE, modelo.classificar(estalo(9, a = 30f, g = 3f)).first)
    }

    @Test
    fun estaloMaisFracoQueOsDaCalibracaoAindaEEstaloPelaForca() {
        // abaixo da faixa calibrada (24 a 29 / 1,4), mas o dobro do toque mais forte
        val (f, motivo) = modelo.classificar(estalo(9, a = 12f, g = 1.5f))
        assertEquals(motivo, Forca.FORTE, f)
        assertTrue(motivo, "pela força" in motivo)
    }

    @Test
    fun estaloMaisForteQueOsDaCalibracaoNaoTemTeto() {
        assertEquals(Forca.FORTE, modelo.classificar(estalo(9, a = 80f, g = 6f)).first)
    }

    @Test
    fun forcaDeEstaloComFormaDeToqueNuncaViraToqueFraco() {
        val (f, motivo) = modelo.classificar(toque(9, a = 30f, g = 3f))
        assertTrue(motivo, f != Forca.FRACA)
    }

    @Test
    fun forcaDeToqueComFormaDeEstaloNuncaViraEstalo() {
        assertTrue(modelo.classificar(estalo(9, a = 1.5f, g = 0.4f)).first != Forca.FORTE)
    }

    @Test
    fun toqueFracoContinuaReconhecido() {
        assertEquals(Forca.FRACA, modelo.classificar(toque(9)).first)
        assertEquals(Forca.FRACA, modelo.classificar(toque(9), emSequencia = true).first)
    }

    @Test
    fun estaloComOPulsoGiradoAindaEEstalo() {
        // o mesmo estalo noutros eixos (o relógio virado): calibrado em z, chega em x
        val girado = Janela(forma(0, 3, 9), 25f, 2.5f)
        assertEquals(Forca.FORTE, modelo.classificar(girado).first)
    }
}
