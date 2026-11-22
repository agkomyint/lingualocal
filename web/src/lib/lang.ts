/** The app's languages as ISO 639-1 codes ("th", "en", "my"). Port of Lang.kt. */
export const TH = "th";
export const EN = "en";
export const MY = "my";

const hasMyanmar = (t: string) => /[က-႟]/.test(t);
const hasThai = (t: string) => /[฀-๿]/.test(t);

export const Lang = {
  /** Language of text by its script: Burmese, Thai, otherwise English. Empty for blank text. */
  of(text: string): string {
    if (!text.trim()) return "";
    if (hasMyanmar(text)) return MY;
    if (hasThai(text)) return TH;
    return EN;
  },
  /** The language a piece is translated into, within the pair. Anything outside the pair goes to its second language. */
  other(lang: string, pair: LangPair): string {
    if (lang === pair.a) return pair.b;
    if (lang === pair.b) return pair.a;
    if (lang === "") return "";
    return pair.b;
  },
  /** What a finished piece was translated into, from its language and its translation's script. */
  target(lang: string, translation: string): string {
    switch (lang) {
      case MY: return translation.trim() && !hasThai(translation) ? EN : TH;
      case TH: return hasMyanmar(translation) ? MY : EN;
      case EN: return hasMyanmar(translation) ? MY : TH;
      default: return "";
    }
  },
  name(code: string): string {
    return code === TH ? "Thai" : code === EN ? "English" : code === MY ? "Burmese" : code;
  },
  locale(code: string): string {
    return code === TH ? "th-TH" : code === MY ? "my-MM" : "en-US";
  },
  cloudEngine: (model: string) => `cloud:${model}`,
  DEVICE_ENGINE: "device:chrome",
};

export interface LangPair {
  code: string;
  a: string;
  b: string;
  label: string;
  short: string;
  /** One language (a === b): speech is written down and typed text is read aloud, nothing is translated. */
  transcribeOnly: boolean;
  /** Only Thai ⇄ English can be translated on the device (Chrome's built-in translator, typed text). */
  worksOffline: boolean;
}

const make = (code: string, a: string, b: string): LangPair => ({
  code, a, b,
  label: a === b ? `${Lang.name(a)} only` : `${Lang.name(a)} ⇄ ${Lang.name(b)}`,
  short: a === b ? a.toUpperCase() : `${a.toUpperCase()} ⇄ ${b.toUpperCase()}`,
  transcribeOnly: a === b,
  worksOffline: code === "th-en",
});

export const TH_EN = make("th-en", TH, EN);
export const TH_MY = make("th-my", TH, MY);
export const EN_MY = make("en-my", EN, MY);
export const MY_ONLY = make("my", MY, MY);
export const PAIRS = [TH_EN, TH_MY, EN_MY, MY_ONLY];
export const pairOf = (code?: string | null): LangPair => PAIRS.find((p) => p.code === code) ?? TH_EN;

/** Language colours from the theme. */
export const langColor = (code: string) =>
  code === TH ? "var(--ll-thai)" : code === MY ? "var(--ll-burmese)" : code === EN ? "var(--ll-english)" : "var(--ll-muted)";
