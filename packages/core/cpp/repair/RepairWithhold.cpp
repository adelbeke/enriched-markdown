// Withholding for streaming tails: syntax that has not finished arriving is
// removed instead of closed, so the prefix renders as the finished document
// will once the next token lands. No reference counterpart; see
// RepairOptions::withholdIncomplete and withholdMath.
#include <vector>

#include "RepairInternal.hpp"

namespace Markdown::RepairHandlers {

using namespace RepairInternal;

namespace {

constexpr std::string_view kOpenerChars = "*_~`=|";

bool isSpaceOrTab(char c) {
  return c == ' ' || c == '\t';
}

std::string_view trimSpaces(std::string_view s) {
  while (!s.empty() && isSpaceOrTab(s.front())) {
    s.remove_prefix(1);
  }
  while (!s.empty() && (isSpaceOrTab(s.back()) || s.back() == '\r')) {
    s.remove_suffix(1);
  }
  return s;
}

// The text without its trailing whitespace. Every rule reads this and erases
// from a position inside it, taking that whitespace with it.
std::string_view body(const RepairContext &ctx) {
  return jsTrimEnd(ctx.text());
}

size_t lastLineStart(std::string_view text) {
  const size_t newline = text.rfind('\n');
  return newline == npos ? 0 : newline + 1;
}

// Erases from `position` together with the spaces before it on its line.
void eraseFrom(RepairContext &ctx, std::string_view text, size_t position) {
  while (position > 0 && isSpaceOrTab(text[position - 1])) {
    --position;
  }
  ctx.erase(position);
}

// Inside an open fence, one or two fence characters alone on the last line
// are a closing fence still being typed; shown, they would be code.
void withholdPartialFence(RepairContext &ctx) {
  const std::string_view text = body(ctx);
  const size_t lineStart = lastLineStart(text);
  size_t depth = 0;
  const std::string_view line = trimSpaces(stripBlockquotes(text.substr(lineStart), depth));
  if (line.empty() || line.size() > 2 || (line[0] != '`' && line[0] != '~') ||
      line.find_first_not_of(line[0]) != npos) {
    return;
  }
  ctx.erase(lineStart);
}

// A last line that is only a block marker: a bullet or number, up to six
// `#`, a setext underline or a fence shorter than three, a blockquote chain,
// or an admonition title or task checkbox still being typed. Rendered, it
// would be an empty item or heading until its content arrives.
bool isMarkerOnlyLine(std::string_view line) {
  size_t depth = 0;
  const std::string_view rest = trimSpaces(stripBlockquotes(line, depth));
  if (rest.empty()) {
    return depth > 0;
  }
  const char first = rest[0];
  if ((first == '#' || first == '=' || first == '-' || first == '`' || first == '~') &&
      rest.find_first_not_of(first) == npos) {
    return rest.size() <= static_cast<size_t>(first == '#' ? 6 : 2);
  }
  if (depth > 0 && startsWith(rest, "[!") && rest.find(']') == npos) {
    return true;
  }
  const size_t marker = skipListMarker(rest);
  if (marker == npos) {
    return false;
  }
  const std::string_view item = rest.substr(marker);
  if (item.empty()) {
    return true;
  }
  if (!isSpaceOrTab(item[0])) {
    return false;
  }
  const std::string_view checkbox = jsTrimStart(item);
  return checkbox == "[" || checkbox == "[x" || checkbox == "[X";
}

void withholdMarkerLine(RepairContext &ctx) {
  const std::string_view text = body(ctx);
  const size_t lineStart = lastLineStart(text);
  if (!text.empty() && isMarkerOnlyLine(text.substr(lineStart))) {
    ctx.erase(lineStart);
  }
}

// Three or more backticks or tildes that open their line are a fence.
bool isFenceLine(std::string_view text, size_t start) {
  if (text.size() - start < 3 || (text[start] != '`' && text[start] != '~') ||
      text.find_first_not_of(text[start], start) != npos) {
    return false;
  }
  return lineBefore(text, start).find_first_not_of(" \t>") == npos;
}

// A delimiter run at the very end with nothing after it: `Hello **`. It
// cannot close anything, since a closer never follows whitespace, and there
// is no content to open around yet.
void withholdEmptyOpener(RepairContext &ctx) {
  const std::string_view text = body(ctx);
  size_t start = text.size();
  while (start > 0 && kOpenerChars.find(text[start - 1]) != npos) {
    --start;
  }
  if (start == text.size() || (start > 0 && !isJsWhitespace(codePointBefore(text, start)))) {
    return;
  }
  if (ctx.code().inside(start) || isFenceLine(text, start)) {
    return;
  }
  eraseFrom(ctx, text, start);
}

// A trailing `![label]` whose source has not started. An image without a
// source is nothing to show, as `![label](partial` already is.
void withholdImageLabel(RepairContext &ctx) {
  const std::string_view text = body(ctx);
  if (text.empty() || text.back() != ']') {
    return;
  }
  const size_t close = text.size() - 1;
  if (isEscaped(text, close) || ctx.code().inside(close)) {
    return;
  }
  const size_t open = findMatchingOpeningBracket(text, close);
  if (open == npos || open == 0 || text[open - 1] != '!' || isEscaped(text, open - 1) || ctx.code().inside(open) ||
      !ctx.openerCanStillClose(open)) {
    return;
  }
  eraseFrom(ctx, text, open - 1);
}

// Math cannot cross a table cell, so on a table row only the last cell is in scope.
size_t mathScopeStart(RepairContext &ctx, std::string_view text) {
  size_t start = ctx.lastBlockStart();
  const size_t lineStart = lastLineStart(text);
  size_t depth = 0;
  const std::string_view line = jsTrimStart(stripBlockquotes(text.substr(lineStart), depth));
  if (line.empty() || line[0] != '|') {
    return start;
  }
  for (size_t i = lineStart; i < text.size(); ++i) {
    if (text[i] == '|' && !isEscaped(text, i) && !ctx.code().inside(i)) {
      start = i + 1;
    }
  }
  return start;
}

// An unclosed `$…$` or `$$…$$` is cut at its opener: a half-written formula
// renders wrong whether it is closed or shown raw.
void withholdUnclosedMath(RepairContext &ctx, bool inlineDollar, bool displayDollar) {
  const std::string_view text = body(ctx);
  if (text.find('$') == npos) {
    return;
  }
  struct Opener {
    size_t index;
    size_t length;
  };
  std::vector<Opener> openers;
  size_t i = mathScopeStart(ctx, text);
  while (i < text.size()) {
    if (text[i] != '$' || isEscaped(text, i) || ctx.code().inside(i)) {
      ++i;
      continue;
    }
    const DollarRun run = dollarRunAt(text, i);
    if ((run.length == 1 && inlineDollar) || (run.length == 2 && displayDollar)) {
      if (run.canClose && !openers.empty() && openers.back().length == run.length) {
        openers.clear();
      } else if (run.canOpen) {
        openers.push_back({i, run.length});
      }
    }
    i += run.length;
  }
  if (!openers.empty()) {
    eraseFrom(ctx, text, openers.back().index);
  }
}

} // namespace

// The rules erase only, so repeating them until the text stops changing
// terminates; the repeat matters because one rule can expose another's case
// (`- **` is a bare bullet once its empty opener is gone).
void withhold(RepairContext &ctx, const RepairOptions &options) {
  if (endsInsideFence(ctx.text())) {
    if (options.withholdIncomplete) {
      withholdPartialFence(ctx);
    }
    return;
  }
  const bool math = options.withholdMath && (options.inlineMath || options.displayMath);
  size_t size = 0;
  do {
    size = ctx.text().size();
    if (math) {
      withholdUnclosedMath(ctx, options.inlineMath, options.displayMath);
    }
    if (options.withholdIncomplete) {
      withholdImageLabel(ctx);
      withholdEmptyOpener(ctx);
      withholdMarkerLine(ctx);
    }
  } while (ctx.text().size() != size);
}

} // namespace Markdown::RepairHandlers
