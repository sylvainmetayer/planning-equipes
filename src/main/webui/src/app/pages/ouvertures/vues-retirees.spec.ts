import { describe, expect, it } from 'vitest';
import { retiredViewTarget } from './vues-retirees';

describe('retiredViewTarget', () => {
  it('lets every other address through', () => {
    expect(retiredViewTarget({}, true)).toBeNull();
    expect(retiredViewTarget({ vue: 'saisie', date: '2026-07-08' }, true)).toBeNull();
    expect(retiredViewTarget({ vue: 'comparer', stands: 'A,B' }, false)).toBeNull();
  });

  it('sends a day to the planning once a plan is computed, with its stand search', () => {
    expect(
      retiredViewTarget({ vue: 'journee', date: '2026-07-08', q: 'Village', filtre: 'x' }, true),
    ).toBe('/journee?date=2026-07-08&q=Village');
  });

  it('narrows the grid to that day before any plan, keeping the other params', () => {
    expect(retiredViewTarget({ vue: 'journee', date: '2026-07-08', stand: 'S1' }, false)).toBe(
      '/ouvertures?stand=S1&du=2026-07-08&au=2026-07-08',
    );
    expect(retiredViewTarget({ vue: 'journee' }, false)).toBe('/ouvertures');
  });

  it('opens the grid on the calendar week, its layers kept', () => {
    expect(
      retiredViewTarget({ vue: 'calendrier', du: '2026-07-11', couches: 'stand,resultat' }, false),
    ).toBe('/ouvertures?du=2026-07-11&couches=stand%2Cresultat');
  });
});
