package swmansion.enriched.markdown.android.example

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.swmansion.enriched.markdown.spoiler.CustomSpoilerOverlay
import com.swmansion.enriched.markdown.spoiler.SpoilerOverlayHost
import com.swmansion.enriched.markdown.spoiler.SpoilerSlice
import com.swmansion.enriched.markdown.spoiler.SpoilerSliceOverlay
import com.swmansion.enriched.markdown.styles.SpoilerStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Seven rooms of a haunted house, each a way of hiding the words, written for the Halloween
// screen. Each is a data class behind a slice overlay drawn on the text view's canvas. The
// overlays are text-aware: the hidden words show through as a faint ghost, and a reveal tears,
// burns or scatters what covers them, line by line for a wrapped spoiler.

private const val TWO_PI = (2 * PI).toFloat()
private const val WHITE = 0xFFFFFFFF.toInt()

/** The guest book's page, the color the overlays paint ahead of a reveal. */
const val GUEST_BOOK_PAGE = 0xFF171023.toInt()
private const val CREAM = 0xFFF1E4C8.toInt()

private fun easeInOut(p: Float): Float = if (p < 0.5f) 2 * p * p else 1 - (-2 * p + 2) * (-2 * p + 2) / 2

private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p)

private fun easeIn(p: Float): Float = p * p

/**
 * Shared toolkit: the words as a bitmap and as a blurred glow, feathered sweep and radial masks,
 * soft ellipses, stars, per-slice randomness, and a reveal that runs line by line. Subclasses
 * draw the idle look in [drawIdle] and one frame of the reveal in [drawRevealing]; ahead of a
 * front they paint [page] over the words the library is fading in.
 */
internal abstract class HauntedSlice(
  host: SpoilerOverlayHost,
  protected val page: Int,
) : SpoilerSliceOverlay() {
  protected val dp = host.density
  protected lateinit var random: Random
  protected var width = 0f
  protected var height = 0f

  /** Gap between the starts of consecutive lines of one spoiler, as a fraction of its reveal. */
  protected open val lineStagger = 0.4f

  private var preparedFor = -1L
  private var textBitmap: Bitmap? = null
  private val glows = HashMap<Int, Pair<Bitmap, IntArray>>()
  private val gradients = HashMap<Int, RadialGradient>()
  private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
  protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  protected val stroke =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
  protected val path = Path()
  private val rect = RectF()

  override val isAnimated get() = true

  protected abstract fun prepare(slice: SpoilerSlice)

  protected abstract fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  )

  protected abstract fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  )

  final override fun draw(
    canvas: Canvas,
    slice: SpoilerSlice,
  ) {
    ensurePrepared(slice)
    val saved = canvas.save()
    canvas.clipRect(0f, 0f, width, height)
    drawIdle(canvas, slice, slice.frameTimeMillis / 1000f)
    canvas.restoreToCount(saved)
  }

  final override fun drawReveal(
    canvas: Canvas,
    slice: SpoilerSlice,
    progress: Float,
  ) {
    ensurePrepared(slice)
    val span = 1f + lineStagger * (slice.count - 1)
    val local = (progress * span - slice.index * lineStagger).coerceIn(0f, 1f)
    val saved = canvas.save()
    canvas.clipRect(0f, 0f, width, height)
    when {
      // Waiting for the lines above: keep the idle look, and the words under it.
      local <= 0f -> {
        canvas.drawColor(page)
        drawIdle(canvas, slice, slice.frameTimeMillis / 1000f)
      }
      local < 1f -> drawRevealing(canvas, slice, slice.frameTimeMillis / 1000f, local)
    }
    canvas.restoreToCount(saved)
  }

  override fun onRemoved() {
    textBitmap = null
    glows.clear()
    preparedFor = -1L
  }

  private fun ensurePrepared(slice: SpoilerSlice) {
    val key = (slice.width.toLong() shl 20) xor slice.height.toLong()
    if (key == preparedFor) return
    preparedFor = key
    width = slice.width
    height = slice.height
    textBitmap = null
    glows.clear()
    random = Random(slice.start * 31L + slice.index * 7L + slice.width.toLong())
    prepare(slice)
  }

  // ---- the words

  private fun text(slice: SpoilerSlice): Bitmap =
    textBitmap ?: Bitmap.createBitmap(max(1, width.roundToInt()), max(1, height.roundToInt()), Bitmap.Config.ARGB_8888).also {
      slice.drawText(Canvas(it))
      textBitmap = it
    }

  /** The words, sharp, at [alpha]. */
  protected fun drawWords(
    canvas: Canvas,
    slice: SpoilerSlice,
    alpha: Float,
  ) {
    if (alpha <= 0.01f) return
    bitmapPaint.alpha = (alpha * 255).roundToInt()
    canvas.drawBitmap(text(slice), 0f, 0f, bitmapPaint)
  }

  /** The words blurred by [radius] and tinted [color]: a ghost of them, or a glow around them. */
  protected fun drawGlow(
    canvas: Canvas,
    slice: SpoilerSlice,
    color: Int,
    alpha: Float,
    radius: Float,
  ) {
    if (alpha <= 0.01f) return
    val key = (radius * 4).roundToInt()
    val (glow, offset) =
      glows.getOrPut(key) {
        val offset = IntArray(2)
        val blur = Paint().apply { maskFilter = BlurMaskFilter(max(0.5f, radius), BlurMaskFilter.Blur.NORMAL) }
        text(slice).extractAlpha(blur, offset) to offset
      }
    fill.shader = null
    fill.color = color
    fill.alpha = (alpha * 255).roundToInt()
    canvas.drawBitmap(glow, offset[0].toFloat(), offset[1].toFloat(), fill)
  }

  // ---- masks

  /** Draws [block], then keeps only what lies ahead of or behind [head], feathered over [feather]. */
  protected inline fun sweep(
    canvas: Canvas,
    head: Float,
    feather: Float,
    keepsAhead: Boolean,
    block: () -> Unit,
  ) {
    val saved = canvas.saveLayer(0f, 0f, width, height, null)
    block()
    val colors = if (keepsAhead) intArrayOf(0, WHITE) else intArrayOf(WHITE, 0)
    maskPaint.shader = LinearGradient(head - feather, 0f, head + feather, 0f, colors, null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, maskPaint)
    canvas.restoreToCount(saved)
  }

  /** Draws [block], then keeps only what lies inside or outside a circle, feathered at its rim. */
  protected inline fun bloom(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    feather: Float,
    keepsInside: Boolean,
    block: () -> Unit,
  ) {
    val saved = canvas.saveLayer(0f, 0f, width, height, null)
    block()
    val outer = max(radius + feather, 0.5f)
    val inner = (max(0f, radius - feather) / outer).coerceIn(0f, 0.999f)
    val colors = if (keepsInside) intArrayOf(WHITE, WHITE, 0) else intArrayOf(0, 0, WHITE)
    maskPaint.shader = RadialGradient(cx, cy, outer, colors, floatArrayOf(0f, inner, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, maskPaint)
    canvas.restoreToCount(saved)
  }

  /** The page itself ahead of a front, so the words stay hidden until it passes. */
  protected fun pageAhead(
    canvas: Canvas,
    head: Float,
    feather: Float,
  ) {
    sweep(canvas, head, feather, keepsAhead = true) { canvas.drawColor(page) }
  }

  // ---- shapes

  /** A radial gradient from [color] to clear, squashed to an ellipse: a cloud, a glow, a shadow. */
  protected fun softEllipse(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    color: Int,
    alpha: Float = 1f,
    core: Float = 0f,
  ) {
    if (alpha <= 0.01f || rx <= 0f || ry <= 0f) return
    val shader =
      gradients.getOrPut(color * 31 + (core * 10).roundToInt()) {
        RadialGradient(0f, 0f, 1f, intArrayOf(color, color, color and 0x00FFFFFF), floatArrayOf(0f, core, 1f), Shader.TileMode.CLAMP)
      }
    fill.shader = shader
    fill.alpha = (alpha * 255).roundToInt()
    val saved = canvas.save()
    canvas.translate(cx, cy)
    canvas.scale(rx, ry)
    canvas.drawCircle(0f, 0f, 1f, fill)
    canvas.restoreToCount(saved)
    fill.shader = null
  }

  protected fun circle(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    color: Int,
    alpha: Float = 1f,
  ) {
    if (alpha <= 0.01f) return
    fill.shader = null
    fill.color = color
    fill.alpha = (alpha * 255).roundToInt()
    canvas.drawCircle(cx, cy, radius, fill)
  }

  protected fun line(
    canvas: Canvas,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    widthPx: Float,
    color: Int,
    alpha: Float = 1f,
  ) {
    if (alpha <= 0.01f) return
    stroke.shader = null
    stroke.strokeWidth = widthPx
    stroke.color = color
    stroke.alpha = (alpha * 255).roundToInt()
    canvas.drawLine(x0, y0, x1, y1, stroke)
  }

  protected fun strokePath(
    canvas: Canvas,
    p: Path,
    widthPx: Float,
    color: Int,
    alpha: Float = 1f,
  ) {
    if (alpha <= 0.01f) return
    stroke.shader = null
    stroke.strokeWidth = widthPx
    stroke.color = color
    stroke.alpha = (alpha * 255).roundToInt()
    canvas.drawPath(p, stroke)
  }

  protected fun fillPath(
    canvas: Canvas,
    p: Path,
    color: Int,
    alpha: Float = 1f,
  ) {
    if (alpha <= 0.01f) return
    fill.shader = null
    fill.color = color
    fill.alpha = (alpha * 255).roundToInt()
    canvas.drawPath(p, fill)
  }

  /** A four-point star, the shape of a glint. */
  protected fun star(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    size: Float,
    color: Int,
    alpha: Float,
  ) {
    if (alpha <= 0.01f) return
    val waist = size * 0.22f
    path.reset()
    path.moveTo(cx, cy - size)
    path.lineTo(cx + waist, cy - waist)
    path.lineTo(cx + size, cy)
    path.lineTo(cx + waist, cy + waist)
    path.lineTo(cx, cy + size)
    path.lineTo(cx - waist, cy + waist)
    path.lineTo(cx - size, cy)
    path.lineTo(cx - waist, cy - waist)
    path.close()
    fillPath(canvas, path, color, alpha)
  }

  protected fun roundRect(
    canvas: Canvas,
    paint: Paint,
    top: Float = 0f,
    bottom: Float = height,
    radius: Float = 4 * dp,
  ) {
    rect.set(0f, top, width, bottom)
    canvas.drawRoundRect(rect, radius, radius, paint)
  }

  protected fun verticalGradient(
    top: Int,
    bottom: Int,
    from: Float = 0f,
    to: Float = height,
  ): Shader = LinearGradient(0f, from, 0f, to, top, bottom, Shader.TileMode.CLAMP)
}

// The attic: a spider's web over the words; the spider runs the line and the web tears behind it.

data class CobwebSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_300

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = CobwebSlice(host, page)
}

internal class CobwebSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private class Glint(val x: Float, val y: Float, val phase: Float)

  private val web = Path()
  private val glints = ArrayList<Glint>()
  private var anchorX = 0f
  private var anchorY = 0f
  private var spiderX = 0f
  private var spiderPhase = 0f
  private var dewEnd = floatArrayOf(0f, 0f)
  private val silk = 0xFFE9E4F5.toInt()
  private val silver = 0xFFD9D4FF.toInt()
  private val spider = 0xFF0C0810.toInt()
  private val eye = 0xFFFF4D4D.toInt()

  override fun prepare(slice: SpoilerSlice) {
    anchorX = width * (0.3f + random.nextFloat() * 0.4f)
    anchorY = -height * 0.7f
    spiderX = width * (0.12f + random.nextFloat() * 0.3f)
    spiderPhase = random.nextFloat() * TWO_PI
    glints.clear()
    web.reset()
    // Spokes fan out from an anchor above the line to the bottom edge and the sides.
    val spokes = (width / (14 * dp)).roundToInt().coerceIn(5, 24)
    val ends = ArrayList<FloatArray>()
    ends += floatArrayOf(0f, height * 0.2f)
    for (k in 0..spokes) ends += floatArrayOf(width * k / spokes, height)
    ends += floatArrayOf(width, height * 0.2f)
    ends.forEach { e ->
      web.moveTo(anchorX, anchorY)
      web.lineTo(e[0], e[1])
    }
    dewEnd = ends[ends.size * 2 / 3]
    // Rings: between neighbouring spokes each strand sags a little toward the anchor.
    val rings = 5
    for (r in 1..rings) {
      val f = r / (rings + 0.4f)
      var prev: FloatArray? = null
      ends.forEach { e ->
        val x = anchorX + (e[0] - anchorX) * f
        val y = anchorY + (e[1] - anchorY) * f
        if (prev == null) {
          web.moveTo(x, y)
        } else {
          val mx = (prev!![0] + x) / 2
          val my = (prev!![1] + y) / 2
          val sag = 0.06f
          web.quadTo(mx + (anchorX - mx) * sag, my + (anchorY - my) * sag, x, y)
          if (random.nextFloat() < 0.35f && y in 0f..height) glints += Glint(x, y, random.nextFloat() * TWO_PI)
        }
        prev = floatArrayOf(x, y)
      }
    }
  }

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    drawWeb(canvas, slice, time, head = -width)
    // The spider hangs on its thread, bobbing, and every few seconds drops and climbs back.
    val cycle = (time + spiderPhase) % 5.2f
    val drop = if (cycle < 0.9f) sin(cycle / 0.9f * PI.toFloat()) * height * 0.3f else 0f
    val sy = height * (0.42f + 0.1f * sin(time * 1.3f + spiderPhase)) + drop
    line(canvas, spiderX, 0f, spiderX, sy - 4 * dp, 0.8f * dp, silk, 0.7f)
    drawSpider(canvas, spiderX, sy, time, running = false)
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    val feather = 10 * dp
    val trail = 60 * dp
    val head = -feather + (width + trail + 2 * feather) * easeInOut(progress)
    pageAhead(canvas, head, feather)
    drawWeb(canvas, slice, time, head)
    // Torn strands curl back from the tear, and the dew falls off them.
    repeat(5) { i ->
      val y = height * (0.12f + i * 0.19f)
      val flap = sin(time * 16f + i * 1.3f) * 3 * dp
      path.reset()
      path.moveTo(head, y)
      path.quadTo(head - 7 * dp, y + flap, head - 11 * dp, y - 5 * dp + flap)
      strokePath(canvas, path, 0.9f * dp, silk, 0.85f)
    }
    glints.forEach { g ->
      val fall = (head - g.x) / (50 * dp)
      if (fall in 0f..1f) circle(canvas, g.x + 2 * dp, g.y + fall * fall * height * 0.9f, 1.1f * dp, WHITE, 1f - fall)
    }
    sweep(canvas, head - trail, trail, keepsAhead = true) {
      sweep(canvas, head, feather, keepsAhead = false) {
        drawGlow(canvas, slice, silver, 0.9f, 5 * dp)
      }
    }
    if (head < width + 12 * dp) drawSpider(canvas, head + 4 * dp, height * 0.5f + sin(time * 30f) * 0.8f * dp, time, running = true)
  }

  private fun drawWeb(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    head: Float,
  ) {
    sweep(canvas, head, 10 * dp, keepsAhead = true) {
      // Moonlight on the words behind the web.
      drawGlow(canvas, slice, CREAM, 0.22f, 4 * dp)
      // The whole web sways a hair in the draught.
      val saved = canvas.save()
      canvas.translate(sin(time * 0.7f) * 1.2f * dp, 0f)
      strokePath(canvas, web, 0.9f * dp, silk, 0.55f)
      canvas.restoreToCount(saved)
      glints.forEach { g ->
        val twinkle = (sin(time * 2.6f + g.phase) + 1f) / 2f
        star(canvas, g.x, g.y, (1f + 1.4f * twinkle) * dp, WHITE, twinkle)
      }
      // A dew drop sliding down one strand, over and over.
      val slide = (time * 0.09f) % 1f
      val dx = anchorX + (dewEnd[0] - anchorX) * (0.35f + 0.65f * slide)
      val dy = anchorY + (dewEnd[1] - anchorY) * (0.35f + 0.65f * slide)
      if (dy in 0f..height) {
        circle(canvas, dx, dy, 1.6f * dp, WHITE, 0.9f)
        circle(canvas, dx - 0.5f * dp, dy - 0.5f * dp, 0.5f * dp, WHITE, 1f)
      }
    }
  }

  private fun drawSpider(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    time: Float,
    running: Boolean,
  ) {
    val s = 2.2f * dp
    val scurry = if (running) sin(time * 40f) * 0.5f else sin(time * 2f) * 0.08f
    for (i in 0 until 4) {
      val spread = 0.5f + i * 0.45f + scurry * (if (i % 2 == 0) 1f else -1f)
      val reach = s * (2.8f - abs(i - 1.5f) * 0.3f)
      line(canvas, cx, cy, cx - cos(spread) * reach, cy - sin(spread) * reach * 0.5f + s * (i - 1.5f) * 0.6f, 0.8f * dp, spider)
      line(canvas, cx, cy, cx + cos(spread) * reach, cy - sin(spread) * reach * 0.5f + s * (i - 1.5f) * 0.6f, 0.8f * dp, spider)
    }
    circle(canvas, cx, cy + s * 0.4f, s * 1.2f, spider)
    circle(canvas, cx, cy - s * 0.7f, s * 0.7f, spider)
    val blink = if ((time * 0.4f) % 1f < 0.04f) 0f else 1f
    circle(canvas, cx - s * 0.25f, cy - s * 0.8f, s * 0.15f, eye, blink)
    circle(canvas, cx + s * 0.25f, cy - s * 0.8f, s * 0.15f, eye, blink)
  }
}

// The crypt: green mist with a shape of the words in it, and will-o'-the-wisps drifting through;
// a cold draught sweeps it along the line.

data class CryptFogSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_300

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = CryptFogSlice(host, page)
}

internal class CryptFogSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private class Bank(val x: Float, val y: Float, val rx: Float, val ry: Float, val speed: Float, val phase: Float, val pale: Boolean)

  private class Mote(val x: Float, val speed: Float, val phase: Float)

  private val banks = ArrayList<Bank>()
  private val motes = ArrayList<Mote>()
  private val wisps = ArrayList<Float>()
  private val mist = 0xFF7FA98C.toInt()
  private val paleMist = 0xFFB7D9BF.toInt()
  private val ghost = 0xFFBBF7D0.toInt()
  private val glowGreen = 0xFF6EE7B7.toInt()
  private val wispCore = 0xFFE8FFF1.toInt()

  override fun prepare(slice: SpoilerSlice) {
    banks.clear()
    motes.clear()
    wisps.clear()
    repeat((width / (9 * dp)).roundToInt().coerceIn(6, 40)) {
      banks +=
        Bank(
          x = random.nextFloat() * width,
          y = height * (0.3f + random.nextFloat() * 0.5f),
          rx = height * (0.6f + random.nextFloat() * 0.5f),
          ry = height * (0.4f + random.nextFloat() * 0.3f),
          speed = (3f + random.nextFloat() * 5f) * dp,
          phase = random.nextFloat() * TWO_PI,
          pale = random.nextFloat() < 0.4f,
        )
    }
    repeat((width / (16 * dp)).roundToInt().coerceIn(2, 18)) {
      motes += Mote(random.nextFloat() * width, height * (0.15f + random.nextFloat() * 0.2f), random.nextFloat() * height)
    }
    repeat(if (width > 60 * dp) 2 else 1) { wisps += random.nextFloat() * TWO_PI }
  }

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    drawMist(canvas, slice, time, head = -width)
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    val feather = 14 * dp
    val trail = 60 * dp
    val head = -feather + (width + trail + 2 * feather) * easeInOut(progress)
    pageAhead(canvas, head, feather)
    drawMist(canvas, slice, time, head)
    sweep(canvas, head - trail, trail, keepsAhead = true) {
      sweep(canvas, head, feather, keepsAhead = false) {
        drawGlow(canvas, slice, glowGreen, 0.9f, 5 * dp)
      }
    }
  }

  private fun wispAt(
    phase: Float,
    t: Float,
  ): FloatArray =
    floatArrayOf(
      width * (0.5f + 0.42f * sin(t * 0.5f + phase)),
      height * (0.5f + 0.28f * sin(t * 0.83f + phase * 2)),
    )

  private fun drawMist(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    head: Float,
  ) {
    sweep(canvas, head, 10 * dp, keepsAhead = true) {
      drawGlow(canvas, slice, ghost, 0.35f, 4.5f * dp)
    }
    val span = width + 2 * height
    banks.forEach { b ->
      var x = ((b.x + time * b.speed) % span) - height
      val y = b.y + sin(time * 0.6f + b.phase) * height * 0.08f
      val breathe = 1f + 0.08f * sin(time * 0.9f + b.phase)
      val ahead = x - head
      if (ahead < 0f) return@forEach
      var squash = 1f
      if (ahead < 40 * dp) {
        x += (1f - ahead / (40 * dp)) * 14 * dp
        squash = 0.7f + 0.3f * (ahead / (40 * dp))
      }
      softEllipse(canvas, x, y, b.rx * breathe, b.ry * squash, if (b.pale) paleMist else mist, 0.9f, core = 0.3f)
    }
    // Cold breath rising out of the mist.
    motes.forEach { m ->
      val y = height - ((time * m.speed + m.phase) % height)
      val x = m.x + sin(time * 1.5f + m.phase) * 3 * dp
      if (x < head) return@forEach
      circle(canvas, x, y, 0.9f * dp, ghost, 0.5f * (y / height))
    }
    // Will-o'-the-wisps wander through the mist with a trail; the draught sends them fleeing.
    wisps.forEach { phase ->
      val at = wispAt(phase, time)
      val flee = ((head + 30 * dp - at[0]) / (30 * dp)).coerceIn(0f, 1f)
      for (k in 4 downTo 0) {
        val p = wispAt(phase, time - k * 0.07f)
        val x = p[0] + flee * flee * width * 0.6f
        val y = p[1] - flee * height * 1.5f
        val a = (1f - k / 5f) * (1f - flee)
        softEllipse(canvas, x, y, (6f - k) * dp, (5f - k * 0.8f) * dp, glowGreen, 0.55f * a, core = 0.3f)
        if (k == 0) circle(canvas, x, y, 1.6f * dp, wispCore, 0.95f * a)
      }
    }
  }
}

// The porch: a pumpkin shell with a carved face glowing from the candle inside; it blinks, and when
// tapped the candle flares, the shell cracks and burns away.

data class LanternSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_250

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = LanternSlice(host, page)
}

internal class LanternSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private val eyes = Path()
  private val mouth = Path()
  private val cracks = ArrayList<FloatArray>()
  private val embers = ArrayList<FloatArray>()
  private var faceX = 0f
  private var eyeY = 0f
  private var flickerPhase = 0f
  private val rindTop = 0xFFC2500F.toInt()
  private val rindBottom = 0xFF6E2206.toInt()
  private val ridge = 0xFF4A1604.toInt()
  private val candle = 0xFFFFD166.toInt()
  private val amber = 0xFFFF9A1F.toInt()
  private lateinit var rind: Shader

  override fun prepare(slice: SpoilerSlice) {
    rind = verticalGradient(rindTop, rindBottom)
    flickerPhase = random.nextFloat() * TWO_PI
    faceX = width / 2
    eyeY = height * 0.38f
    val eye = height * 0.22f
    val gap = min(width * 0.18f, height * 0.5f)
    eyes.reset()
    for (side in intArrayOf(-1, 1)) {
      val cx = faceX + side * gap
      eyes.moveTo(cx, eyeY - eye * 0.6f)
      eyes.lineTo(cx + eye * 0.6f, eyeY + eye * 0.4f)
      eyes.lineTo(cx - eye * 0.6f, eyeY + eye * 0.4f)
      eyes.close()
    }
    mouth.reset()
    val mouthHalf = min(width * 0.32f, height * 1.1f)
    val teeth = 5
    val top = height * 0.62f
    val bottom = height * 0.82f
    mouth.moveTo(faceX - mouthHalf, top)
    for (t in 1..teeth) {
      val x = faceX - mouthHalf + mouthHalf * 2 * t / teeth
      mouth.lineTo(x - mouthHalf / teeth / 2, if (t % 2 == 0) top else top + (bottom - top) * 0.35f)
      mouth.lineTo(x, top)
    }
    mouth.lineTo(faceX + mouthHalf, top)
    mouth.lineTo(faceX + mouthHalf * 0.8f, bottom)
    mouth.lineTo(faceX - mouthHalf * 0.8f, bottom)
    mouth.close()
    cracks.clear()
    repeat(7) {
      val angle = random.nextFloat() * TWO_PI
      cracks += floatArrayOf(angle, (0.5f + random.nextFloat()) * width * 0.5f, (random.nextFloat() - 0.5f) * 0.8f)
    }
    embers.clear()
    repeat(18) { embers += floatArrayOf(random.nextFloat() * TWO_PI, 0.4f + random.nextFloat() * 0.6f, random.nextFloat()) }
  }

  private fun flicker(time: Float): Float = 0.72f + 0.14f * sin(time * 9f + flickerPhase) + 0.14f * sin(time * 23f + flickerPhase * 2)

  /** 1 with the eyes open, 0 shut: a quick blink every few seconds. */
  private fun lids(time: Float): Float {
    val cycle = (time + flickerPhase) % 3.7f
    return if (cycle < 0.22f) abs(cos(cycle / 0.22f * PI.toFloat())) else 1f
  }

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    drawShell(canvas, time, flicker(time), lids(time))
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    val cy = height / 2
    val feather = 22 * dp
    val reach = hypot(width / 2, height / 2) + feather
    // A beat of flare and cracking light, then the shell burns away from the face.
    val burned = easeInOut(((progress - 0.22f) / 0.78f).coerceAtLeast(0f))
    val radius = reach * burned
    bloom(canvas, faceX, cy, radius, feather, keepsInside = false) {
      canvas.drawColor(page)
      drawShell(canvas, time, 1f + (1f - progress) * 0.6f, 1f)
      if (progress < 0.45f) {
        val grow = (progress / 0.3f).coerceAtMost(1f)
        cracks.forEach { c ->
          val len = c[1] * grow
          path.reset()
          path.moveTo(faceX, cy)
          path.lineTo(faceX + cos(c[0]) * len * 0.5f, cy + sin(c[0]) * len * 0.5f + c[2] * height * 0.2f)
          path.lineTo(faceX + cos(c[0]) * len, cy + sin(c[0]) * len)
          strokePath(canvas, path, 1.3f * dp, candle, (1f - progress / 0.45f) * 0.95f)
        }
      }
    }
    if (burned in 0.02f..0.98f) {
      stroke.shader = null
      stroke.strokeWidth = 3 * dp
      stroke.color = candle
      stroke.alpha = (220 * (1f - burned)).roundToInt()
      canvas.drawCircle(faceX, cy, radius - feather * 0.4f, stroke)
      embers.forEach { e ->
        val r = radius * e[1] + burned * 30 * dp
        val x = faceX + cos(e[0]) * r
        val y = cy + sin(e[0]) * r - burned * 18 * dp * e[2]
        circle(canvas, x, y, (0.8f + e[2]) * dp, amber, (1f - burned) * 0.9f)
      }
    }
    bloom(canvas, faceX, cy, radius, feather, keepsInside = true) {
      drawGlow(canvas, slice, amber, (1f - progress / 0.9f).coerceAtLeast(0f), 7 * dp)
      drawGlow(canvas, slice, candle, (1f - progress / 0.5f).coerceAtLeast(0f), 3 * dp)
    }
  }

  private fun drawShell(
    canvas: Canvas,
    time: Float,
    glow: Float,
    open: Float,
  ) {
    fill.shader = rind
    fill.alpha = 255
    roundRect(canvas, fill, radius = height * 0.35f)
    fill.shader = null
    val ridges = (width / (11 * dp)).roundToInt().coerceAtLeast(2)
    for (i in 1 until ridges) {
      softEllipse(canvas, width * i / ridges, height / 2, 2.2f * dp, height * 0.6f, ridge, 0.6f)
    }
    // Candlelight through the carving; the eyes close to a slit when it blinks.
    softEllipse(canvas, faceX, height * 0.55f, width * 0.45f, height * 0.7f, amber, 0.45f * glow.coerceAtMost(1f), core = 0.2f)
    fillPath(canvas, mouth, amber, glow.coerceAtMost(1f))
    fillPath(canvas, mouth, candle, (glow * 0.8f).coerceAtMost(1f))
    val saved = canvas.save()
    canvas.scale(1f, open.coerceAtLeast(0.06f), faceX, eyeY)
    fillPath(canvas, eyes, amber, glow.coerceAtMost(1f))
    fillPath(canvas, eyes, candle, (glow * 0.8f).coerceAtMost(1f))
    if (glow > 1f) fillPath(canvas, eyes, WHITE, (glow - 1f).coerceAtMost(1f))
    canvas.restoreToCount(saved)
  }
}

// The nursery: ghosts in sheets drifting over the words; on tap they shriek and fly off.

data class GhostSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_200

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = GhostSlice(host, page)
}

internal class GhostSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private class Ghost(val x: Float, val w: Float, val phase: Float, val lean: Float)

  private val ghosts = ArrayList<Ghost>()
  private val sheet = Path()
  private val sheetWhite = 0xFFF7F4FF.toInt()
  private val sheetShade = 0xFFCFC8E6.toInt()
  private val eyes = 0xFF201833.toInt()
  private val chill = 0xFFE9E5FF.toInt()
  private lateinit var shade: Shader

  override fun prepare(slice: SpoilerSlice) {
    ghosts.clear()
    val count = (width / (58 * dp)).roundToInt().coerceIn(1, 12)
    val w = min(68 * dp, width / count * 1.25f)
    repeat(count) { i ->
      ghosts += Ghost(width * (i + 0.5f) / count, w, random.nextFloat() * TWO_PI, (random.nextFloat() - 0.5f) * 0.6f)
    }
    shade = verticalGradient(sheetWhite, sheetShade, -height * 0.6f, height * 0.9f)
  }

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    drawGlow(canvas, slice, CREAM, 0.12f, 4 * dp)
    ghosts.forEach { g -> drawGhost(canvas, g, time, rise = 0f, shriek = 0f, alpha = 1f) }
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    drawGlow(canvas, slice, chill, (1f - progress / 0.9f).coerceAtLeast(0f) * 0.9f, 6 * dp)
    ghosts.forEachIndexed { i, g ->
      val own = (progress * 1.3f - i * 0.06f).coerceIn(0f, 1f)
      // A shriek first: the ghost swells with its mouth wide, then shoots up, afterimages behind.
      val shriek = if (own < 0.25f) sin(own / 0.25f * PI.toFloat()) else 0f
      val rise = easeIn(((own - 0.2f) / 0.8f).coerceAtLeast(0f))
      if (rise > 0f) {
        for (k in 2 downTo 1) {
          val back = (rise - k * 0.09f).coerceAtLeast(0f)
          if (back > 0f) drawGhost(canvas, g, time, back, 0f, (1f - own) * (0.35f / k))
        }
      }
      drawGhost(canvas, g, time, rise, shriek, 1f - own)
    }
  }

  private fun drawGhost(
    canvas: Canvas,
    g: Ghost,
    time: Float,
    rise: Float,
    shriek: Float,
    alpha: Float,
  ) {
    if (alpha <= 0.01f) return
    val swirl = sin(rise * 7f + g.phase) * 9 * dp * rise
    val cx = g.x + sin(time * 0.9f + g.phase) * 4 * dp + rise * g.lean * 60 * dp + swirl
    val cy = height * 0.55f + sin(time * 1.6f + g.phase) * 2.5f * dp - rise * height * 2.8f
    val w = g.w * (1f + shriek * 0.18f)
    val top = cy - height * (0.95f + shriek * 0.2f)
    val hem = cy + height * 0.5f
    sheet.reset()
    sheet.moveTo(cx - w / 2, hem)
    sheet.lineTo(cx - w / 2, cy - height * 0.2f)
    // Arms: a bump out on each side, raised when it shrieks.
    sheet.quadTo(cx - w * 0.72f, cy - height * (0.25f + shriek * 0.25f), cx - w * 0.45f, cy - height * 0.42f)
    sheet.cubicTo(cx - w * 0.45f, top, cx + w * 0.45f, top, cx + w * 0.45f, cy - height * 0.42f)
    sheet.quadTo(cx + w * 0.72f, cy - height * (0.25f + shriek * 0.25f), cx + w / 2, cy - height * 0.2f)
    sheet.lineTo(cx + w / 2, hem)
    val waves = 3
    for (k in 0 until waves) {
      val x1 = cx + w / 2 - w * (k + 1) / waves
      val x0 = cx + w / 2 - w * k / waves
      val wobble = sin(time * 3f + g.phase + k) * 2 * dp
      sheet.quadTo((x0 + x1) / 2, hem - height * 0.28f + wobble, x1, hem)
    }
    sheet.close()
    softEllipse(canvas, cx, cy, w * 0.9f, height * 0.9f, sheetWhite, 0.25f * alpha)
    fill.shader = shade
    fill.alpha = (242 * alpha).roundToInt()
    canvas.drawPath(sheet, fill)
    fill.shader = null
    // Eyes that blink now and then, and a mouth that opens for the shriek.
    val blink = ((time * 0.3f + g.phase) % 1f).let { if (it < 0.035f) 0.15f else 1f }
    val eyeY = cy - height * 0.12f
    fill.color = eyes
    fill.alpha = (255 * alpha).roundToInt()
    for (side in intArrayOf(-1, 1)) {
      canvas.drawOval(cx + side * w * 0.17f - 2.2f * dp, eyeY - 3.2f * dp * blink, cx + side * w * 0.17f + 2.2f * dp, eyeY + 3.2f * dp * blink, fill)
    }
    val mouthH = 4.5f * dp + shriek * 7 * dp
    canvas.drawOval(cx - (2.6f + shriek * 2f) * dp, eyeY + 6 * dp, cx + (2.6f + shriek * 2f) * dp, eyeY + 6 * dp + mouthH, fill)
  }
}

// The belfry: bats roosting on the words, one stretching now and then; they scatter on tap.

data class BatsSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_050

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = BatsSlice(host, page)
}

internal class BatsSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private class Bat(val x: Float, val y: Float, val size: Float, val phase: Float, val dx: Float, val dy: Float)

  private val bats = ArrayList<Bat>()
  private val dust = ArrayList<FloatArray>()
  private val wing = Path()
  private val fur = 0xFF2B2140.toInt()
  private val rim = 0xFF8B5CF6.toInt()
  private val eye = 0xFFFF3B3B.toInt()
  private val roost = 0xFF0E0916.toInt()
  private val violet = 0xFFC4B5FD.toInt()
  private val grit = 0xFF8C8299.toInt()

  override fun prepare(slice: SpoilerSlice) {
    bats.clear()
    dust.clear()
    val count = (width / (22 * dp)).roundToInt().coerceIn(2, 18)
    repeat(count) { i ->
      val angle = -PI.toFloat() / 2 + (random.nextFloat() - 0.5f) * 2.4f
      bats +=
        Bat(
          x = width * (i + 0.5f) / count + (random.nextFloat() - 0.5f) * 6 * dp,
          y = height * (0.35f + random.nextFloat() * 0.3f),
          size = (8f + random.nextFloat() * 4f) * dp,
          phase = random.nextFloat() * TWO_PI,
          dx = cos(angle) * (90 + random.nextFloat() * 90) * dp,
          dy = sin(angle) * (90 + random.nextFloat() * 90) * dp,
        )
    }
    repeat(14) { dust += floatArrayOf(random.nextFloat() * width, random.nextFloat() * TWO_PI, 0.5f + random.nextFloat()) }
  }

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    softEllipse(canvas, width / 2, height / 2, width * 0.62f, height * 0.75f, roost, 0.95f, core = 0.55f)
    // Every couple of seconds one bat stretches its wings and shuffles along the roost.
    val turn = (time / 2.4f).toInt()
    val stretching = ((turn * 7919) % bats.size + bats.size) % bats.size
    val within = (time % 2.4f) / 2.4f
    bats.forEachIndexed { i, b ->
      val twitch = 0.25f + 0.08f * sin(time * 3f + b.phase)
      val stretch = if (i == stretching && within < 0.35f) sin(within / 0.35f * PI.toFloat()) else 0f
      val shuffle = if (i == stretching && within < 0.35f) sin(within / 0.35f * TWO_PI) * 2 * dp else 0f
      val blink = ((time * 0.25f + b.phase) % 1f).let { if (it < 0.03f) 0f else 1f }
      drawBat(canvas, b.x + shuffle, b.y - stretch * 2 * dp, b.size, twitch + stretch * 0.65f, 1f, blink)
    }
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    val away = easeOut(progress)
    softEllipse(canvas, width / 2, height / 2, width * 0.62f, height * 0.75f, roost, 0.95f * (1f - progress * 1.4f).coerceAtLeast(0f), core = 0.55f)
    // Dust kicked up off the roost.
    dust.forEach { d ->
      val a = (1f - progress * 1.6f).coerceAtLeast(0f)
      circle(canvas, d[0] + cos(d[1]) * away * 16 * dp, height * 0.5f + sin(d[1]) * away * 10 * dp - away * 6 * dp, d[2] * dp, grit, 0.6f * a)
    }
    drawGlow(canvas, slice, violet, (1f - progress / 0.9f).coerceAtLeast(0f) * 0.9f, 6 * dp)
    bats.forEach { b ->
      val flap = 0.55f + 0.5f * sin(time * 32f + b.phase)
      // Each bat climbs on an arc, with motion trails behind it.
      for (k in 2 downTo 0) {
        val back = (away - k * 0.07f).coerceAtLeast(0f)
        val x = b.x + b.dx * back
        val y = b.y + b.dy * back - 50 * dp * back * (1f - back)
        drawBat(canvas, x, y, b.size, if (k == 0) flap else 0.6f, (1f - progress) * (if (k == 0) 1f else 0.4f / k), 1f)
      }
    }
  }

  /** A bat: body, ears, red eyes and two scalloped wings at [spread] (0 folded, 1 wide). */
  private fun drawBat(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    s: Float,
    spread: Float,
    alpha: Float,
    eyesOpen: Float,
  ) {
    if (alpha <= 0.01f) return
    val lift = (spread - 0.5f) * s * 0.9f
    wing.reset()
    wing.moveTo(cx, cy - s * 0.45f)
    wing.lineTo(cx - s * 0.18f, cy - s * 0.75f)
    wing.lineTo(cx - s * 0.28f, cy - s * 0.35f)
    wing.quadTo(cx - s * 0.9f, cy - s * 0.6f - lift, cx - s * 1.5f, cy - lift)
    wing.quadTo(cx - s * 1.05f, cy + s * 0.05f - lift * 0.3f, cx - s * 0.8f, cy + s * 0.28f)
    wing.quadTo(cx - s * 0.55f, cy + s * 0.05f, cx - s * 0.3f, cy + s * 0.32f)
    wing.quadTo(cx, cy + s * 0.6f, cx + s * 0.3f, cy + s * 0.32f)
    wing.quadTo(cx + s * 0.55f, cy + s * 0.05f, cx + s * 0.8f, cy + s * 0.28f)
    wing.quadTo(cx + s * 1.05f, cy + s * 0.05f - lift * 0.3f, cx + s * 1.5f, cy - lift)
    wing.quadTo(cx + s * 0.9f, cy - s * 0.6f - lift, cx + s * 0.28f, cy - s * 0.35f)
    wing.lineTo(cx + s * 0.18f, cy - s * 0.75f)
    wing.close()
    fillPath(canvas, wing, fur, alpha)
    strokePath(canvas, wing, 0.7f * dp, rim, 0.45f * alpha)
    circle(canvas, cx - s * 0.1f, cy - s * 0.3f, s * 0.07f, eye, alpha * eyesOpen)
    circle(canvas, cx + s * 0.1f, cy - s * 0.3f, s * 0.07f, eye, alpha * eyesOpen)
  }
}

// The kitchen: a smear of blood over the words, drips hanging off its ragged edge and letting go;
// a cloth wipes the line clean and the blood runs off its trailing edge.

data class BloodSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_400

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = BloodSlice(host, page)
}

internal class BloodSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private class Drip(val x: Float, val length: Float, val speed: Float, val phase: Float, val thick: Float)

  private class Spatter(val x: Float, val y: Float, val r: Float)

  private val drips = ArrayList<Drip>()
  private val edges = ArrayList<Float>()
  private val spatter = ArrayList<Spatter>()
  private val smear = Path()
  private val red = 0xFFB3121F.toInt()
  private val bright = 0xFFD91E2E.toInt()
  private val dark = 0xFF4A0410.toInt()
  private val gloss = 0xFFFF8A94.toInt()
  private val glow = 0xFFFF6B6B.toInt()
  private lateinit var body: Shader

  override fun prepare(slice: SpoilerSlice) {
    body = verticalGradient(bright, dark, -height * 0.2f, height * 0.9f)
    // The smear: a lumpy top edge and a bottom edge that bulges where the drips hang.
    val bumps = (width / (12 * dp)).roundToInt().coerceIn(3, 30)
    val step = width / bumps
    val tops = FloatArray(bumps + 1) { height * (0.02f + random.nextFloat() * 0.14f) }
    val bottoms = FloatArray(bumps + 1) { height * (0.62f + random.nextFloat() * 0.22f) }
    smear.reset()
    smear.moveTo(-2 * dp, tops[0])
    for (i in 0 until bumps) {
      smear.quadTo((i + 0.5f) * step, tops[i] - height * 0.06f, (i + 1) * step, tops[i + 1])
    }
    smear.lineTo(width + 2 * dp, bottoms[bumps])
    for (i in bumps downTo 1) {
      smear.quadTo((i - 0.5f) * step, bottoms[i] + height * 0.12f, (i - 1) * step, bottoms[i - 1])
    }
    smear.close()
    drips.clear()
    edges.clear()
    repeat((width / (14 * dp)).roundToInt().coerceIn(3, 24)) {
      val x = random.nextFloat() * width
      val i = (x / step).toInt().coerceIn(0, bumps - 1)
      val edge = bottoms[i] + height * 0.06f
      drips += Drip(x, height * (0.12f + random.nextFloat() * 0.28f), 0.07f + random.nextFloat() * 0.09f, random.nextFloat(), (1.4f + random.nextFloat() * 2f) * dp)
      edges += edge
    }
    spatter.clear()
    repeat((width / (9 * dp)).roundToInt().coerceIn(4, 40)) {
      spatter += Spatter(random.nextFloat() * width, height * (0.78f + random.nextFloat() * 0.22f), (0.6f + random.nextFloat() * 1.3f) * dp)
    }
  }

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    drawSmear(canvas, slice, time, head = -width)
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    val feather = 6 * dp
    val trail = 40 * dp
    // The cloth wipes left to right; blood is dragged ahead of it and runs off behind.
    val head = -feather + (width + trail + 2 * feather) * easeInOut(progress)
    pageAhead(canvas, head, feather)
    drawSmear(canvas, slice, time, head)
    // A wet pink streak left behind the cloth that dries, and the words glowing warm in its wake.
    sweep(canvas, head - trail, trail, keepsAhead = true) {
      sweep(canvas, head, feather, keepsAhead = false) {
        fill.shader = null
        fill.color = red
        fill.alpha = 60
        canvas.drawRect(0f, 0f, width, height, fill)
        drawGlow(canvas, slice, glow, 0.9f, 5 * dp)
      }
    }
    // Blood piled up against the cloth's edge, with runnels streaming down off it.
    if (head < width + feather) {
      softEllipse(canvas, head - 5 * dp, height * 0.45f, 7 * dp, height * 0.55f, bright, 0.95f, core = 0.4f)
      repeat(3) { i ->
        val y0 = height * (0.3f + i * 0.2f)
        val run = (12 + i * 6) * dp
        line(canvas, head - 3 * dp, y0, head - 3 * dp - run * 0.3f, y0 + run, 1.6f * dp, red, 0.9f)
        circle(canvas, head - 3 * dp - run * 0.3f, y0 + run, 1.3f * dp, red)
      }
      // The cloth's edge: a pale band of linen.
      line(canvas, head, -2 * dp, head, height + 2 * dp, 3.5f * dp, 0xFFEADFC8.toInt(), 0.9f)
      line(canvas, head + 2.5f * dp, 0f, head + 2.5f * dp, height, 1.5f * dp, 0xFFC9BBA0.toInt(), 0.8f)
    }
  }

  private fun drawSmear(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    head: Float,
  ) {
    sweep(canvas, head, 8 * dp, keepsAhead = true) {
      // A shadow under the smear, the body of it, then the words showing through as darker stains.
      canvas.save()
      canvas.translate(0f, 1.5f * dp)
      fillPath(canvas, smear, dark, 0.7f)
      canvas.restore()
      fill.shader = body
      fill.alpha = 255
      canvas.drawPath(smear, fill)
      fill.shader = null
      drawGlow(canvas, slice, dark, 0.55f, 2.5f * dp)
      // Wet highlight along the upper half, and a moving glint.
      fill.shader = verticalGradient(gloss, gloss and 0x00FFFFFF, height * 0.05f, height * 0.42f)
      fill.alpha = 110
      canvas.drawPath(smear, fill)
      fill.shader = null
      val gx = ((time * 11 * dp) % (width + 40 * dp)) - 20 * dp
      softEllipse(canvas, gx, height * 0.18f, 10 * dp, 2.5f * dp, gloss, 0.35f)
      // Spatter around the foot of the line.
      spatter.forEach { s -> circle(canvas, s.x, s.y, s.r, red, 0.85f) }
    }
    drips.forEachIndexed { i, d ->
      if (d.x < head - 6 * dp) return@forEachIndexed
      val edge = edges.getOrElse(i) { height * 0.7f }
      val cycle = ((time * d.speed + d.phase) % 1f)
      val len = d.length * (0.25f + 0.75f * cycle)
      val tip = edge + len
      // A tapering runnel with a swelling bead, and a highlight on its shoulder.
      path.reset()
      path.moveTo(d.x - d.thick * 0.9f, edge - 3 * dp)
      path.quadTo(d.x - d.thick * 0.25f, edge + len * 0.5f, d.x - d.thick * 0.35f, tip)
      path.lineTo(d.x + d.thick * 0.35f, tip)
      path.quadTo(d.x + d.thick * 0.25f, edge + len * 0.5f, d.x + d.thick * 0.9f, edge - 3 * dp)
      path.close()
      fillPath(canvas, path, red)
      circle(canvas, d.x, tip, d.thick * (0.6f + cycle * 0.5f), bright)
      circle(canvas, d.x - d.thick * 0.25f, tip - d.thick * 0.35f, d.thick * 0.22f, gloss, 0.85f)
      // Near the end of its run the bead lets go, falls, and splashes at the foot of the line.
      if (cycle > 0.8f) {
        val fall = (cycle - 0.8f) / 0.2f
        val y = tip + fall * fall * (height - tip)
        circle(canvas, d.x, y, d.thick * 0.6f, bright, 1f - fall * 0.3f)
        if (fall > 0.8f) {
          val burst = (fall - 0.8f) / 0.2f
          circle(canvas, d.x - 2 * dp * burst, height - 1 * dp, d.thick * 0.3f, red, 1f - burst)
          circle(canvas, d.x + 2.5f * dp * burst, height - 1.5f * dp, d.thick * 0.25f, red, 1f - burst)
        }
      }
    }
  }
}

// The cellar: darkness, one candle flame wandering along the line and a moth circling it; the flame
// flares and lights the words.

data class CandleSpoiler(
  val page: Int = GUEST_BOOK_PAGE,
) : CustomSpoilerOverlay {
  override val revealDurationMillis: Long get() = 1_300

  override fun createSliceOverlay(
    host: SpoilerOverlayHost,
    style: SpoilerStyle,
  ): SpoilerSliceOverlay = CandleSlice(host, page)
}

internal class CandleSlice(
  host: SpoilerOverlayHost,
  page: Int,
) : HauntedSlice(host, page) {
  private class Mote(val x: Float, val y: Float, val size: Float, val phase: Float)

  private val motes = ArrayList<Mote>()
  private val flame = Path()
  private var wander = 0f
  private val shadow = 0xFF05030A.toInt()
  private val warm = 0xFFFFC56B.toInt()
  private val hot = 0xFFFFF1C2.toInt()
  private val ember = 0xFFFF8C42.toInt()
  private val smoke = 0xFF9A8FA8.toInt()
  private val moth = 0xFFD9CBA3.toInt()

  override fun prepare(slice: SpoilerSlice) {
    wander = random.nextFloat() * TWO_PI
    motes.clear()
    repeat((width / (8 * dp)).roundToInt().coerceIn(6, 50)) {
      motes += Mote(random.nextFloat() * width, random.nextFloat() * height, (0.5f + random.nextFloat() * 0.9f) * dp, random.nextFloat() * TWO_PI)
    }
  }

  private fun flameX(time: Float) = width * (0.5f + 0.32f * sin(time * 0.35f + wander))

  private fun flameY(time: Float) = height * (0.5f + 0.12f * sin(time * 0.55f + wander * 2))

  override fun drawIdle(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
  ) {
    val fx = flameX(time)
    val fy = flameY(time)
    drawShadows(canvas, time, 1f)
    drawMotes(canvas, time, fx, fy, reach = 44 * dp, lit = 0f)
    drawFlame(canvas, fx, fy, time, swell = 0f, presence = 1f)
    drawMoth(canvas, fx, fy, time, flee = 0f)
  }

  override fun drawRevealing(
    canvas: Canvas,
    slice: SpoilerSlice,
    time: Float,
    progress: Float,
  ) {
    val fx = flameX(time)
    val fy = flameY(time)
    val feather = 30 * dp
    val reach = hypot(width, height) + feather
    val bloomed = easeInOut(progress)
    val radius = reach * bloomed
    bloom(canvas, fx, fy, radius, feather, keepsInside = false) {
      canvas.drawColor(page)
      drawShadows(canvas, time, 1f)
    }
    bloom(canvas, fx, fy, radius, feather, keepsInside = true) {
      drawGlow(canvas, slice, warm, (1f - progress / 0.85f).coerceAtLeast(0f), 6 * dp)
    }
    if (bloomed in 0.02f..0.98f) {
      stroke.shader = null
      stroke.strokeWidth = 3 * dp
      stroke.color = hot
      stroke.alpha = (180 * (1f - bloomed)).roundToInt()
      canvas.drawCircle(fx, fy, radius - feather * 0.5f, stroke)
    }
    drawMotes(canvas, time, fx, fy, reach = 44 * dp, lit = bloomed)
    // The flame flares before the light takes the room.
    val flare = if (progress < 0.18f) sin(progress / 0.18f * PI.toFloat()) else 0f
    drawFlame(canvas, fx, fy, time, swell = bloomed + flare * 0.25f, presence = (1f - (bloomed - 0.5f) * 2f).coerceIn(0f, 1f))
    drawMoth(canvas, fx, fy, time, flee = easeIn(progress))
  }

  /** Darkness as overlapping breathing shadows, so there is no sheet with edges. */
  private fun drawShadows(
    canvas: Canvas,
    time: Float,
    alpha: Float,
  ) {
    val count = (width / (18 * dp)).roundToInt().coerceAtLeast(2)
    val slot = width / count
    for (i in 0 until count) {
      val cx = slot * (i + 0.5f) + slot * 0.15f * sin(time * 0.4f + i)
      val breathe = 1f + 0.08f * sin(time * 0.9f + i * 1.7f)
      // Wide enough to overlap into one bank, but the clouds at the ends fade before the edge,
      // so the slice's box never shows as a hard corner.
      val rx = minOf(slot * 2.2f * breathe, max(slot, cx), max(slot, width - cx))
      softEllipse(canvas, cx, height / 2, rx, height * 0.5f * breathe, shadow, 0.92f * alpha, core = 0.5f)
    }
  }

  private fun drawMotes(
    canvas: Canvas,
    time: Float,
    fx: Float,
    fy: Float,
    reach: Float,
    lit: Float,
  ) {
    motes.forEach { m ->
      val x = m.x + sin(time * 0.6f + m.phase) * 3 * dp
      val y = m.y + cos(time * 0.5f + m.phase) * 2 * dp
      val near = (1f - hypot(x - fx, y - fy) / reach).coerceAtLeast(0f)
      val a = max(lit, near) * (0.5f + 0.5f * sin(time * 3f + m.phase)) * 0.9f
      circle(canvas, x, y, m.size, warm, a)
    }
  }

  private fun drawFlame(
    canvas: Canvas,
    fx: Float,
    fy: Float,
    time: Float,
    swell: Float,
    presence: Float,
  ) {
    if (presence <= 0.01f) return
    val breath = 0.9f + 0.1f * sin(time * 2.2f) + 0.05f * sin(time * 13f)
    val halo = (30 * dp) * breath + swell * hypot(width, height)
    softEllipse(canvas, fx, fy, halo, halo * 0.8f, warm, 0.55f * presence, core = 0.08f)
    softEllipse(canvas, fx, fy, halo * 0.35f, halo * 0.3f, hot, 0.7f * presence, core = 0.2f)
    // A thread of smoke rising off the tip.
    val h = 6 * dp * breath
    path.reset()
    path.moveTo(fx, fy - h)
    for (k in 1..4) {
      val yy = fy - h - k * 3.5f * dp
      path.lineTo(fx + sin(time * 2.5f + k * 1.4f) * (1f + k * 0.8f) * dp, yy)
    }
    strokePath(canvas, path, 1f * dp, smoke, 0.3f * presence)
    // The flame itself: a teardrop leaning with the draught.
    val lean = sin(time * 5f) * 1.2f * dp
    flame.reset()
    flame.moveTo(fx, fy - h)
    flame.quadTo(fx + h * 0.55f + lean, fy - h * 0.2f, fx, fy + h * 0.45f)
    flame.quadTo(fx - h * 0.55f + lean, fy - h * 0.2f, fx, fy - h)
    flame.close()
    fillPath(canvas, flame, ember, presence)
    val saved = canvas.save()
    canvas.scale(0.55f, 0.6f, fx, fy + h * 0.1f)
    fillPath(canvas, flame, hot, presence)
    canvas.restoreToCount(saved)
  }

  /** A moth circling the flame; it flies off when the light floods the room. */
  private fun drawMoth(
    canvas: Canvas,
    fx: Float,
    fy: Float,
    time: Float,
    flee: Float,
  ) {
    if (flee >= 1f) return
    val r = 13 * dp
    val mx = fx + cos(time * 2.3f + wander) * r + flee * 60 * dp
    val my = fy + sin(time * 3.1f + wander) * r * 0.55f - flee * 70 * dp
    val flap = abs(sin(time * 22f))
    val s = 2.4f * dp
    val a = (1f - flee) * 0.9f
    circle(canvas, mx, my, s * 0.35f, moth, a)
    for (side in intArrayOf(-1, 1)) {
      path.reset()
      path.moveTo(mx, my)
      path.lineTo(mx + side * s * (0.5f + flap * 0.8f), my - s * 0.9f)
      path.lineTo(mx + side * s * (0.7f + flap), my + s * 0.2f)
      path.close()
      fillPath(canvas, path, moth, a * (0.5f + 0.5f * flap))
    }
  }
}
