package com.swmansion.enriched.markdown.codehighlight

import android.graphics.Color
import com.swmansion.enriched.markdown.styles.StyleConfig
import com.swmansion.enriched.markdown.styles.StyleExtensionKey

/**
 * Per-token foreground colors for highlighted code blocks, as ARGB. A type without a color keeps
 * the code block's own text color.
 *
 * A style stored under [CodeHighlightStyleKey] is laid over the default palette rather than
 * replacing it, as on iOS: its colors win, and a type it leaves null takes the palette's color.
 * The palette is [githubDark] on a dark code block background and [githubLight] otherwise.
 */
data class CodeHighlightStyle(
  val keyword: Int? = null,
  val operator: Int? = null,
  val punctuation: Int? = null,
  val string: Int? = null,
  val number: Int? = null,
  val constant: Int? = null,
  val comment: Int? = null,
  val function: Int? = null,
  val type: Int? = null,
  val variable: Int? = null,
  val property: Int? = null,
  val tag: Int? = null,
  val attribute: Int? = null,
  val embedded: Int? = null,
) {
  /** The color for [type], or null when this style leaves it unset. */
  operator fun get(type: SyntaxTokenType): Int? =
    when (type) {
      SyntaxTokenType.KEYWORD -> keyword
      SyntaxTokenType.OPERATOR -> operator
      SyntaxTokenType.PUNCTUATION -> punctuation
      SyntaxTokenType.STRING -> string
      SyntaxTokenType.NUMBER -> number
      SyntaxTokenType.CONSTANT -> constant
      SyntaxTokenType.COMMENT -> comment
      SyntaxTokenType.FUNCTION -> function
      SyntaxTokenType.TYPE -> this.type
      SyntaxTokenType.VARIABLE -> variable
      SyntaxTokenType.PROPERTY -> property
      SyntaxTokenType.TAG -> tag
      SyntaxTokenType.ATTRIBUTE -> attribute
      SyntaxTokenType.EMBEDDED -> embedded
    }

  /** This style's colors, with [fallback]'s for every type this one leaves unset. */
  fun over(fallback: CodeHighlightStyle): CodeHighlightStyle = of { this[it] ?: fallback[it] }

  companion object {
    // GitHub's palettes, the same values iOS uses. Operators, punctuation, variables and embedded
    // code have no color of their own in either and keep the block's.
    private val GITHUB_LIGHT =
      CodeHighlightStyle(
        keyword = opaque(0xCF222E),
        string = opaque(0x0A3069),
        number = opaque(0x0550AE),
        constant = opaque(0x0550AE),
        comment = opaque(0x6E7781),
        function = opaque(0x8250DF),
        type = opaque(0x953800),
        property = opaque(0x0550AE),
        tag = opaque(0x116329),
        attribute = opaque(0x0550AE),
      )

    private val GITHUB_DARK =
      CodeHighlightStyle(
        keyword = opaque(0xFF7B72),
        string = opaque(0xA5D6FF),
        number = opaque(0x79C0FF),
        constant = opaque(0x79C0FF),
        comment = opaque(0x8B949E),
        function = opaque(0xD2A8FF),
        type = opaque(0xFFA657),
        property = opaque(0x79C0FF),
        tag = opaque(0x7EE787),
        attribute = opaque(0x79C0FF),
      )

    /** GitHub's light palette, for code on a light background. */
    fun githubLight(): CodeHighlightStyle = GITHUB_LIGHT

    /** GitHub's dark palette, for code on a dark background. */
    fun githubDark(): CodeHighlightStyle = GITHUB_DARK

    /**
     * [githubDark] when white text contrasts with [backgroundColor] more than black text does,
     * [githubLight] otherwise. Alpha is ignored except that a fully transparent background, which
     * says nothing about what shows through it, gets the light palette.
     */
    fun defaultFor(backgroundColor: Int): CodeHighlightStyle =
      if (Color.alpha(backgroundColor) != 0 && Color.luminance(backgroundColor) < DARK_LUMINANCE) GITHUB_DARK else GITHUB_LIGHT

    /** A style with [color]'s result for every token type. */
    internal fun of(color: (SyntaxTokenType) -> Int?): CodeHighlightStyle =
      CodeHighlightStyle(
        keyword = color(SyntaxTokenType.KEYWORD),
        operator = color(SyntaxTokenType.OPERATOR),
        punctuation = color(SyntaxTokenType.PUNCTUATION),
        string = color(SyntaxTokenType.STRING),
        number = color(SyntaxTokenType.NUMBER),
        constant = color(SyntaxTokenType.CONSTANT),
        comment = color(SyntaxTokenType.COMMENT),
        function = color(SyntaxTokenType.FUNCTION),
        type = color(SyntaxTokenType.TYPE),
        variable = color(SyntaxTokenType.VARIABLE),
        property = color(SyntaxTokenType.PROPERTY),
        tag = color(SyntaxTokenType.TAG),
        attribute = color(SyntaxTokenType.ATTRIBUTE),
        embedded = color(SyntaxTokenType.EMBEDDED),
      )

    // Relative luminance at which black and white text have equal WCAG contrast:
    // (L + 0.05) / 0.05 == 1.05 / (L + 0.05).
    private const val DARK_LUMINANCE = 0.179f

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or rgb
  }
}

/** The handle an explicit [CodeHighlightStyle] is stored under in [StyleConfig.extensions]. */
val CodeHighlightStyleKey: StyleExtensionKey<CodeHighlightStyle> =
  StyleExtensionKey("com.swmansion.enriched.markdown.codehighlight:codeHighlight")

/**
 * The colors code blocks are highlighted with: the style stored under [CodeHighlightStyleKey]
 * laid over the default palette for [StyleConfig.codeBlockStyle]'s background.
 */
fun StyleConfig.codeHighlightStyle(): CodeHighlightStyle {
  val palette = CodeHighlightStyle.defaultFor(codeBlockStyle.backgroundColor)
  return get(CodeHighlightStyleKey)?.over(palette) ?: palette
}
