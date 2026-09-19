import { describe, expect, it } from 'vitest';
import { segmenterArticles, urlLegifrance } from './legifrance';

describe('urlLegifrance', () => {
  it('points at the article search, which always resolves to the version in force', () => {
    expect(urlLegifrance('L3121-20')).toBe(
      'https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-20',
    );
  });

  it('normalises the spaced form used when the law is quoted verbatim', () => {
    expect(urlLegifrance('L. 3131-2')).toBe(urlLegifrance('L3131-2'));
  });
});

describe('segmenterArticles', () => {
  it('leaves prose without any citation in a single unlinked segment', () => {
    const segments = segmenterArticles('La charge de travail doit être répartie équitablement.');
    expect(segments).toHaveLength(1);
    expect(segments[0].url).toBeNull();
  });

  it('links the article number and nothing around it', () => {
    const segments = segmenterArticles('Pas de travail de nuit (Code du travail art. L3163-1).');
    expect(segments.map((segment) => segment.text)).toEqual([
      'Pas de travail de nuit (Code du travail art. ',
      'L3163-1',
      ').',
    ]);
    expect(segments[1].url).toBe(urlLegifrance('L3163-1'));
    expect(segments[0].url).toBeNull();
    expect(segments[2].url).toBeNull();
  });

  it('links every article of a description that cites several', () => {
    const segments = segmenterArticles(
      '35 heures (Code du travail art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans).',
    );
    const liens = segments.filter((segment) => segment.url !== null);
    expect(liens.map((segment) => segment.text)).toEqual(['L3162-1', 'D4153-3']);
  });

  it('recognises décret articles too, D and R alike', () => {
    const segments = segmenterArticles("celle de l'art. R3164-2 reste à instruire");
    expect(segments.filter((segment) => segment.url !== null).map((s) => s.text)).toEqual([
      'R3164-2',
    ]);
  });

  it('reproduces the input exactly when the segments are joined back', () => {
    const text =
      'Le repos quotidien est de 11 h pour un majeur (art. L3131-1), 12 h pour un mineur et 14 h avant 16 ans (art. L3164-1).';
    expect(
      segmenterArticles(text)
        .map((segment) => segment.text)
        .join(''),
    ).toBe(text);
  });

  it('does not mistake a plain number, or a word ending in L, for an article', () => {
    const segments = segmenterArticles('48 h par semaine sur 12 semaines, soit 2880 minutes.');
    expect(segments.filter((segment) => segment.url !== null)).toEqual([]);
  });

  it('handles an empty description without producing an empty segment', () => {
    expect(segmenterArticles('')).toEqual([]);
  });
});
