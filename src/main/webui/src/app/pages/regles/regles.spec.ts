import { describe, expect, it } from 'vitest';
import { ConstraintView, ParametresLegaux } from '../../core/models';
import {
  SEUILS,
  articlesOf,
  hasChanges,
  mergeSettings,
  ongletOfRule,
  readOngletRegles,
  rulesOfTab,
  shownValue,
  storedValue,
} from './regles';

function rule(overrides: Partial<ConstraintView>): ConstraintView {
  return {
    name: 'r',
    niveau: 'HARD',
    categorie: 'Affectation',
    description: '',
    actif: true,
    protegee: false,
    legale: false,
    dosable: false,
    poids: 1,
    score: null,
    matchCount: null,
    violations: [],
    postesEvalues: null,
    plancher: null,
    references: [],
    ...overrides,
  };
}

describe('regles', () => {
  it('reads the three tabs, and nothing else', () => {
    expect(readOngletRegles('legal')).toBe('legal');
    expect(readOngletRegles('qualite')).toBe('qualite');
    expect(readOngletRegles('calcul')).toBe('calcul');
    expect(readOngletRegles('legaux')).toBeNull();
    expect(readOngletRegles(null)).toBeNull();
  });

  it('lists every hard rule on « Légal » and the others on « Qualité »', () => {
    expect(ongletOfRule({ niveau: 'HARD' })).toBe('legal');
    expect(ongletOfRule({ niveau: 'MEDIUM' })).toBe('qualite');
    expect(ongletOfRule({ niveau: 'SOFT' })).toBe('qualite');
  });

  it('orders a tab by category, the law first, and keeps the catalogue order inside one', () => {
    const rules = [
      rule({ name: 'affectation', categorie: 'Affectation' }),
      rule({ name: 'mineurs1', categorie: 'Légal (mineurs)' }),
      rule({ name: 'travail', categorie: 'Légal (temps de travail)' }),
      rule({ name: 'mineurs2', categorie: 'Légal (mineurs)' }),
      rule({ name: 'qualite', niveau: 'MEDIUM', categorie: "Qualité d'organisation" }),
    ];

    expect(rulesOfTab(rules, 'legal').map((each) => each.name)).toEqual([
      'travail',
      'mineurs1',
      'mineurs2',
      'affectation',
    ]);
    expect(rulesOfTab(rules, 'qualite').map((each) => each.name)).toEqual(['qualite']);
  });

  it('finds the articles a description cites, once each', () => {
    expect(
      articlesOf('Au plus 48 h (art. L3121-20) ; 35 h pour un mineur (L3162-1, L3121-20).'),
    ).toEqual(['L3121-20', 'L3162-1']);
    expect(articlesOf('Chaque place ouverte doit être pourvue.')).toEqual([]);
  });

  describe('a setting on its row', () => {
    const legaux = {
      dureeHebdomadaireMaxMinutes: 48 * 60,
      coupureRepasMidiDebut: '12:00:00',
    } as unknown as Record<string, string | number | null>;

    it('shows a weekly cap in hours and a window as HH:mm', () => {
      expect(
        shownValue(
          SEUILS['dureeHebdomadaireMaxMinutes'],
          'dureeHebdomadaireMaxMinutes',
          legaux,
          {},
        ),
      ).toBe(48);
      expect(shownValue(SEUILS['coupureRepasMidi'], 'coupureRepasMidiDebut', legaux, {})).toBe(
        '12:00',
      );
    });

    it('shows what was typed over what is stored', () => {
      expect(
        shownValue(SEUILS['dureeHebdomadaireMaxMinutes'], 'dureeHebdomadaireMaxMinutes', legaux, {
          dureeHebdomadaireMaxMinutes: 44 * 60 + 30,
        }),
      ).toBe(44.5);
    });

    it('stores hours as minutes, an emptied time as no rule, a decimal as typed', () => {
      expect(storedValue(SEUILS['dureeHebdomadaireMaxMinutes'], '44,5')).toBe(2670);
      expect(storedValue(SEUILS['heureServiceTardif'], '')).toBeNull();
      expect(storedValue(SEUILS['facteurDetour'], '1.35')).toBe(1.35);
      expect(storedValue(SEUILS['joursConsecutifsMax'], '6.4')).toBe(6);
      expect(storedValue(SEUILS['joursConsecutifsMax'], 'abc')).toBeNull();
    });

    it('sees no change in a value typed back to what is stored', () => {
      expect(hasChanges(legaux, { coupureRepasMidiDebut: '12:00' })).toBe(false);
      expect(hasChanges(legaux, { coupureRepasMidiDebut: '12:30' })).toBe(true);
      expect(hasChanges(legaux, { dureeHebdomadaireMaxMinutes: 48 * 60 })).toBe(false);
    });

    /** The server replaces the whole record: a save built from the changes alone would reset the rest. */
    it('sends the stored record with the changes over it', () => {
      const stored = {
        dureeHebdomadaireMaxMinutes: 2880,
        dureePauseMinutes: 30,
        heureDebutSoiree: '20:00',
      } as ParametresLegaux;

      expect(mergeSettings(stored, { dureePauseMinutes: 45 })).toEqual({
        dureeHebdomadaireMaxMinutes: 2880,
        dureePauseMinutes: 45,
        heureDebutSoiree: '20:00',
      });
    });
  });
});
