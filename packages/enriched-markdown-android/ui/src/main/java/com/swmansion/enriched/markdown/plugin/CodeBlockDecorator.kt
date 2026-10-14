package com.swmansion.enriched.markdown.plugin

import android.content.Context
import android.text.SpannableStringBuilder
import com.swmansion.enriched.markdown.styles.StyleConfig

/**
 * Adds spans to a code block core has already rendered, e.g. syntax-highlighting colors. Runs on
 * the render thread, once per code block, top-level or nested.
 *
 * The code is `builder.subSequence(start, end)`: what the block displays, read back from the
 * builder rather than the AST. Core's own block spans are set by then, so a character style set
 * here applies after the block's and is not repainted with its text color. Enclosing blockquote
 * and list item spans take a higher priority, so they apply before both.
 *
 * A top-level block is drawn by its own view instead, so its builder holds only the code, without
 * the trailing newline, and no block span: the view's text color is the block color.
 */
@InternalPluginApi
fun interface CodeBlockDecorator {
  /**
   * [language] is the fence's info string language, or null when it has none. The block's colors
   * are in [StyleConfig.codeBlockStyle]; a plugin's own style is in [StyleConfig.extensions].
   */
  fun decorate(
    builder: SpannableStringBuilder,
    start: Int,
    end: Int,
    language: String?,
    style: StyleConfig,
    context: Context,
  )
}
