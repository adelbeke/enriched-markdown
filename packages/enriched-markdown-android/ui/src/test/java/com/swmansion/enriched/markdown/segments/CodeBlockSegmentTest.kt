package com.swmansion.enriched.markdown.segments

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.parser.MarkdownASTNode.NodeType
import com.swmansion.enriched.markdown.plugin.PluginSnapshot
import com.swmansion.enriched.markdown.styles.CodeBlockStyle
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.defaultStyle
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.renderSegments
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.styleWithCodeBlock
import com.swmansion.enriched.markdown.test.TestAstFactory.blockquote
import com.swmansion.enriched.markdown.test.TestAstFactory.codeBlock
import com.swmansion.enriched.markdown.test.TestAstFactory.document
import com.swmansion.enriched.markdown.test.TestAstFactory.listItem
import com.swmansion.enriched.markdown.test.TestAstFactory.paragraph
import com.swmansion.enriched.markdown.test.TestAstFactory.text
import com.swmansion.enriched.markdown.test.TestAstFactory.unorderedList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 35])
class CodeBlockSegmentTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  // Robolectric's text measurement is roughly a pixel per character, so the line has to be long in characters.
  private val longLine = "val answer = listOf(1, 2, 3).map { it * 2 }.filter { it > 2 }.joinToString(separator = \", \")".repeat(4)

  private fun fencedCodeBlock(
    content: String,
    language: String = "",
    fenceChar: String = "`",
  ) = MarkdownASTNode(
    NodeType.CodeBlock,
    attributes = mapOf("language" to language, "fenceChar" to fenceChar),
    children = listOf(text(content)),
  )

  private fun newView(
    node: MarkdownASTNode,
    codeBlockStyle: CodeBlockStyle = defaultStyle.codeBlockStyle,
  ): CodeBlockContainerView = CodeBlockContainerView(context, styleWithCodeBlock(codeBlockStyle)).apply { applyCodeBlockNode(node) }

  private fun layOut(
    view: View,
    width: Int,
  ) {
    view.measure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
  }

  private fun scrollViewOf(view: CodeBlockContainerView) = view.getChildAt(0) as HorizontalScrollView

  private fun textViewOf(view: CodeBlockContainerView) = scrollViewOf(view).getChildAt(0) as TextView

  // A. Splitting and rendering

  @Test
  fun rootCodeBlockBecomesItsOwnSegmentInDocumentOrder() {
    val code = codeBlock("val x = 1\n")
    val doc = document(paragraph(text("Before")), code, paragraph(text("After")))

    val segments = splitASTIntoSegments(doc, PluginSnapshot.EMPTY)

    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Text)
    assertEquals(code, (segments[1] as MarkdownSegment.CodeBlock).node)
    assertTrue(segments[2] is MarkdownSegment.Text)
  }

  @Test
  fun codeBlockNestedInListOrBlockquoteStaysInItsTextSegment() {
    val inList = document(unorderedList(listItem(paragraph(text("Item")), codeBlock("a\n"))))
    val inQuote = document(blockquote(codeBlock("b\n")))

    assertTrue(splitASTIntoSegments(inList, PluginSnapshot.EMPTY).single() is MarkdownSegment.Text)
    assertTrue(splitASTIntoSegments(inQuote, PluginSnapshot.EMPTY).single() is MarkdownSegment.Text)
  }

  @Test
  fun codeBlockSignatureIsStableForSameCodeAndDiffersFromOtherKinds() {
    val node = codeBlock("val x = 1\n")
    val signature = (renderSegments(listOf(MarkdownSegment.CodeBlock(node))).single() as RenderedSegment.CodeBlock).signature
    val again = (renderSegments(listOf(MarkdownSegment.CodeBlock(codeBlock("val x = 1\n")))).single()).signature
    val changed = (renderSegments(listOf(MarkdownSegment.CodeBlock(codeBlock("val x = 2\n")))).single()).signature
    val asText = (renderSegments(listOf(MarkdownSegment.Text(listOf(node)))).single()).signature

    assertEquals(signature, again)
    assertNotEquals(signature, changed)
    assertNotEquals(signature, asText)
  }

  // B. Layout

  @Test
  fun longLinesDoNotWrapSoHeightIsIndependentOfWidth() {
    val view = newView(fencedCodeBlock("$longLine\nshort\n"))

    layOut(view, 2000)
    val wideHeight = view.measuredHeight
    layOut(view, 200)

    assertEquals(wideHeight, view.measuredHeight)
    assertEquals(2, textViewOf(view).layout.lineCount)
  }

  @Test
  fun codeWiderThanTheBoxScrollsHorizontally() {
    val view = newView(fencedCodeBlock(longLine))
    layOut(view, 200)

    val scrollView = scrollViewOf(view)
    assertTrue(textViewOf(view).width > scrollView.width)
    assertTrue(scrollView.canScrollHorizontally(1))

    scrollView.scrollTo(50, 0)
    assertTrue(scrollView.canScrollHorizontally(-1))
  }

  @Test
  fun boxFillsTheGivenWidthWhenTheCodeIsShort() {
    val view = newView(fencedCodeBlock("x"))
    layOut(view, 1000)

    assertEquals(1000, view.width)
    assertTrue(!scrollViewOf(view).canScrollHorizontally(1))
  }

  @Test
  fun shortCodeFillsTheBoxSoALongPressAnywhereReachesTheTextView() {
    val view = newView(fencedCodeBlock("x"))
    layOut(view, 1000)

    val scrollView = scrollViewOf(view)
    val textView = textViewOf(view)
    assertEquals(scrollView.width, textView.width)
    assertEquals(scrollView.height, textView.height)
    assertTrue(textView.isLongClickable)
  }

  @Test
  fun codeBlockLineHeightSpacesTheLines() {
    val code = fencedCodeBlock("line 1\nline 2\nline 3")
    val plain = newView(code, defaultStyle.codeBlockStyle.copy(lineHeight = 0f))
    val spaced = newView(code, defaultStyle.codeBlockStyle.copy(lineHeight = 100f))
    layOut(plain, 1000)
    layOut(spaced, 1000)

    val plainLayout = textViewOf(plain).layout
    val spacedLayout = textViewOf(spaced).layout
    assertTrue(plainLayout.getLineBottom(0) < 100)
    for (line in 0 until 3) {
      assertEquals(100, spacedLayout.getLineBottom(line) - spacedLayout.getLineTop(line))
    }
    assertEquals(spacedLayout.height - plainLayout.height, spaced.measuredHeight - plain.measuredHeight)
  }

  @Test
  fun wrapContentParentShrinksTheBoxToTheCode() {
    val view = newView(fencedCodeBlock("x"))
    view.measure(
      View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )

    assertTrue(view.measuredWidth < 1000)
  }

  @Test
  fun codeStartsAtTheLeftInRtl() {
    val view = newView(fencedCodeBlock(longLine))
    view.layoutDirection = View.LAYOUT_DIRECTION_RTL
    layOut(view, 200)

    assertEquals(0, scrollViewOf(view).scrollX)
  }

  @Test
  fun reapplyingANodeKeepsTheScrollPosition() {
    val view = newView(fencedCodeBlock(longLine))
    layOut(view, 200)
    scrollViewOf(view).scrollTo(50, 0)

    view.applyCodeBlockNode(fencedCodeBlock("$longLine\nmore"))
    layOut(view, 200)

    assertEquals(50, scrollViewOf(view).scrollX)
  }

  @Test
  fun segmentMarginsComeFromTheCodeBlockStyle() {
    val view = newView(fencedCodeBlock("x"))

    assertEquals(defaultStyle.codeBlockStyle.marginTop.toInt(), view.segmentMarginTop)
    assertEquals(defaultStyle.codeBlockStyle.marginBottom.toInt(), view.segmentMarginBottom)
  }

  // C. Content and copy output

  @Test
  fun showsTheCodeWithoutTheTrailingNewline() {
    val view = newView(fencedCodeBlock("line 1\nline 2\n"))

    assertEquals("line 1\nline 2", textViewOf(view).text.toString())
  }

  @Test
  fun fencedMarkdownKeepsLanguageAndFenceChar() {
    assertEquals("```kotlin\nval x = 1\n```", CodeBlockContainerView.fencedMarkdown("val x = 1", "kotlin", '`'))
    assertEquals("~~~\nx\n~~~", CodeBlockContainerView.fencedMarkdown("x", null, '~'))
  }

  @Test
  fun fencedMarkdownOutgrowsAFenceInsideTheCode() {
    val markdown = CodeBlockContainerView.fencedMarkdown("```\nnested\n```", "md", '`')

    assertEquals("````md\n```\nnested\n```\n````", markdown)
  }
}
