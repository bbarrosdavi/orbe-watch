package io.orbe.watch.gl

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import io.orbe.watch.orbe.Arte
import io.orbe.watch.orbe.MiniCena
import io.orbe.watch.orbe.Skin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A thread que desenha todos os orbes da tela num contexto GL só: uma
 * superfície por OrbeView, os programas e os atlas compartilhados. As
 * miniaturas do menu saem daqui também, mas fora da tela e uma vez só (as
 * prévias): quadros prontos que o menu troca como imagem.
 *
 * Cada orbe sai em dois passes, como no Qt Quick do desktop: o shader da figura
 * pinta a máscara num FBO (o "layer" do item) e o pos.frag lê a máscara e dá a
 * cor, a aberração e o glitch. No Ophanim o primeiro passe sai por elemento
 * (as primitivas do fim do figura.frag): a figura por pixel não cabe na GPU
 * de um relógio. A animação anda no vsync, a 30 quadros por segundo (20 com
 * o orbe à espera), e para quando não há orbe à vista.
 *
 * Diagnóstico num relógio de verdade: adb shell setprop log.tag.Orbe VERBOSE
 * mostra, a cada 2 s, os quadros por segundo, os atrasados e o custo do
 * primeiro passe.
 */
internal object Motor {
    // 30 quadros por segundo (um vsync sim, um não, num painel de 60 Hz) e 20 com o
    // orbe à espera (um em três), pelos ajustes: o intervalo fica 10% abaixo do múltiplo
    // do vsync, para um vsync que chega um pouco antes não perder a vez (30 e 45 ms)
    @Volatile private var intervaloAtivo = intervalo(30)
    @Volatile private var intervaloCalmo = intervalo(20)
    private fun intervalo(qps: Int) = (1e9 / qps * 0.9).toLong()

    /** Quadros por segundo do orbe parado e do orbe em sessão (ou com o dedo). */
    fun ritmo(parado: Int, ativo: Int) {
        intervaloCalmo = intervalo(parado.coerceIn(5, 60))
        intervaloAtivo = intervalo(ativo.coerceIn(5, 60))
    }
    /** quanto antes da hora do quadro a thread acorda para pedir o vsync dele */
    private const val ADIANTAR_MS = 12L
    private const val ESPERA_GPU_NS = 250_000_000L         // o teto da espera pela GPU antes de entregar o quadro
    // Miniaturas desenhadas por quadro, em rodízio. Medido no TicWatch Pro 5: com
    // as cinco do menu no mesmo quadro, a GPU ficava ocupada de uma vez só e a
    // rolagem da tela perdia quadros (90% deles acima de 150 ms).
    private const val MINIATURAS_POR_QUADRO = 2
    private const val EGL_OPENGL_ES3_BIT = 0x40
    private const val AQUECIMENTO = 10    // passos da prévia antes do primeiro quadro

    private var handler: Handler? = null
    @SuppressLint("StaticFieldLeak")                         // guarda o contexto do app, não o de uma tela
    private var oficina: Oficina? = null
    private var tela: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var contexto: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var reserva: EGLSurface = EGL14.EGL_NO_SURFACE   // 1x1, para ter o contexto sem janela
    private var quadrado = 0              // o VAO do quadrado dos dois passes
    private var primitivas: Primitivas? = null
    private val alvos = ArrayList<Alvo>()
    private val previas = ArrayDeque<Previa>()
    private val principal = Handler(Looper.getMainLooper())
    private var rodando = false
    private val aoVsync = Choreographer.FrameCallback { quadro(it) }

    /** O app saiu da frente (onStop): nada desenha até [retomar]. */
    @Volatile private var pausado = false
    /** Ambiente: o orbe fica no último quadro; só [umQuadro] desenha outro. */
    @Volatile private var congelado = false
    @Volatile private var umaVez = false
    private var ultimoNs = 0L
    private var vez = 0                   // de quem é a vez no rodízio das miniaturas

    /** O FBO do primeiro passe (o "layer" do item no Qt). */
    private class Mascara {
        var fbo = 0
        var textura = 0
        var estencil = 0             // os aros das primitivas: um pixel pinta uma vez por aro
        var fw = 0
        var fh = 0

        fun soltar() {
            if (fbo == 0) return
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(textura), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(estencil), 0)
            fbo = 0
        }
    }

    private class Alvo(val view: OrbeView, val st: SurfaceTexture, var w: Int, var h: Int) {
        var egl: EGLSurface = EGL14.EGL_NO_SURFACE
        var desenhadoEm = 0L         // cada orbe anda pelo tempo desde o próprio último quadro
        val m = Mascara()
    }

    /** Uma miniatura do menu virando [n] quadros, [passo] segundos um do outro. */
    private class Previa(
        val pedido: Pedido, val cena: MiniCena, val w: Int, val h: Int, val densidade: Float,
        val n: Int, val passo: Double, val pronto: (List<Bitmap>) -> Unit,
    ) {
        val m = Mascara()
        var fbo = 0
        var cor = 0
        var buf: ByteBuffer? = null
        val quadros = ArrayList<Bitmap>(n)

        fun soltar() {
            m.soltar()
            if (fbo == 0) return
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(cor), 0)
            fbo = 0
        }
    }

    /** O que o menu guarda de uma prévia pedida, para desistir dela. */
    class Pedido {
        @Volatile var cancelado = false
            private set

        fun cancelar() {
            cancelado = true
        }
    }

    /** Qualidade da máscara travada (0 = o Motor ajusta sozinho); para medir num relógio de verdade. */
    @Volatile var qualidadeFixa = 0f

    // ── a resolução da máscara do orbe grande, pelo que a GPU do relógio dá conta ──
    //
    // O critério são os quadros que perdem a vez, não o custo do primeiro passe:
    // a GPU baixa a frequência quando sobra tempo, e o custo medido engana.
    // Medido no TicWatch Pro 5 (Adreno 702, 466 px): o anel de energia roda a
    // 100% (25 a 30 quadros por segundo); o Ophanim leva 150 ms por quadro a
    // 100% e 25 a 40 ms a 50%, que é onde ele fica. Cada skin tem o seu shader,
    // então cada uma tem a sua qualidade, guardada entre uma abertura e outra.
    private const val JANELA_NS = 2_000_000_000L
    private const val QUALIDADE_MIN = 0.375f
    // a máscara do orbe grande em pouco mais da metade do quadro ativo (30 ms): o
    // resto é dos outros passes e da composição. Medido no TicWatch Pro 3 (Adreno
    // 504): a 100%, 85 ms; a faixa de 10 a 60% de quadros atrasados não descia mais
    private const val ORCAMENTO_MS = 16f
    private var qualidade = 1f            // fração da resolução do primeiro passe
    private var teto = 1f                 // subir além disto já deu atraso nesta execução
    private var skinGrande: Skin? = null  // a skin do orbe grande neste quadro
    private var skinMedida: Skin? = null  // de qual skin é a qualidade em uso
    private var guardado: SharedPreferences? = null
    private var janelaDesde = 0L
    private var grandeDesde = 0L          // quando o orbe grande apareceu (0 = fora da tela)
    // os primeiros segundos do orbe grande (o shader carregando, a abertura
    // crescendo a figura) atrasam quadros que não são de falta de GPU: não contam
    private const val AQUECIMENTO_NS = 3_000_000_000L
    private var naJanela = 0
    private var atrasados = 0
    private var ruins = 0                 // janelas seguidas com a maioria dos quadros atrasada
    private var boas = 0
    private var subiuEm = 0L
    private var somaMascaraMs = 0f        // diagnóstico: custo do primeiro passe do orbe grande
    private var somaLacoMs = 0f
    private var desenhados = 0

    @Synchronized
    private fun fio(ctx: Context): Handler {
        handler?.let { return it }
        val t = HandlerThread("orbe-gl").apply { start() }
        val h = Handler(t.looper)
        val app = ctx.applicationContext
        h.post { abrir(app) }
        handler = h
        return h
    }

    fun ligar(view: OrbeView, st: SurfaceTexture, w: Int, h: Int) {
        fio(view.context).post {
            if (contexto == EGL14.EGL_NO_CONTEXT) return@post
            val a = Alvo(view, st, w, h)
            a.egl = EGL14.eglCreateWindowSurface(tela, config, st, intArrayOf(EGL14.EGL_NONE), 0)
            if (a.egl == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "superfície: erro EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
                return@post
            }
            alvos.add(a)
            acordarAqui()
        }
    }

    /**
     * Desenha [cena] fora da tela em [n] quadros de [w] x [h] px, [passo]
     * segundos um do outro, e entrega os Bitmaps em [pronto], na thread
     * principal. Um quadro por vsync em que nenhum orbe desenha: a prévia não
     * tira a vez do orbe grande.
     */
    fun previa(ctx: Context, cena: MiniCena, w: Int, h: Int, densidade: Float, n: Int, passo: Double, pronto: (List<Bitmap>) -> Unit): Pedido {
        val pedido = Pedido()
        fio(ctx).post {
            if (contexto == EGL14.EGL_NO_CONTEXT || pedido.cancelado) return@post
            previas.addLast(Previa(pedido, cena, max(1, w), max(1, h), densidade, n, passo, pronto))
            acordarAqui()
        }
        return pedido
    }

    private fun acordarAqui() {
        if (rodando) return
        rodando = true
        ultimoNs = 0L
        Choreographer.getInstance().postFrameCallback(aoVsync)
    }

    /** Há o que desenhar de novo (um orbe voltou à vista, deixou de estar parado): de qualquer thread. */
    fun acordar() {
        handler?.post { acordarAqui() }
    }

    fun pausar() {
        pausado = true
    }

    fun retomar() {
        pausado = false
        acordar()
    }

    /** Ambiente: o próximo quadro sai e a animação para nele; fora dele, tudo volta a andar. */
    fun congelar(sim: Boolean) {
        congelado = sim
        umaVez = sim
        acordar()
    }

    /** No ambiente, mais um quadro (o tique de cada minuto, o deslocamento contra marcar a tela). */
    fun umQuadro() {
        umaVez = true
        handler?.post {
            alvos.forEach { it.desenhadoEm = 0L }
            acordarAqui()
        }
    }

    // ── memória: o que sai com o app fora da frente, e os atlas decodificados fora da orbe-gl ──

    /**
     * Solta da GPU o que o orbe em tela não usa: os atlas e os programas das
     * skins por onde a lista passou ([tudo] = false, 10 s depois de o app sair
     * da frente), ou tudo, máscaras inclusive ([tudo] = true, o sistema pedindo
     * memória). O contexto fica; o que voltar a ser pedido é refeito.
     */
    fun liberar(tudo: Boolean) {
        handler?.post {
            val of = oficina ?: return@post
            if (contexto == EGL14.EGL_NO_CONTEXT) return@post
            EGL14.eglMakeCurrent(tela, reserva, reserva, contexto)
            val (nt, np) = of.soltar(if (tudo) Long.MAX_VALUE else System.nanoTime() - USO_RECENTE_NS)
            if (tudo) alvos.forEach {
                it.m.soltar()
                it.desenhadoEm = 0L       // a máscara é refeita no próximo quadro dele
            }
            synchronized(decodificadas) { decodificadas.clear() }
            Log.i(TAG, "memória: soltos $nt atlas e $np programas${if (tudo) " e as máscaras" else ""}")
        }
    }

    private const val USO_RECENTE_NS = 2_000_000_000L
    private val decodificador = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "orbe-arte").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    /** atlas decodificados (ou a caminho), na ordem do pedido: no máximo [DECODIFICADAS] esperando */
    private val decodificadas = LinkedHashMap<String, java.util.concurrent.Future<Bitmap?>>()
    private const val DECODIFICADAS = 2
    /** os atlas já na GPU (escritos na orbe-gl, lidos na principal) */
    private val carregados = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Decodifica o PNG do atlas da [skin] numa thread de baixa prioridade, para
     * a orbe-gl só subir a textura quando a página da skin entrar: sem isso a
     * decodificação (uns 150 ms no relógio) parava o quadro em que ela aparecia.
     */
    fun prepararArte(ctx: Context, skin: Skin) {
        val nome = skin.arte ?: return
        if (nome in carregados) return
        val app = ctx.applicationContext
        synchronized(decodificadas) {
            if (decodificadas.containsKey(nome)) return
            decodificadas[nome] = decodificador.submit<Bitmap?> {
                try {
                    Oficina.decodificar(app, skin)
                } catch (e: Exception) {
                    Log.w(TAG, "atlas $nome: ${e.message}")
                    null
                }
            }
            // a lista rolada depressa pede várias: só as últimas ficam esperando na memória
            while (decodificadas.size > DECODIFICADAS) {
                val velho = decodificadas.keys.first()
                // já decodificando: o bitmap sai na própria fila, sem a principal esperar por ele
                decodificadas.remove(velho)?.let { f ->
                    if (!f.cancel(false)) decodificador.execute { runCatching { f.get() }.getOrNull()?.recycle() }
                }
            }
        }
    }

    /** O atlas já decodificado (esperando o resto, se está a caminho); null se não foi pedido. */
    internal fun arteDecodificada(nome: String): Bitmap? {
        val f = synchronized(decodificadas) { decodificadas.remove(nome) } ?: return null
        return runCatching { f.get() }.getOrNull()
    }

    internal fun atlasCarregado(nome: String) {
        carregados += nome
    }

    internal fun atlasSolto(nome: String) {
        carregados -= nome
    }

    /** Algum orbe à vista ainda anda (ou nunca foi desenhado), ou há prévia na fila. */
    private fun precisa(): Boolean {
        if (previas.isNotEmpty()) return true
        for (a in alvos) {
            if (a.view.aVista && (!a.view.parado || a.desenhadoEm == 0L) && a.w > 0 && a.h > 0) return true
        }
        return false
    }

    /**
     * O próximo quadro: no vsync seguinte, ou, se ainda falta mais de um vsync
     * para a hora dele (o orbe calmo, a 20 por segundo), a thread dorme até
     * perto dela em vez de acordar em cada vsync do meio.
     */
    private fun agendar(agoraNs: Long, intervalo: Long) {
        val faltaMs = (ultimoNs + intervalo - agoraNs) / 1_000_000
        if (previas.isEmpty() && faltaMs > ADIANTAR_MS + 8) {
            Choreographer.getInstance().postFrameCallbackDelayed(aoVsync, faltaMs - ADIANTAR_MS)
        } else {
            Choreographer.getInstance().postFrameCallback(aoVsync)
        }
    }

    fun redimensionar(view: OrbeView, w: Int, h: Int) {
        handler?.post {
            alvos.firstOrNull { it.view === view }?.let { it.w = w; it.h = h; it.desenhadoEm = 0L }
        }
    }

    /** A superfície vai embora; a SurfaceTexture é solta aqui, depois da superfície EGL. */
    fun desligar(view: OrbeView, st: SurfaceTexture) {
        val h = handler
        if (h == null) {
            st.release()
            return
        }
        h.post {
            val a = alvos.firstOrNull { it.view === view && it.st === st }
            if (a != null) {
                alvos.remove(a)
                EGL14.eglMakeCurrent(tela, reserva, reserva, contexto)
                a.m.soltar()
                EGL14.eglDestroySurface(tela, a.egl)
            }
            st.release()
        }
    }

    private fun abrir(app: Context) {
        tela = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versao = IntArray(2)
        if (!EGL14.eglInitialize(tela, versao, 0, versao, 1)) {
            Log.e(TAG, "EGL não iniciou")
            return
        }
        val pedido = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(tela, pedido, 0, configs, 0, 1, n, 0) || n[0] == 0) {
            Log.e(TAG, "sem config EGL para o OpenGL ES 3")
            return
        }
        config = configs[0]
        contexto = EGL14.eglCreateContext(tela, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (contexto == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "sem contexto OpenGL ES 3")
            return
        }
        reserva = EGL14.eglCreatePbufferSurface(tela, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(tela, reserva, reserva, contexto)
        oficina = Oficina(app)
        guardado = app.getSharedPreferences("motor", Context.MODE_PRIVATE)

        // o quadrado dos dois passes
        val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v)
        buf.position(0)
        val ids = IntArray(1)
        primitivas = Primitivas()
        GLES30.glGenVertexArrays(1, ids, 0)
        quadrado = ids[0]
        GLES30.glBindVertexArray(quadrado)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        Log.i(TAG, "GL: ${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}")
    }

    private fun quadro(agoraNs: Long) {
        Diagnostico.vsync()
        // nada a desenhar (fora da frente, congelado no ambiente, orbes parados ou fora de
        // vista): a thread dorme até alguém a [acordar]
        if (pausado || (congelado && !umaVez) || !precisa()) {
            rodando = false
            return
        }
        val forcado = umaVez
        var calmo = true
        var livre = true             // nenhum orbe a desenhar: a prévia tem todo vsync
        for (i in alvos.indices) {
            val v = alvos[i].view
            if (!v.aVista) continue
            if (!v.cena.calma) calmo = false
            if (!v.parado || alvos[i].desenhadoEm == 0L) livre = false
        }
        val intervalo = if (calmo) intervaloCalmo else intervaloAtivo
        if (!forcado && ultimoNs != 0L && agoraNs - ultimoNs < intervalo) {
            umaPrevia()              // o vsync em que os orbes não desenham
            agendar(agoraNs, intervalo)
            return
        }
        umaVez = false
        val passou = if (ultimoNs == 0L) 0L else agoraNs - ultimoNs
        ultimoNs = agoraNs
        val t0 = System.nanoTime()
        var grande = false
        var n = 0
        var miniaturas = 0
        val primeiro = vez
        for (k in alvos.indices) {
            val i = (primeiro + k) % alvos.size
            val a = alvos[i]
            // parado, fica no último quadro; o primeiro sai mesmo assim (a página
            // que entra no carrossel e a miniatura que nasce no meio da rolagem)
            if (!a.view.aVista || (a.view.parado && a.desenhadoEm != 0L) || a.w <= 0 || a.h <= 0) continue
            if (!a.view.adaptar) {
                if (miniaturas >= MINIATURAS_POR_QUADRO) continue
                miniaturas++
                vez = i + 1
            }
            try {
                desenhar(a, if (a.desenhadoEm == 0L) 1.0 / 30 else (agoraNs - a.desenhadoEm) / 1e9)
                a.desenhadoEm = agoraNs
                n++
                if (a.view.adaptar) grande = true
            } catch (e: Exception) {
                Log.e(TAG, "quadro: ${e.message}")
            }
        }
        val lacoNs = System.nanoTime() - t0
        somaLacoMs += lacoNs / 1e6f
        desenhados += n
        Diagnostico.laco(n, lacoNs)
        if (livre) umaPrevia()
        // só as janelas com o orbe grande na tela contam para a qualidade dele, e
        // sem prévia no meio: o atraso de um quadro dela não é dele
        if (!grande) grandeDesde = 0L else if (grandeDesde == 0L) grandeDesde = agoraNs
        if (grande && previas.isEmpty() && agoraNs - grandeDesde >= AQUECIMENTO_NS && !forcado) avaliar(agoraNs, passou, intervalo)
        else janelaDesde = 0L
        agendar(agoraNs, intervalo)
    }

    /** Um quadro da primeira prévia da fila, lido da GPU para um Bitmap. */
    private fun umaPrevia() {
        val p = previas.firstOrNull() ?: return
        if (p.pedido.cancelado) {
            previas.removeFirst()
            EGL14.eglMakeCurrent(tela, reserva, reserva, contexto)
            p.soltar()
            p.quadros.forEach { it.recycle() }
            return
        }
        if (!EGL14.eglMakeCurrent(tela, reserva, reserva, contexto)) return
        val wl = (p.w / p.densidade).toDouble()
        val hl = (p.h / p.densidade).toDouble()
        if (p.fbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            p.cor = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.cor)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, p.w, p.h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glGenFramebuffers(1, ids, 0)
            p.fbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, p.fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, p.cor, 0)
            p.buf = ByteBuffer.allocateDirect(p.w * p.h * 4).order(ByteOrder.nativeOrder())
            // a miniatura já andando, sem o começo da animação
            repeat(AQUECIMENTO) { p.cena.passo(p.passo, wl, hl) }
        }
        try {
            pintar(p.m, p.cena.passo(p.passo, wl, hl), p.fbo, p.w, p.h, wl, hl, 1f, false)
        } catch (e: Exception) {
            Log.e(TAG, "prévia: ${e.message}")
        }
        val buf = p.buf ?: return
        buf.position(0)
        GLES30.glReadPixels(0, 0, p.w, p.h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        // cor pré-multiplicada, como o Bitmap guarda; as linhas já vêm de cima (pintar)
        Diagnostico.previa()
        val bmp = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        // na memória da GPU: o menu desenha sem subir a textura a cada troca de quadro
        val hw = bmp.copy(Bitmap.Config.HARDWARE, false)
        if (hw != null) bmp.recycle()
        p.quadros.add(hw ?: bmp)
        if (p.quadros.size < p.n) return
        previas.removeFirst()
        p.soltar()
        val prontos = p.quadros.toList()
        principal.post { if (!p.pedido.cancelado) p.pronto(prontos) }
    }

    /**
     * Perdeu a vez o quadro que saiu mais de um vsync depois do combinado. Com a
     * maioria deles atrasada por duas janelas seguidas (abaixo de uns 22 por
     * segundo), a máscara desce até onde os quadros cabem; meio minuto sem
     * atraso, volta um degrau. Se a volta atrasa de novo, aquele fica sendo o teto.
     */
    private fun avaliar(agoraNs: Long, passou: Long, intervalo: Long) {
        val skin = skinGrande
        if (skin != skinMedida) {
            // outra skin, outro shader: vale o que se aprendeu dela, ou começa inteira
            skinMedida = skin
            qualidade = guardado?.getFloat(chave(skin), 1f)?.coerceIn(QUALIDADE_MIN, 1f) ?: 1f
            teto = 1f
            ruins = 0
            boas = 0
            janelaDesde = 0L
        }
        if (janelaDesde == 0L) {
            janelaDesde = agoraNs
            zerarJanela()
            return
        }
        naJanela++
        if (passou > intervalo * 16 / 10) atrasados++
        if (agoraNs - janelaDesde < JANELA_NS) return
        val fracao = atrasados.toFloat() / naJanela
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "%s: %.1f quadros/s, %d%% atrasados; máscara a %d%%, %.1f ms; %.1f orbes, laço de %.1f ms".format(
                skin?.id, naJanela * 1e9f / (agoraNs - janelaDesde), (fracao * 100).roundToInt(), (qualidade * 100).roundToInt(),
                somaMascaraMs / naJanela, desenhados.toFloat() / naJanela, somaLacoMs / naJanela))
        }
        if (qualidadeFixa > 0f) {
            qualidade = qualidadeFixa
        } else {
            when {
                fracao > 0.6f -> { ruins++; boas = 0 }
                fracao < 0.1f -> { boas++; ruins = 0 }
                else -> { ruins = 0; boas = 0 }
            }
            val antes = qualidade
            // com quadros atrasados a GPU já está no máximo e o custo medido vale:
            // a máscara desce de uma vez até caber no orçamento, sem esperar outra janela
            val mascaraMs = somaMascaraMs / naJanela
            val cabeNoOrcamento = if (fracao > 0.1f && mascaraMs > 0f) {
                floor(qualidade * sqrt(ORCAMENTO_MS / mascaraMs) * 8) / 8f
            } else {
                qualidade
            }
            if (cabeNoOrcamento < qualidade && qualidade > QUALIDADE_MIN) {
                if (agoraNs - subiuEm < 20_000_000_000L) teto = qualidade - 0.125f
                qualidade = cabeNoOrcamento.coerceIn(QUALIDADE_MIN, qualidade - 0.125f)
                ruins = 0
                boas = 0
                Log.i(TAG, "máscara a ${(qualidade * 100).roundToInt()}%: custava ${mascaraMs.roundToInt()} ms")
            } else if (ruins >= 2 && qualidade > QUALIDADE_MIN) {
                if (agoraNs - subiuEm < 20_000_000_000L) teto = qualidade - 0.125f
                // o custo acompanha a área: desce de uma vez até onde os quadros cabem
                val qps = naJanela * 1e9f / (agoraNs - janelaDesde)
                val cabe = qualidade * sqrt(qps / (0.9f * 1e9f / (intervalo * 10 / 9)))
                qualidade = ((cabe * 8).roundToInt() / 8f).coerceIn(QUALIDADE_MIN, qualidade - 0.125f)
                ruins = 0
                Log.i(TAG, "máscara a ${(qualidade * 100).roundToInt()}%: saíam ${qps.roundToInt()} quadros por segundo")
            } else if (boas >= 15 && qualidade < teto) {
                qualidade += 0.125f
                boas = 0
                subiuEm = agoraNs
                Log.i(TAG, "máscara a ${(qualidade * 100).roundToInt()}%")
            }
            if (qualidade != antes) guardado?.edit()?.putFloat(chave(skin), qualidade)?.apply()
        }
        janelaDesde = agoraNs
        zerarJanela()
    }

    // desenhado por elemento, o custo é outro: a qualidade aprendida por pixel não vale.
    // As de imagem passaram a pular as camadas sem desenho no pixel: a aprendida antes também não
    // O Seraphim ganhou as asas articuladas (2026-10-07): o shader é outro, a de antes não vale
    private fun chave(skin: Skin?) = "qualidade_${skin?.id}" + when {
        skin == Skin.SERAFIM_GRAVURA || skin == Skin.SERAFIM_POSITIVO -> "_articulado"
        skin?.primitivas == true -> "_prim"
        skin?.imagem == true -> "_caixas"
        else -> ""
    }

    private fun zerarJanela() {
        naJanela = 0
        atrasados = 0
        somaMascaraMs = 0f
        somaLacoMs = 0f
        desenhados = 0
    }

    private fun desenhar(a: Alvo, dt: Double) {
        if (!EGL14.eglMakeCurrent(tela, a.egl, a.egl, contexto)) return
        val dens = a.view.densidade
        val wl = (a.w / dens).toDouble()
        val hl = (a.h / dens).toDouble()
        val arte = a.view.cena.passo(dt, wl, hl)
        if (a.view.adaptar) skinGrande = arte.skin
        pintar(a.m, arte, 0, a.w, a.h, wl, hl, if (a.view.adaptar) qualidade else 1f, a.view.adaptar)
        esperarGpu()
        EGL14.eglSwapBuffers(tela, a.egl)
    }

    /**
     * Só vai para a TextureView um quadro que a GPU já terminou. A RenderThread,
     * ao compor a tela, pega o quadro mais novo da fila e espera a cerca dele; com
     * a GPU levando quase o intervalo inteiro (o anel, ~30 ms a cada 30 ms), chegava
     * outro quadro inacabado durante a espera e ela corria atrás dele, e a tela
     * parava (perfetto de 2026-10-06: 29 esperas num DrawFrame de 947 ms, com a
     * orbe-gl em dia). Esperando aqui, o custo da GPU atrasa a orbe-gl, onde a
     * qualidade da máscara ([avaliar]) o vê e se ajusta.
     */
    private fun esperarGpu() {
        val cerca = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (cerca == 0L) return
        GLES30.glClientWaitSync(cerca, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, ESPERA_GPU_NS)
        GLES30.glDeleteSync(cerca)
    }

    /**
     * Os dois passes de um orbe em [saida], de [w] x [h] px: a máscara em [m],
     * na fração [q] da resolução, e a cor por cima. Na saída 0 (a superfície da
     * janela) a imagem vai invertida, como a tela quer; num FBO, com a linha de
     * cima primeiro, como o Bitmap lê.
     */
    private fun pintar(m: Mascara, arte: Arte, saida: Int, w: Int, h: Int, wl: Double, hl: Double, q: Float, medir: Boolean) {
        val of = oficina ?: return
        val prim = of.primitivas(arte.skin)
        val figura = prim ?: of.figura(arte.skin, arte.variante)        // sem primitivas nesta GPU, por pixel
        // a outra variante (o anel com e sem as línguas) compila já no primeiro quadro da
        // arte, na abertura: a primeira língua que acende não para o orbe (362 ms no Adreno 504)
        if (prim == null) arte.outraVariante?.let { of.figura(arte.skin, it) }
        val pos = of.pos()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, saida)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        if (figura == null || pos == null) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        // ── primeiro passe: a máscara, no FBO (o "layer" do item no Qt) ──
        val t0 = System.nanoTime()
        mascara(m, w, h, q)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, m.fbo)
        GLES30.glViewport(0, 0, m.fw, m.fh)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
        recortar(m, arte, wl, hl)
        if (prim != null) {
            primitivas?.desenhar(prim, arte.fx, (wl / m.fw).toFloat())
            GLES30.glBindVertexArray(quadrado)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(figura.id)
            figura.aplicar(arte.fx)
            figura.v1("qt_Opacity", 1f)
            figura.v4("uvT", 1f, 1f, 0f, 0f)
            val atlas = of.textura(arte.skin)
            if (atlas != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
                figura.amostrador("arte", 0)
                figura.amostrador("atlas", 0)
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)      // o quadrado ficou ligado no abrir()
            Diagnostico.gl()
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if (medir && Log.isLoggable(TAG, Log.VERBOSE)) {
            GLES30.glFinish()            // só no diagnóstico: esperar a GPU a cada quadro custa quadros
            somaMascaraMs += (System.nanoTime() - t0) / 1e6f
        }

        // ── segundo passe: cor, aberração e glitch, na saída ──
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, saida)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)   // cor pré-multiplicada
        GLES30.glUseProgram(pos.id)
        pos.aplicar(arte.pos)
        pos.v1("qt_Opacity", 1f)
        if (saida == 0) pos.v4("uvT", 1f, -1f, 0f, 1f)                    // a tela tem o y para baixo
        else pos.v4("uvT", 1f, 1f, 0f, 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.textura)
        pos.amostrador("source", 0)
        // só onde o pos.frag pode pintar (a célula e o alcance do glitch): fora dela ele dá zero,
        // o mesmo do glClear acima, então a imagem é igual com menos pixels rodando
        val alcance = RecortePos.calcular(arte.pos, arte.recorte, wl, hl)
            ?.let { RecortePos.emPx(it, w, h, wl, hl, invertido = saida == 0) }
        if (alcance != null) {
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(alcance[0], alcance[1], alcance[2], alcance[3])
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        Diagnostico.gl()
    }

    /** O FBO da máscara: no tamanho da superfície; no orbe grande, vezes a qualidade. */
    private fun mascara(m: Mascara, w: Int, h: Int, q: Float) {
        val fw = max(8, (w * q).roundToInt())
        val fh = max(8, (h * q).roundToInt())
        if (m.fbo != 0 && fw == m.fw && fh == m.fh) return
        val ids = IntArray(1)
        if (m.fbo == 0) {
            GLES30.glGenFramebuffers(1, ids, 0)
            m.fbo = ids[0]
            GLES30.glGenTextures(1, ids, 0)
            m.textura = ids[0]
            GLES30.glGenRenderbuffers(1, ids, 0)
            m.estencil = ids[0]
        }
        m.fw = fw
        m.fh = fh
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.textura)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, fw, fh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, m.estencil)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_STENCIL_INDEX8, fw, fh)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, m.fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, m.textura, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, m.estencil)
    }

    /** Só a célula da figura é pintada: fora dela a máscara é vazia de qualquer jeito. */
    private fun recortar(m: Mascara, arte: Arte, wl: Double, hl: Double) {
        val r = arte.recorte
        if (r[2] * 2 >= wl && r[3] * 2 >= hl) return
        val sx = m.fw / wl
        val sy = m.fh / hl
        val x0 = max(0.0, (r[0] - r[2]) * sx).toInt()
        val y0 = max(0.0, (r[1] - r[3]) * sy).toInt()      // no FBO o y lógico cresce para cima
        val x1 = min(m.fw.toDouble(), (r[0] + r[2]) * sx + 1).toInt()
        val y1 = min(m.fh.toDouble(), (r[1] + r[3]) * sy + 1).toInt()
        if (x1 <= x0 || y1 <= y0) return
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
    }
}
