/**
 * Cleans the live preview: the app's own voice heard back, and late words of the piece above, don't belong
 * to the piece being recorded. Only the preview is touched, never the final words. Port of PreviewText.kt.
 */

const WORD = /[\p{L}\p{N}\p{M}]/u;
const MARK = /\p{M}/u;
const LATIN = /[a-z0-9]/;
const OPENING = "\"'([";
/** Ways to step over a misheard letter or two, smallest first. */
const SKIPS = [[1, 1], [1, 0], [0, 1], [2, 2], [2, 1], [1, 2], [2, 0], [0, 2]];
const SEED = 4;
const SHORT = 5;
const LONG = 8;

function units(text: string) {
  const at: number[] = [];
  let ch = "";
  for (let i = 0; i < text.length; i++) {
    if (!WORD.test(text[i])) continue;
    ch += text[i].toLowerCase()[0];
    at.push(i);
  }
  return { ch, at };
}

/** How far a from i and b from j say the same, allowing small mishearings. */
function matchEnd(a: string, i: number, b: string, j: number): [number, number] {
  let x = i, y = j;
  for (;;) {
    while (x < a.length && y < b.length && a[x] === b[y]) { x++; y++; }
    if (x - i < SEED || x === a.length || y === b.length) return [x, y];
    const skip = SKIPS.find(([da, db]) =>
      x + da + SEED <= a.length && a.slice(x + da, x + da + SEED) === b.slice(y + db, y + db + SEED));
    if (!skip) return [x, y];
    x += skip[0];
    y += skip[1];
  }
}

/** raw without what the app just said (spoken) and without the end of the pieces above (above) at its start. */
export function cleanPreview(raw: string, spoken: Iterable<string>, above: Iterable<string>): string {
  const { ch, at } = units(raw);
  const n = ch.length;
  const sources = [
    ...[...spoken].map((t) => ({ text: units(t).ch, own: true })),
    ...[...above].map((t) => ({ text: units(t).ch, own: false })),
  ].filter((s) => s.text);
  if (!n || !sources.length) return raw;
  const insideWord = (k: number) => k > 0 && k < n && at[k] === at[k - 1] + 1 && LATIN.test(ch[k - 1]) && LATIN.test(ch[k]);

  let out = "", from = 0, kept = false, i = 0;
  while (i < n) {
    let end = i;
    if (!MARK.test(ch[i]) && !insideWord(i)) {
      for (const { text, own } of sources) {
        if (kept && !own) continue;
        for (let j = 0; j < text.length; j++) {
          if (text[j] !== ch[i]) continue;
          let [x, y] = matchEnd(ch, i, text, j);
          // The last letters are often still being corrected.
          if (x - i >= SHORT && n - x <= 2 && y < text.length) x = n;
          while (insideWord(x) && x > i) { x--; y--; }
          const len = x - i;
          const growing = x === n, whole = y >= text.length, mostOfIt = len >= text.length * 0.6;
          const hide = kept
            ? len >= LONG && (mostOfIt || (j === 0 && growing))
            : own
              ? (growing && len >= (j === 0 ? 2 : SHORT)) || (whole && len >= (j === 0 ? SEED : SHORT)) || (len >= LONG && mostOfIt)
              : (growing || whole) && len >= SHORT;
          if (hide && x > end) end = x;
        }
      }
    }
    if (end === i) { kept = true; i++; continue; }
    if (kept) out += raw.slice(from, at[i]);
    from = at[end - 1] + 1;
    const next = end < n ? at[end] : raw.length;
    while (from < next && !OPENING.includes(raw[from])) from++;
    i = end;
  }
  if (from === 0) return raw;
  return kept ? out + raw.slice(from) : "";
}
