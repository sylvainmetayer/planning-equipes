// Turns the Code du travail articles cited in our legal texts into links to
// Légifrance, the official publisher of French law.
//
// The citations themselves are written once, in French prose, wherever the
// rule is stated: the backend `ConstraintCatalog` descriptions, the legal
// hints of the Contraintes page, the under-16 warning of the animateur form.
// Nothing there knows about URLs — this file is the single place that maps an
// article number to where the reader can check it.
//
// The URL is *derived* from the article number rather than looked up in a
// table of Légifrance ids on purpose. A `LEGIARTI…` id designates one
// *version* of an article, so a hand-maintained table silently starts
// pointing at a superseded text the day the article is amended, and a
// mistyped id points at an unrelated one. Both failures are invisible from
// here and worse than no link at all, in an app whose whole claim is that its
// hard constraints match the law. The search form below always resolves to
// the version currently in force, and costs nothing for articles cited later.

const RECHERCHE_ARTICLE = 'https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=';

/**
 * Convention collective nationale ÉCLAT (animation, IDCC 1518). Its clauses
 * are numbered `5.2`, `6.2`… — too generic to be recognised in prose the way
 * an article number is, so the link is placed by hand where ÉCLAT is cited.
 */
export const URL_CCN_ECLAT = 'https://www.legifrance.gouv.fr/conv_coll/id/KALICONT000005635177';

/**
 * Matches an article of a French code as our texts write it: a `L`, `D` or `R`
 * prefix (statute, décret, décret en Conseil d'État), then the book/title/
 * chapter digits and the article number. The optional dot and space cover the
 * `L. 3131-2` form used inside verbatim quotations of the law.
 */
const ARTICLE = /\b([LDR])\.?[ \u00a0]?(\d{3,4}-\d{1,3})\b/g;

/** One run of text, linked when it is an article number, plain otherwise. */
export interface LegalSegment {
  readonly text: string;
  /** `null` for ordinary prose, which is most of the string. */
  readonly url: string | null;
}

/**
 * Where to check `article` on Légifrance. Accepts the spaced form as written
 * in quotations (`L. 3131-2`), since that is the same article as `L3131-2`.
 */
export function urlLegifrance(article: string): string {
  return RECHERCHE_ARTICLE + encodeURIComponent(article.replace(/[.\s\u00a0]/g, ''));
}

/**
 * Splits French legal prose into the runs a template renders one after the
 * other, so an article number becomes a link without the surrounding sentence
 * being touched.
 *
 * <p>Only the article number is linked, never the words around it: `art.` and
 * `Code du travail` are prose, and a reader scanning for a number finds a link
 * shaped like the thing they are looking for.</p>
 *
 * <p>Segments are returned back to back with no separator added, so joining
 * their `text` reproduces the input exactly — a rendered description reads
 * identically to the string the backend sent.</p>
 */
export function segmenterArticles(text: string): LegalSegment[] {
  const segments: LegalSegment[] = [];
  let curseur = 0;
  for (const found of text.matchAll(ARTICLE)) {
    const debut = found.index;
    if (debut > curseur) {
      segments.push({ text: text.slice(curseur, debut), url: null });
    }
    segments.push({ text: found[0], url: urlLegifrance(found[0]) });
    curseur = debut + found[0].length;
  }
  if (curseur < text.length) {
    segments.push({ text: text.slice(curseur), url: null });
  }
  return segments;
}
