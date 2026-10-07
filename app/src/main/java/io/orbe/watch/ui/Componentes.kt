package io.orbe.watch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.sizeIn
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// Os componentes do app do Orbe (orbe-qt/app/*.qml) na medida do relógio:
// mesmos nomes, mesmas cores e mesmas proporções.

@Composable
fun Texto(
    texto: String,
    estilo: TextStyle,
    modifier: Modifier = Modifier,
    cor: Color = Estilo.texto,
    alinhar: TextAlign? = null,
    linhas: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = texto,
        modifier = modifier,
        style = estilo.copy(color = cor, textAlign = alinhar ?: TextAlign.Unspecified),
        maxLines = linhas,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Verdadeiro enquanto a tela rola: as miniaturas esperam paradas (a GPU do relógio é pouca para as duas coisas). */
val LocalRolando = compositionLocalOf { false }

/** O papel de parede borrado atrás dos menus; desligado, eles ficam no preto. */
val LocalFundoMenu = compositionLocalOf { true }

/** O relógio escureceu com o app aberto: só o orbe fica, apagado; rótulos, pontos e textos saem. */
val LocalAmbiente = compositionLocalOf { false }

/** Botão de texto: chapado, ou em pílula na cor de destaque. `ligado` tinge o chapado, para os que alternam. */
@Composable
fun Botao(
    texto: String,
    modifier: Modifier = Modifier,
    destaque: Boolean = false,
    ligado: Boolean = false,
    ativo: Boolean = true,
    aoClicar: () -> Unit,
) {
    val fonte = remember { MutableInteractionSource() }
    val apertado by fonte.collectIsPressedAsState()
    Box(
        modifier
            .alpha(if (ativo) 1f else 0.4f)
            .height(if (destaque) 36.dp else 30.dp)
            .vidro(CircleShape, tinta = if (destaque) Estilo.accent else if (ligado) Estilo.accent.alfa(0.35f) else null, apertado = apertado)
            .clickable(fonte, indication = null, enabled = ativo, onClick = aoClicar)
            .padding(horizontal = if (destaque) 22.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Texto(texto, Estilo.botao, cor = if (destaque) Estilo.accentFg else if (ligado) Estilo.accent else Estilo.texto, linhas = 1)
    }
}

/** Ícone simbólico pintado na cor pedida, como o Icone.qml do app. */
@Composable
fun Icone(id: Int, cor: Color, modifier: Modifier = Modifier) {
    Image(painterResource(id), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(cor))
}

/** Botão redondo e chapado com um ícone (o BotaoIcone.qml do app, com fundo). */
@Composable
fun BotaoIcone(icone: Int, modifier: Modifier = Modifier, ativo: Boolean = true, aoClicar: () -> Unit) {
    val fonte = remember { MutableInteractionSource() }
    val apertado by fonte.collectIsPressedAsState()
    Box(
        modifier
            .alpha(if (ativo) 1f else 0.4f)
            .size(28.dp)
            .vidro(CircleShape, apertado = apertado)
            .clickable(fonte, indication = null, enabled = ativo, onClick = aoClicar),
        contentAlignment = Alignment.Center,
    ) { Icone(icone, Estilo.texto, Modifier.size(16.dp)) }
}

/**
 * Um botão da gaveta: o vidro das caixas do app num círculo, o ícone da aba e
 * o nome embaixo. Ligado (a prévia rodando), na cor de destaque, como a aba
 * marcada no app.
 */
@Composable
fun BotaoGaveta(icone: Int, nome: String, ligado: Boolean = false, aoClicar: () -> Unit) {
    val fonte = remember { MutableInteractionSource() }
    val apertado by fonte.collectIsPressedAsState()
    Column(
        Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .vidro(CircleShape, tinta = if (ligado) Estilo.accent.alfa(0.35f) else null, apertado = apertado)
                .clickable(fonte, indication = null, onClick = aoClicar),
            contentAlignment = Alignment.Center,
        ) { Icone(icone, if (ligado) Estilo.accent else Estilo.texto, Modifier.size(22.dp)) }
        Texto(nome, Estilo.cartao, cor = if (ligado) Estilo.accent else Estilo.texto.alfa(0.85f), alinhar = TextAlign.Center, linhas = 1)
    }
}

/** Uma etiqueta em caixa, como a tecla do atalho na aba Ativação. */
@Composable
fun Etiqueta(texto: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .vidro(RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Texto(texto, Estilo.mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), linhas = 1)
    }
}

/** A caixa de vidro de um grupo do app (Grupo.qml): cada linha do menu tem a sua. */
@Composable
fun Modifier.caixa(): Modifier {
    return fillMaxWidth().vidro(RoundedCornerShape(12.dp))
}

/**
 * Os itens do menu, cada um na roda do Wear: perto do alto e do pé da tela
 * redonda o item encolhe e some, como nas listas do sistema (e no HinaWatch).
 */
class MenuEscopo internal constructor(
    private val lista: TransformingLazyColumnScope,
    private val spec: TransformationSpec,
) {
    /** Um item solto: o topo, uma fileira de cartões, o rodapé. */
    fun item(conteudo: @Composable () -> Unit) {
        lista.item { Roda(this, spec, conteudo) }
    }

    /** Título e descrição de um grupo, fora das caixas; o vão de cima separa do grupo anterior. */
    fun grupo(titulo: String, descricao: String = "") = item {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (titulo.isNotEmpty()) Texto(titulo, Estilo.grupo, Modifier.padding(start = 4.dp))
            if (descricao.isNotEmpty()) Texto(descricao, Estilo.subtitulo, Modifier.padding(horizontal = 4.dp), cor = Estilo.texto.alfa(0.55f))
        }
    }

    /** Uma linha de preferência, na caixa de vidro dela. */
    fun linha(conteudo: @Composable () -> Unit) = item {
        Box(Modifier.caixa()) { conteudo() }
    }

    /** Linhas em duas colunas, cada uma na sua caixa (a última sozinha, se sobrar). */
    fun duas(linhas: List<@Composable () -> Unit>) {
        linhas.chunked(2).forEach { par ->
            item {
                // as duas da fileira com a mesma altura, a da maior: não seguem o texto
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    par.forEach { l -> Box(Modifier.weight(1f).fillMaxHeight().caixa(), contentAlignment = Alignment.CenterStart) { l() } }
                    if (par.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Roda(escopo: TransformingLazyColumnItemScope, spec: TransformationSpec, conteudo: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(escopo, spec)
            .graphicsLayer { with(escopo) { with(spec) { applyContainerTransformation(scrollProgress) } } },
    ) { conteudo() }
}

/**
 * O menu: uma lista que rola em roda, com o indicador de rolagem do sistema.
 * A coroa é do OrbeApp (no orbe ela passa de orbe em orbe), por isso a lista não
 * pega a coroa sozinha.
 */
@Composable
fun Menu(lista: TransformingLazyColumnState, margem: Dp, conteudo: MenuEscopo.() -> Unit) {
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = lista) { vaos ->
        TransformingLazyColumn(
            state = lista,
            contentPadding = PaddingValues(
                start = margem, end = margem,
                top = vaos.calculateTopPadding(), bottom = vaos.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            rotaryScrollableBehavior = null,
            modifier = Modifier.fillMaxSize(),
        ) { MenuEscopo(this, spec).conteudo() }
    }
}

/**
 * O fundo do menu. No PC o app é vidro sobre o papel de parede borrado pelo
 * niri; a ponte manda o papel em poucas cores, e aqui elas viram o borrão,
 * escurecido por preto ([VIDRO]): o cinza do tema, como no Conteudo.qml,
 * lavava as cores e tirava o preto da tela AMOLED. Com uma imagem escolhida
 * no app do PC, ela entra no lugar do borrão, nítida, sob o mesmo preto. Sem
 * papel (outro sistema, ponte antiga), o clarão do tema no alto.
 */
/** quanto o preto do vidro cobre o borrão do papel */
private const val VIDRO = 0.62f

@Composable
fun FundoVidro(modifier: Modifier = Modifier, menu: Boolean = true) {
    val tema = LocalTema.current
    if (menu && !LocalFundoMenu.current) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }
    val borrao = remember(tema.papel) { tema.papel.takeIf { it.isNotEmpty() }?.let(::borrar) }
    Canvas(modifier.fillMaxSize()) {
        val imagem = tema.imagem
        if (imagem != null) {
            drawImage(imagem, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            drawRect(Color.Black.alfa(VIDRO))
        } else if (borrao != null) {
            drawImage(borrao, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            drawRect(Color.Black.alfa(VIDRO))
        } else {
            drawRect(tema.fundo)
            drawRect(
                Brush.radialGradient(
                    listOf(tema.accent.alfa(0.13f), Color.Transparent),
                    center = Offset(size.width / 2, size.width * 0.30f), radius = size.width * 0.62f,
                ),
            )
        }
    }
}

/**
 * As n x n cores do papel numa imagem de 64 px, por uma B-spline cúbica: o
 * esticado até a tela fica liso, sem as quinas do bilinear direto das cores.
 */
private fun borrar(cores: List<Color>): ImageBitmap {
    val n = Tema.lado(cores.size)
    val lado = 64
    val px = IntArray(lado * lado)
    val wx = FloatArray(4)
    val wy = FloatArray(4)
    fun pesos(t: Float, w: FloatArray) {
        val u = 1 - t
        w[0] = u * u * u / 6
        w[1] = (3 * t * t * t - 6 * t * t + 4) / 6
        w[2] = (-3 * t * t * t + 3 * t * t + 3 * t + 1) / 6
        w[3] = t * t * t / 6
    }
    for (y in 0 until lado) {
        val gy = (y + 0.5f) / lado * n - 0.5f
        val iy = floor(gy).toInt()
        pesos(gy - iy, wy)
        for (x in 0 until lado) {
            val gx = (x + 0.5f) / lado * n - 0.5f
            val ix = floor(gx).toInt()
            pesos(gx - ix, wx)
            var r = 0f
            var g = 0f
            var b = 0f
            for (j in 0 until 4) {
                val cy = (iy - 1 + j).coerceIn(0, n - 1)
                for (i in 0 until 4) {
                    val c = cores[cy * n + (ix - 1 + i).coerceIn(0, n - 1)]
                    val w = wx[i] * wy[j]
                    r += c.red * w
                    g += c.green * w
                    b += c.blue * w
                }
            }
            px[y * lado + x] = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f)).toArgb()
        }
    }
    return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Linha de uma caixa de preferências: título, subtítulo e o que vier à direita. */
@Composable
fun Linha(
    titulo: String,
    modifier: Modifier = Modifier,
    subtitulo: String = "",
    ativo: Boolean = true,
    aoClicar: (() -> Unit)? = null,
    sufixo: @Composable RowScope.() -> Unit = {},
) {
    val fonte = remember { MutableInteractionSource() }
    val apertado by fonte.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (ativo) 1f else 0.4f)
            .defaultMinSize(minHeight = 40.dp)
            .background(Estilo.accent.alfa(if (apertado) 0.07f else 0f))
            .then(if (aoClicar != null) Modifier.clickable(fonte, indication = null, enabled = ativo, onClick = aoClicar) else Modifier)
            .padding(start = 11.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Texto(titulo, Estilo.titulo, linhas = 2)
            if (subtitulo.isNotEmpty()) Texto(subtitulo, Estilo.subtitulo, cor = Estilo.texto.alfa(0.55f))
        }
        sufixo()
    }
}

/** A chave do app: trilho de destaque quando ligada, botão que desliza. */
@Composable
fun LinhaSwitch(
    titulo: String,
    ligado: Boolean,
    modifier: Modifier = Modifier,
    subtitulo: String = "",
    ativo: Boolean = true,
    aoMudar: (Boolean) -> Unit,
) {
    Linha(titulo, modifier, subtitulo, ativo, aoClicar = { aoMudar(!ligado) }) {
        val trilho by animateColorAsState(if (ligado) Estilo.accent else Estilo.texto.alfa(0.16f), tween(120), label = "trilho")
        val x by animateDpAsState(if (ligado) 19.dp else 3.dp, tween(120), label = "botão")
        Box(Modifier.size(38.dp, 22.dp).clip(CircleShape).background(trilho)) {
            Box(
                Modifier.offset { IntOffset(x.roundToPx(), 3.dp.roundToPx()) }.size(16.dp).clip(CircleShape)
                    .background(if (ligado) Estilo.accentFg else Estilo.texto),
            )
        }
    }
}

/** A faixa da borda do cartão redondo que cada nome em arco ocupa, no alto e no pé. */
val FAIXA_CARTAO = 13.dp
private val BORDA_NOME = 3.dp
private const val ARCO_NOME = 160f

/**
 * Miniatura animada de uma skin num cartão redondo, como o mostrador: o nome
 * dela em arco no alto e a [nota] (o agente dela) em arco no pé, como o rótulo
 * da tela do orbe. O cartão é um círculo da largura que o [modifier] der.
 */
@Composable
fun Cartao(
    previa: ChavePrevia,
    marcado: Boolean,
    modifier: Modifier = Modifier,
    nota: String? = null,
    aoEscolher: () -> Unit,
) {
    val fonte = remember { MutableInteractionSource() }
    val apertado by fonte.collectIsPressedAsState()
    val corNome = if (marcado) Estilo.accent else Estilo.texto
    val corNota = Estilo.texto.alfa(0.55f)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (marcado) Estilo.accent.alfa(0.14f) else Estilo.texto.alfa(if (apertado) 0.07f else 0.04f))
            .border(1.dp, if (marcado) Estilo.accent.alfa(0.55f) else Estilo.texto.alfa(0.08f), CircleShape)
            .clickable(fonte, indication = null, onClick = aoEscolher),
        contentAlignment = Alignment.Center,
    ) {
        Miniatura(previa, Modifier.size(previa.largura, previa.altura))
        Box(Modifier.fillMaxSize().padding(BORDA_NOME)) {
            CurvedLayout(anchor = 270f) {
                basicCurvedText(
                    previa.skin.nome,
                    style = { CurvedTextStyle(color = corNome, fontSize = Estilo.cartao.fontSize, fontWeight = FontWeight.Bold) },
                    modifier = CurvedModifier.sizeIn(maxSweepDegrees = ARCO_NOME),
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nota != null) {
                // no pé a leitura é da esquerda para a direita: o sentido contrário ao do alto
                CurvedLayout(anchor = 90f, angularDirection = CurvedDirection.Angular.Reversed) {
                    basicCurvedText(
                        nota,
                        style = { CurvedTextStyle(color = corNota, fontSize = Estilo.cartao.fontSize) },
                        modifier = CurvedModifier.sizeIn(maxSweepDegrees = ARCO_NOME),
                        angularDirection = CurvedDirection.Angular.Reversed,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tamanho do orbe na tela. O botão do slider é a miniatura da skin escolhida (a do cartão dela). */
@Composable
fun SliderOrbe(
    valor: Float,
    de: Float,
    ate: Float,
    botao: ChavePrevia,
    modifier: Modifier = Modifier,
    passo: Float = 0.05f,
    aoMudar: (Float) -> Unit,
) {
    val lado = 44.dp
    val mudar by rememberUpdatedState(aoMudar)
    val fracao = ((valor - de) / (ate - de)).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Texto("Tamanho", Estilo.titulo)
                Texto("escala do orbe na tela", Estilo.subtitulo, cor = Estilo.texto.alfa(0.55f))
            }
            Texto("${(valor * 100).roundToInt()}%", Estilo.botao, cor = Estilo.accent)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(lado + 6.dp).padding(top = 6.dp)) {
            val densidade = LocalDensity.current
            val trilho = maxWidth - lado
            val pos = trilho * fracao
            val vao = lado / 2 + 2.dp          // o trilho abre um vão ao redor do botão
            val definir = { xPx: Float ->
                val larg = with(densidade) { trilho.toPx() }
                val meio = with(densidade) { (lado / 2).toPx() }
                val v = de + ((xPx - meio) / larg).coerceIn(0f, 1f) * (ate - de)
                val arred = de + ((v - de) / passo).roundToInt() * passo
                mudar((arred * 100).roundToInt() / 100f)
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(de, ate) { detectTapGestures { definir(it.x) } }
                    // a rolagem é da página: o slider só muda arrastando de lado
                    .pointerInput(de, ate) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            definir(change.position.x)
                        }
                    },
            ) {
                val y = Modifier.align(Alignment.CenterStart)
                // trecho percorrido, até o vão
                Box(y.offset(x = lado / 2).width((pos - vao).coerceAtLeast(0.dp)).height(5.dp).clip(CircleShape).background(Estilo.accent.alfa(0.75f)))
                // trecho restante, depois do vão
                val resto = (trilho - pos - vao).coerceAtLeast(0.dp)
                Box(y.offset(x = lado / 2 + trilho - resto).width(resto).height(5.dp).clip(CircleShape).background(Estilo.texto.alfa(0.12f)))
                // marca do tamanho original (some dentro do vão)
                val marca = trilho * ((1f - de) / (ate - de))
                if (abs((marca - pos).value) > vao.value) {
                    Box(y.offset(x = lado / 2 + marca - 1.dp).width(2.dp).height(12.dp).clip(CircleShape).background(Estilo.texto.alfa(0.35f)))
                }
                // o botão é só o orbe, sem aro nem disco
                Miniatura(botao, y.offset(x = pos).size(lado))
            }
        }
    }
}

/** Um ponto de espaço entre grupos, para a conta ficar no chamador. */
@Composable
fun Vao(altura: Dp) = Spacer(Modifier.height(altura))

/** Escala suave para o que aparece e some (o aviso do rodapé, a linha de estado). */
@Composable
fun Sumir(visivel: Boolean, modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    val a by animateFloatAsState(if (visivel) 1f else 0f, tween(160), label = "sumir")
    if (a > 0.01f) Box(modifier.graphicsLayer { alpha = a }) { conteudo() }
}
