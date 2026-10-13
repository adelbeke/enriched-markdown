package com.swmansion.enriched.markdown.segments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.appcompat.widget.AppCompatTextView
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.spans.LineHeightSpan
import com.swmansion.enriched.markdown.styles.CodeBlockStyle
import com.swmansion.enriched.markdown.styles.StyleConfig
import com.swmansion.enriched.markdown.utils.text.TypefaceUtils
import com.swmansion.enriched.markdown.utils.text.span.SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE
import com.swmansion.enriched.markdown.utils.text.view.SelectionMenuConfig
import com.swmansion.enriched.markdown.views.ContextMenuPopup
import kotlin.math.ceil

/**
 * Block segment view for a root-level fenced code block: the code never wraps and
 * scrolls horizontally inside the block's box instead. Code blocks nested in a list
 * or blockquote stay in their text segment, drawn by CodeBlockSpan, and wrap.
 */
class CodeBlockContainerView(
  context: Context,
  styleConfig: StyleConfig,
) : FrameLayout(context),
  BlockSegmentView {
  private val codeBlockStyle: CodeBlockStyle = styleConfig.codeBlockStyle

  override val segmentMarginTop: Int get() = codeBlockStyle.marginTop.toInt()
  override val segmentMarginBottom: Int get() = codeBlockStyle.marginBottom.toInt()

  var selectionMenuConfig: SelectionMenuConfig = SelectionMenuConfig()

  private val borderWidth = ceil(codeBlockStyle.borderWidth).toInt()

  private var code: String = ""
  private var language: String? = null
  private var fenceChar: Char = '`'

  private val textView =
    AppCompatTextView(context).apply {
      includeFontPadding = false
      setLineSpacing(0f, 1f)
      // Code reads left to right whatever the locale.
      layoutDirection = View.LAYOUT_DIRECTION_LTR
      textDirection = View.TEXT_DIRECTION_LTR
      setTextSize(TypedValue.COMPLEX_UNIT_PX, codeBlockStyle.fontSize)
      typeface = TypefaceUtils.applyStyles(context, codeBlockStyle.fontFamily, codeBlockStyle.fontWeight)
      setTextColor(codeBlockStyle.color)
      val padding = ceil(codeBlockStyle.padding).toInt()
      setPadding(padding, padding, padding, padding)
      setOnLongClickListener { showContextMenu(it) }
    }

  private val scrollView =
    HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = true
      overScrollMode = View.OVER_SCROLL_NEVER
      layoutDirection = View.LAYOUT_DIRECTION_LTR
      // Short code still fills the box, so a long press anywhere in it lands on the text view;
      // the scroll view itself swallows touches and never reports a long press.
      isFillViewport = true
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        horizontalScrollbarThumbDrawable =
          GradientDrawable().apply {
            setColor(scrollbarColor(codeBlockStyle.color))
            cornerRadius = resources.displayMetrics.density * 2
          }
      }
      addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

  init {
    background =
      GradientDrawable().apply {
        setColor(codeBlockStyle.backgroundColor)
        cornerRadius = codeBlockStyle.borderRadius
        if (codeBlockStyle.borderWidth > 0f) {
          setStroke(borderWidth, codeBlockStyle.borderColor)
        }
      }
    // Scrolled code is clipped to the rounded box rather than drawn over its corners.
    clipToOutline = true
    isLongClickable = true
    setOnLongClickListener { showContextMenu(it) }
    addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
  }

  fun applyCodeBlockNode(node: MarkdownASTNode) {
    code = extractCode(node)
    language = node.getAttribute("language")?.takeIf { it.isNotEmpty() }
    fenceChar = node.getAttribute("fenceChar")?.firstOrNull() ?: '`'
    textView.text = buildCodeText(code, codeBlockStyle)
  }

  override fun onMeasure(
    widthSpec: Int,
    heightSpec: Int,
  ) {
    val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    textView.measure(unspecified, unspecified)
    // The box fills the width it is given; only a wrap_content parent shrinks it to the code.
    val width = resolveSize(textView.measuredWidth + borderWidth * 2, widthSpec)
    scrollView.measure(
      MeasureSpec.makeMeasureSpec((width - borderWidth * 2).coerceAtLeast(0), MeasureSpec.EXACTLY),
      unspecified,
    )
    setMeasuredDimension(width, scrollView.measuredHeight + borderWidth * 2)
  }

  override fun onLayout(
    changed: Boolean,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
  ) {
    // Inside the border, so scrolled code never paints over it.
    scrollView.layout(borderWidth, borderWidth, right - left - borderWidth, bottom - top - borderWidth)
  }

  private fun showContextMenu(anchor: View): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    ContextMenuPopup.show(anchor, this) {
      item(ContextMenuPopup.Icon.COPY, context.getString(android.R.string.copy)) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
      }
      if (selectionMenuConfig.copyAsMarkdown) {
        item(ContextMenuPopup.Icon.DOCUMENT, selectionMenuConfig.resolvedCopyAsMarkdownLabel) {
          clipboard.setPrimaryClip(ClipData.newPlainText("Code", fencedMarkdown(code, language, fenceChar)))
        }
      }
    }
    return true
  }

  internal companion object {
    private fun scrollbarColor(color: Int): Int = (color and 0x00FFFFFF) or (0x99 shl 24)

    fun extractCode(node: MarkdownASTNode): String {
      val builder = StringBuilder()

      fun append(current: MarkdownASTNode) {
        builder.append(current.content)
        current.children.forEach { append(it) }
      }
      append(node)
      return builder.toString().trimEnd('\n')
    }

    /** The fence outgrows the longest run of [fenceChar] in [code], so the code cannot close it early. */
    fun fencedMarkdown(
      code: String,
      language: String?,
      fenceChar: Char,
    ): String {
      val longestRun =
        Regex("${Regex.escape(fenceChar.toString())}+").findAll(code).maxOfOrNull { it.value.length } ?: 0
      val fence = fenceChar.toString().repeat(maxOf(3, longestRun + 1))
      return "$fence${language.orEmpty()}\n$code\n$fence"
    }

    fun buildCodeText(
      code: String,
      style: CodeBlockStyle,
    ): SpannableString =
      SpannableString(code).apply {
        if (style.lineHeight > 0f && isNotEmpty()) {
          setSpan(LineHeightSpan(style.lineHeight), 0, length, SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE)
        }
      }
  }
}
