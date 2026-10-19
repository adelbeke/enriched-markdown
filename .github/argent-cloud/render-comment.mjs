// Builds the text of the pull request comment for an Argent Cloud run: the
// report the agent wrote, and a footer pointing at the run. `argent-cloud
// comment` posts it and puts the session's screenshots inline under it, so the
// images never pass through here.
//
// The report is a model's prose, shaped by the pull request's own title, body
// and diff, so it is the same class of input render-prompt.mjs handles and it
// gets the same treatment: fenced with a backtick run longer than any inside
// it, never interpolated into markup. Rendering it as markdown instead would
// let a pull request author draw a convincing "approved by a maintainer" block,
// or an <img> that reports a reader's address to a third party, inside a comment
// that looks like it came from CI.
//
// Usage:
//   node render-comment.mjs --report <f> --has-screenshots <true|false>
//                           --number <n> --run-url <url> --artifact <name>
//                           [--session <id>] --out <f>

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

// GitHub rejects a comment body over 65536 characters, and the screenshots the
// CLI appends count against the same limit. The report is the only part this
// script writes that can grow without bound, so it is capped well short of it.
const REPORT_LIMIT = 50000;

function arg(name, { required = true } = {}) {
  const index = process.argv.indexOf(`--${name}`);
  const value = index === -1 ? undefined : process.argv[index + 1];
  if (required && (value === undefined || value.startsWith('--'))) {
    throw new Error(`--${name} is required`);
  }
  return value && value.startsWith('--') ? undefined : value;
}

/**
 * Wraps text in a fence one backtick longer than the longest run it contains,
 * so closing the fence early is impossible rather than merely discouraged.
 * Same rule as render-prompt.mjs, for the same reason.
 */
function fence(text, info = 'text') {
  const runs = [...text.matchAll(/`+/g)].map((match) => match[0].length);
  const bar = '`'.repeat(Math.max(3, Math.max(0, ...runs) + 1));
  return `${bar}${info}\n${text}\n${bar}`;
}

const reportPath = arg('report');
const hasScreenshots = arg('has-screenshots') === 'true';
const number = arg('number');
const runUrl = arg('run-url');
const artifact = arg('artifact');
const session = arg('session', { required: false }) || 'unknown';
const outPath = arg('out');

// A session that died before writing anything still gets a comment, because
// "the agent produced nothing" is a result a maintainer needs to see.
let report = '';
try {
  report = readFileSync(reportPath, 'utf8').trim();
} catch {
  report = '';
}
if (report.length > REPORT_LIMIT) {
  report = `${report.slice(0, REPORT_LIMIT)}\n[truncated - the full report is output.log in the ${artifact} artifact]`;
}

const lines = [`### Argent Cloud - #${number}`, ''];

if (!hasScreenshots) {
  lines.push(
    '> [!WARNING]',
    '> The agent archived no screenshots. Its report may still cite them by number;',
    '> those numbers refer to images that were not saved.',
    ''
  );
}

lines.push(
  report
    ? fence(report)
    : '_The agent produced no report. The step log in the run has whatever it managed to print._',
  '',
  `<sub>Session <code>${session}</code> · <a href="${runUrl}">run log</a> · the screenshots archive and the agent's turn-by-turn commentary are in the <code>${artifact}</code> artifact (14 days)</sub>`
);

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, `${lines.join('\n')}\n`);
console.log(`Wrote ${outPath}: ${report.length} chars of report, screenshots ${hasScreenshots ? 'follow' : 'missing'}`);
