import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { ScoreSentence } from '../core/models';
import { ScoreReadingPanel, segmentsOf } from './lecture-score';

const VERDICT: ScoreSentence = {
  sujet: 'VERDICT',
  niveau: 'BLOQUANT',
  texte:
    '1 règle impérative n’est pas respectée : le planning ne devrait pas être publié en l’état (« Places pourvues », 3 écarts).',
  liens: [
    {
      texte: 'Places pourvues',
      route: '/constraints',
      parametres: {},
      fragment: 'posteDoitEtrePourvu',
    },
  ],
};

const COVERAGE: ScoreSentence = {
  sujet: 'COUVERTURE',
  niveau: 'BLOQUANT',
  texte: '3 places restent vides, dont 2 le dimanche 12/07.',
  liens: [
    {
      texte: 'dimanche 12/07',
      route: '/journee',
      parametres: { date: '2026-07-12' },
      fragment: null,
    },
  ],
};

describe('segmentsOf', () => {
  it('cuts a sentence at the words of its links, in order', () => {
    const segments = segmentsOf(COVERAGE);
    expect(segments.map((segment) => segment.text).join('')).toBe(COVERAGE.texte);
    expect(segments[1]).toEqual({ text: 'dimanche 12/07', link: COVERAGE.liens[0] });
  });

  it('drops a link whose words the sentence does not carry', () => {
    const segments = segmentsOf({
      ...COVERAGE,
      liens: [{ ...COVERAGE.liens[0], texte: 'lundi' }],
    });
    expect(segments).toEqual([{ text: COVERAGE.texte, link: null }]);
  });
});

describe('ScoreReadingPanel', () => {
  let fixture: ComponentFixture<ScoreReadingPanel>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
    fixture = TestBed.createComponent(ScoreReadingPanel);
  });

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('says there is nothing to read before any solve', async () => {
    await fixture.whenStable();
    expect(element().textContent).toContain('Aucune résolution à lire.');
    expect(element().querySelectorAll('li')).toHaveLength(0);
  });

  it('renders each sentence with its rule and its day as links', async () => {
    fixture.componentRef.setInput('sentences', [VERDICT, COVERAGE]);
    await fixture.whenStable();

    const phrases = element().querySelectorAll('li');
    expect(element().querySelector('ul')?.getAttribute('lang')).toBe('fr');
    expect(phrases).toHaveLength(2);
    expect(phrases[0].className).toContain('lecture-score-bloquant');
    const ruleLink = phrases[0].querySelector('a');
    expect(ruleLink?.textContent).toBe('Places pourvues');
    expect(ruleLink?.getAttribute('href')).toBe('/constraints#posteDoitEtrePourvu');
    expect(phrases[1].querySelector('a')?.getAttribute('href')).toBe('/journee?date=2026-07-12');
  });

  it('names itself once, and not at all when the host already does', async () => {
    fixture.componentRef.setInput('sentences', [VERDICT]);
    await fixture.whenStable();
    expect(element().querySelector('[role="region"]')?.getAttribute('aria-labelledby')).toBe(
      element().querySelector('h2')?.id,
    );

    fixture.componentRef.setInput('hideHeading', true);
    await fixture.whenStable();
    expect(element().querySelector('h2')).toBeNull();
    expect(element().querySelector('[role="region"]')).toBeNull();
  });

  it('says the reading is the last analysed plan while a solve runs', async () => {
    fixture.componentRef.setInput('sentences', [VERDICT]);
    fixture.componentRef.setInput('running', true);
    await fixture.whenStable();
    expect(element().textContent).toContain('dernier plan analysé');
  });
});
