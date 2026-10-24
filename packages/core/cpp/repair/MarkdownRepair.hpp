// Repair of incomplete markdown for streaming: closes the `**`, `[link](`,
// `$$` and similar constructs left open at the end of a streamed prefix, so
// the parser renders it the way the finished document will.
//
// Attribution: this module is a C++ port of remend 1.3.1 by Vercel, licensed
// under Apache-2.0; see LICENSE.remend in this directory. Below, "the
// reference" means that implementation. Handler names, option names, defaults
// and run order match it, and the tests in packages/core/cpp/tests replay its
// test suite against this port. When changing anything here, run
// `yarn core:test`.
//
// Source layout, mirroring the reference's:
//   MarkdownRepair.cpp   pipeline and public helpers        (index.ts, utils.ts)
//   RepairEmphasis.cpp   bold / italic / bold-italic        (emphasis-handlers.ts)
//   RepairHandlers.cpp   every other handler                (*-handler.ts)
//   RepairInternal.*     UTF-8, JS character classes, scans (utils.ts, code-block-utils.ts)
//
// Deliberate divergences from the reference:
// - Closers for constructs that are open at the same time are inserted in
//   reverse opening order, before any trailing newlines, so the result nests
//   correctly (`**bold `code` becomes `**bold `code`**`). The reference appends
//   each closer as its handler runs.
// - `**` and `__` close on parity even when a single marker follows the
//   opener, so a nested italic no longer keeps them open (`**bold *ital`
//   becomes `**bold *ital***`).
// - The pipeline does not stop after a placeholder link, and the placeholder
//   is itself a closer anchored at `[`, so `**bold [link` gets its `**` after
//   the link and `[**bold link` gets it inside.
// - The htmlTags handler only strips a tag that starts a line. Inline HTML is
//   disabled in our parser, so `<` inside a line is prose.
// - A `[` that starts a task-list item (`- [ `), an admonition (`> [!`), a
//   footnote (`[^`) or the second bracket of a reference link (`][`) is not
//   an incomplete link.
// - Trailing CRLF counts as a trailing newline when placing closers.
// - An opener whose paragraph has already ended is not closed: after a blank
//   line, a heading line, or a later line that starts a fence, heading or
//   list item. An inline span cannot reach across a block boundary, so the
//   closer would be a stray marker in a later block.
// - Closers are anchored at the delimiter that is actually open, found while
//   counting, rather than at the first marker in the text.
// - An escaped `\[` or `\]` is never a link bracket; LLMs stream `\[ … \]`
//   for display math.
// - The single-underscore counter applies the same open/close flanking rule
//   as the asterisk counter (`_a_ b_` does not reopen).
// - The handlers that erase instead of closing (an incomplete link URL, an
//   incomplete image, a tag that starts a line) apply the same block rule:
//   left open in an earlier block, the construct is literal and the text
//   after it is kept. The reference deletes everything after it.
// - A blockquote, a thematic break and a `=` setext underline end a
//   paragraph too, not only a blank line, a fence, a heading or a list item.
// - A `~~~` fence is code, like a ``` fence. The reference knows only the
//   latter, so it closed emphasis and strikethrough inside tilde fences.
// - `**`, `__` and `~~` inside math are not delimiters, as `*` and `_`
//   already were not, and `~~` inside a fence is not one either. With
//   `inlineMath` off a lone `$` is currency, so `$5 and *x` closes; the
//   reference treats every `$` as math.
// - A `-` or `=` line inside an open fence is code, not a setext underline;
//   the reference broke it with a zero-width space.
// - Closers are inserted before trailing whitespace, not only before trailing
//   newlines: `*italic  ` becomes `*italic*  `, which parses, where the
//   reference's `*italic  *` does not close.
// The affected recorded cases in the test data are marked as ours.
//
// Additions with no reference counterpart: closers for spoilers, highlight,
// superscript and subscript (RepairExtensions.cpp), and withholding for
// streaming tails (RepairWithhold.cpp), behind the `withhold` options below.
//
// When wiring this into a renderer, set `inlineMath` and `displayMath` from
// the parser's latexMath flag, so `$` is math only when the parser agrees.
// A renderer that streams token by token turns on `withholdIncomplete`, and
// `withholdMath` with it.
//
// All text is UTF-8. Positions are byte offsets.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace Markdown {

enum class LinkMode { Protocol, TextOnly };

// Same names and defaults as the reference's options object, except that its
// `katex` / `inlineKatex` are `displayMath` / `inlineMath` here: this repo
// calls the feature latexMath everywhere, and "KaTeX" would suggest the web
// renderer.
struct RepairOptions {
  bool bold = true;
  bool boldItalic = true;
  bool comparisonOperators = true;
  bool htmlTags = true;
  bool images = true;
  bool inlineCode = true;
  bool inlineMath = false; // `$…$`; opt-in upstream too, since `$` is ambiguous with currency
  bool italic = true;
  bool displayMath = true; // `$$…$$`
  bool links = true;
  bool setextHeadings = true;
  bool singleTilde = true;
  bool strikethrough = true;
  LinkMode linkMode = LinkMode::Protocol;

  // Closers for md4c extensions the reference does not have. Spoilers are
  // always on in our parser; wire the others from the parser flags. With
  // subscripts on, the single-tilde escape is skipped since `~x~` is markup.
  bool spoilers = true;
  bool highlight = false;
  bool superscript = false;
  bool subscript = false;

  // Streaming tails. Some half-written syntax has no sensible closer: a
  // delimiter with nothing after it yet, a line that is only a list bullet,
  // heading hashes or blockquote marker, a task checkbox or admonition title
  // still being typed, a closing fence still being typed, an image label
  // without a source. With `withholdIncomplete` on, these are removed, so the
  // prefix renders as the finished document will once the next token lands,
  // instead of flashing a bare marker. `withholdMath` does the same for an
  // unclosed `$…$` or `$$…$$` (whichever of inlineMath / displayMath is on):
  // it is cut at its opener instead of closed, since a half-written formula
  // renders wrong either way. It also makes every handler read `$` the way
  // our parser does (MathLookup with code): a `$` in code or next to a
  // digit or space is not a delimiter, so `$5 and **x` still closes, and
  // `_` inside a closed `$…$` is not emphasis. Both default off, for parity
  // with the reference.
  bool withholdIncomplete = false;
  bool withholdMath = false;
};

// Equivalent to the reference's top-level function.
std::string repairInlineMarkdown(std::string_view markdown, const RepairOptions &options);
void repairInlineMarkdownInPlace(std::string &markdown, const RepairOptions &options);

// Placeholder URL substituted for an incomplete link in LinkMode::Protocol.
// Renderers must treat links with this URL as inert.
inline constexpr std::string_view kIncompleteLinkUrl = "streamdown:incomplete-link";

// Sentinel for "no character here" in the code point helpers below.
inline constexpr uint32_t kNoCodePoint = 0xFFFFFFFFu;

// Helpers with the same semantics as the reference's public exports.
bool isWordChar(uint32_t codePoint);
bool isWithinCodeBlock(std::string_view text, size_t position);
bool isWithinMathBlock(std::string_view text, size_t position);
bool isWithinLinkOrImageUrl(std::string_view text, size_t position);

} // namespace Markdown
