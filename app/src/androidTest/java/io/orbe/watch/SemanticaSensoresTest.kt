package io.orbe.watch

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A premissa do SensoresNaFrente: com dois ouvintes no mesmo sensor, um no mais
 * rápido e outro no ritmo de jogo, o Android entrega ao de jogo as amostras do
 * mais rápido (a taxa é só um pedido). Se isto falhar, o sair passaria a ler
 * mais amostras do que lia e o hub precisa decimar para ele.
 */
@RunWith(AndroidJUnit4::class)
class SemanticaSensoresTest {
    @Test
    fun oOuvinteLentoRecebeATaxaDoRapido() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sm = ctx.getSystemService(SensorManager::class.java)
        val giro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val t = HandlerThread("teste-sensores").apply { start() }
        val h = Handler(t.looper)
        fun contador(n: AtomicInteger) = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { n.incrementAndGet() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // sozinho, no ritmo de jogo
        val so = AtomicInteger()
        val lSo = contador(so)
        sm.registerListener(lSo, giro, SensorManager.SENSOR_DELAY_GAME, h)
        Thread.sleep(3000)
        sm.unregisterListener(lSo)
        // junto de um no mais rápido
        val rapido = AtomicInteger()
        val lento = AtomicInteger()
        val lR = contador(rapido)
        val lL = contador(lento)
        sm.registerListener(lR, giro, SensorManager.SENSOR_DELAY_FASTEST, h)
        sm.registerListener(lL, giro, SensorManager.SENSOR_DELAY_GAME, h)
        Thread.sleep(3000)
        sm.unregisterListener(lR)
        sm.unregisterListener(lL)
        t.quitSafely()
        Log.i("OrbeSensores", "sozinho em jogo: ${so.get()} em 3 s; junto: rápido ${rapido.get()}, jogo ${lento.get()}")
        // o de jogo junto do rápido recebe bem mais do que sozinho, perto do que o rápido recebe
        assertTrue("jogo sozinho ${so.get()}, junto ${lento.get()}, rápido ${rapido.get()}",
            lento.get() > so.get() * 1.5 && lento.get() > rapido.get() * 0.8)
    }
}
