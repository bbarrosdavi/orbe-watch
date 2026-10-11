package io.orbe.watch.dados

import android.util.Log
import org.json.JSONObject

/**
 * Registro dos gestos para depuração sem o adb: cada evento vai para o
 * logcat e, pela ponte, para o arquivo de gestos do PC. Sem ponte, espera
 * numa fila curta e sai quando ela conectar.
 */
object Diagnostico {
    private const val MAX = 500
    private val fila = ArrayDeque<String>()
    private var envio: ((String) -> Boolean)? = null

    fun registrar(tag: String, texto: String) {
        Log.i(tag, texto)
        val linha = "diag " + JSONObject().put("t", System.currentTimeMillis()).put("tag", tag).put("msg", texto)
        synchronized(this) {
            if (fila.isEmpty() && envio?.invoke(linha) == true) return
            fila.addLast(linha)
            while (fila.size > MAX) fila.removeFirst()
        }
    }

    /** a ponte conectou ([f] não nulo) ou caiu */
    fun ligar(f: ((String) -> Boolean)?) = synchronized(this) {
        envio = f
        if (f == null) return@synchronized
        while (fila.isNotEmpty() && f(fila.first())) fila.removeFirst()
    }
}
