package io.orbe.watch.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.orbe.watch.gl.Motor
import io.orbe.watch.orbe.MiniCena
import io.orbe.watch.orbe.Skin
import kotlin.math.min
import kotlin.math.roundToInt

// As miniaturas do menu em quadros prontos, como os bustos do HinaWatch: o
// Motor desenha cada uma fora da tela, uma vez, e o menu só troca de imagem.
// Com uma superfície GL viva por miniatura, a rolagem do menu disputava a GPU
// do relógio com elas e perdia quadros.

/** Onde a miniatura aparece: o traço de cada lugar (Miniatura.qml). */
enum class Papel(val peso: Double, val quadros: Int = 16) {
    TOPO(1.0), CARTAO(1.2),
    /** as instâncias ativas do orbe, paradas, no pé da tela (TelaOrbe) */
    INSTANCIA(1.6, quadros = 1),
}

/** Tudo que muda o desenho de uma miniatura: outra chave, outros quadros. */
data class ChavePrevia(
    val papel: Papel,
    val skin: Skin,
    val largura: Dp,
    val altura: Dp,
    val glitch: Boolean,
    val linhas: Boolean,
    val cor: Color,
    val anel: Color,
    val fundo: Color,
)

internal object Previas {
    /** Quadros de cada miniatura, em vai e volta: sem emenda no fim do laço. */
    private const val QUADROS = 16
    /** Entre um quadro e outro: o ritmo das miniaturas vivas no rodízio do Motor. */
    const val PASSO_MS = 100L
    /** Prévias guardadas: as do menu (os cartões e o topo), as das instâncias e as que estão saindo. */
    private const val GUARDADAS = 16

    private val prontas = mutableStateMapOf<ChavePrevia, List<ImageBitmap>>()
    private val ordem = ArrayDeque<ChavePrevia>()
    private val pedidas = HashMap<Triple<Papel, Skin, Color?>, Pair<ChavePrevia, Motor.Pedido>>()
    /** a última prévia pronta de cada lugar: fica na tela enquanto a nova (outro glitch, outro tema) sai */
    private val ultimas = HashMap<Triple<Papel, Skin, Color?>, List<ImageBitmap>>()

    /** Muda a cada [limpar]: quem desenha prévias pede de novo. */
    val geracao = mutableIntStateOf(0)

    private fun vaga(chave: ChavePrevia) = Triple(chave.papel, chave.skin, chave.cor.takeIf { chave.papel == Papel.INSTANCIA })

    fun quadros(chave: ChavePrevia): List<ImageBitmap>? = prontas[chave]

    fun ultima(chave: ChavePrevia): List<ImageBitmap>? = ultimas[vaga(chave)]

    /**
     * Solta todas as prévias (o app fora da frente): na volta, o menu as pede de
     * novo quando chegar perto dele. Na thread principal.
     */
    fun limpar() {
        pedidas.values.forEach { it.second.cancelar() }
        pedidas.clear()
        prontas.clear()
        ordem.clear()
        ultimas.clear()
        geracao.intValue++
    }

    /**
     * Na thread principal. Cada lugar (papel e skin) tem um pedido só: o novo
     * desiste do velho. As das instâncias, uma por cor, convivem.
     */
    fun pedir(ctx: Context, chave: ChavePrevia, densidade: Float) {
        if (chave in prontas) return
        val vaga = vaga(chave)
        val antes = pedidas[vaga]
        if (antes?.first == chave) return
        antes?.second?.cancelar()
        val cena = MiniCena(chave.skin).apply {
            glitch = chave.glitch
            varredura = chave.linhas
            peso = chave.papel.peso
            // o topo do app: o Ophanim no raio do cabeçalho, não o maior que cabe
            raio = if (chave.papel == Papel.TOPO) min(chave.altura.value * 0.42f, chave.largura.value * 0.32f).toDouble() else -1.0
            cor = chave.cor.rgb()
            accent = chave.anel.rgb()
            corFundo = chave.fundo.rgb()
        }
        val w = (chave.largura.value * densidade).roundToInt()
        val h = (chave.altura.value * densidade).roundToInt()
        val pedido = Motor.previa(ctx, cena, w, h, densidade, min(QUADROS, chave.papel.quadros), PASSO_MS / 1000.0) { bitmaps ->
            if (pedidas[vaga]?.first == chave) pedidas.remove(vaga)
            val imagens = bitmaps.map { it.asImageBitmap() }
            prontas[chave] = imagens
            ultimas[vaga] = imagens
            ordem.addLast(chave)
            while (ordem.size > GUARDADAS) prontas.remove(ordem.removeFirst())
        }
        pedidas[vaga] = chave to pedido
    }
}

/**
 * A skin parada, em imagem (previa_<skin> em src/debug), tingida na [cor]: o
 * lugar do orbe em GL nas prévias do Android Studio. Fora do debug, nada.
 */
@Composable
internal fun SkinParada(skin: Skin, cor: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    @Suppress("DiscouragedApi")
    val id = ctx.resources.getIdentifier("previa_${skin.id}", "drawable", ctx.packageName)
    if (id == 0) return
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(id), contentDescription = skin.nome, modifier = modifier,
        colorFilter = if (skin == Skin.ANEL) null else androidx.compose.ui.graphics.ColorFilter.tint(cor, androidx.compose.ui.graphics.BlendMode.Modulate),
    )
}

/**
 * As prévias do menu podem ser pedidas: o menu está perto, ou o orbe já teve uns
 * segundos para aquecer depois de abrir (OrbeApp). As das instâncias, de um
 * quadro só, saem sempre.
 */
val LocalPreviasLiberadas = staticCompositionLocalOf { true }

/** O passo das miniaturas: anda só com o menu na tela (TelaAjustes). */
val LocalQuadro = staticCompositionLocalOf<IntState> { mutableIntStateOf(0) }

/** O quadro do passo [passo] em vai e volta: 0, 1, …, n-1, n-2, …, 1, 0, … */
fun vaiEVolta(passo: Int, n: Int): Int {
    if (n <= 1) return 0
    val ciclo = 2 * n - 2
    val i = passo.mod(ciclo)
    return if (i < n) i else ciclo - i
}

/**
 * A figura de uma skin em miniatura (Miniatura.qml), dos quadros prontos.
 * Cabe no [modifier] sem deformar; trocou o glitch ou o tema, fica a anterior
 * até a nova sair.
 */
@Composable
fun Miniatura(chave: ChavePrevia, modifier: Modifier = Modifier) {
    // na prévia do Android Studio não há GL: a imagem parada da skin (src/debug)
    if (LocalInspectionMode.current) {
        SkinParada(chave.skin, chave.cor, modifier)
        return
    }
    val ctx = LocalContext.current
    val densidade = LocalDensity.current.density
    val liberada = LocalPreviasLiberadas.current || chave.papel == Papel.INSTANCIA
    val geracao = Previas.geracao.intValue
    LaunchedEffect(chave, liberada, geracao) { if (liberada) Previas.pedir(ctx, chave, densidade) }
    val quadros = Previas.quadros(chave)
    // a última do lugar mora nas Previas (e não aqui): o limpar a solta mesmo com o menu montado
    val mostrar = quadros ?: Previas.ultima(chave)
    val passo = LocalQuadro.current
    Canvas(modifier) {
        val q = mostrar ?: return@Canvas
        // o passo é lido só no desenho: trocar de quadro não recompõe nada
        val img = q[vaiEVolta(passo.intValue, q.size)]
        val e = min(size.width / img.width, size.height / img.height)
        val w = (img.width * e).roundToInt()
        val h = (img.height * e).roundToInt()
        drawImage(
            img,
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Low,
        )
    }
}
