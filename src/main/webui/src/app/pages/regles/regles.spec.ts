import { describe, expect, it } from 'vitest';
import { ConstraintView, ParametresLegaux } from '../../core/models';
import {
  SEUILS,
  articlesOf,
  hasChanges,
  mergeSettings,
  blocOfRule,
  niveauEffectif,
  opensGroup,
  readOngletRegles,
  rulesOfBloc,
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
    niveauReglable: false,
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
  it('lists the rules of a catalogue group together, under one title', () => {
    const rules = [
      rule({ name: 'a', groupe: 'g' }),
      rule({ name: 'b' }),
      rule({ name: 'c', groupe: 'g' }),
    ];
    const rows = rulesOfBloc(rules, 'avance');
    expect(rows.map((each) => each.name)).toEqual(['a', 'c', 'b']);
    expect(rows.map((_, index) => opensGroup(rows, index))).toEqual([true, false, false]);
  });

  it('reads the rules tab, and the two it replaced', () => {
    expect(readOngletRegles('regles')).toBe('regles');
    expect(readOngletRegles('legal')).toBe('regles');
    expect(readOngletRegles('qualite')).toBe('regles');
    expect(readOngletRegles('calcul')).toBeNull();
    expect(readOngletRegles('legaux')).toBeNull();
    expect(readOngletRegles(null)).toBeNull();
  });

  it('files a rule by what the catalogue says of it, never by its name', () => {
    expect(blocOfRule({ legale: true, niveauReglable: false })).toBe('legal');
    expect(blocOfRule({ legale: false, niveauReglable: true })).toBe('organisation');
    expect(blocOfRule({ legale: false, niveauReglable: false })).toBe('avance');
  });

  it('reads the level a rule scores on from the edition’s choice', () => {
    const base = { niveau: 'MEDIUM', niveauReglable: true } as const;
    expect(niveauEffectif({ ...base, niveauChoisi: 'OBLIGATOIRE' })).toBe('HARD');
    expect(niveauEffectif({ ...base, niveauChoisi: 'PREFERENCE_FORTE' })).toBe('MEDIUM');
    // A rule the catalogue ships hard, brought down to a preference.
    expect(niveauEffectif({ ...base, niveau: 'HARD', niveauChoisi: 'PREFERENCE_LEGERE' })).toBe(
      'MEDIUM',
    );
    // No choice to make: the catalogue's own.
    expect(niveauEffectif({ niveau: 'HARD', niveauReglable: false, niveauChoisi: null })).toBe(
      'HARD',
    );
  });

  it('orders a block by category, the law first, and keeps the catalogue order inside one', () => {
    const rules = [
      rule({ name: 'affectation', categorie: 'Affectation' }),
      rule({ name: 'mineurs1', categorie: 'Légal (mineurs)', legale: true }),
      rule({ name: 'travail', categorie: 'Légal (temps de travail)', legale: true }),
      rule({ name: 'mineurs2', categorie: 'Légal (mineurs)', legale: true }),
      rule({
        name: 'qualite',
        niveau: 'MEDIUM',
        categorie: "Qualité d'organisation",
        niveauReglable: true,
      }),
    ];

    expect(rulesOfBloc(rules, 'legal').map((each) => each.name)).toEqual([
      'travail',
      'mineurs1',
      'mineurs2',
    ]);
    expect(rulesOfBloc(rules, 'organisation').map((each) => each.name)).toEqual(['qualite']);
    expect(rulesOfBloc(rules, 'avance').map((each) => each.name)).toEqual(['affectation']);
  });

  it('finds the articles a description cites, once each', () => {
    expect(
      articlesOf('Au plus 48 h (art. L3121-20) ; 35 h pour un mineur (L3162-1, L3121-20).'),
    ).toEqual(['L3121-20', 'L3162-1']);
    expect(articlesOf('Chaque place ouverte doit être pourvue.')).toEqual([]);
  });

  describe('a setting on its row', () => {
    const legaux: Record<string, string | number | null> = {
      dureeHebdomadaireMaxMinutes: 48 * 60,
      coupureRepasMidiDebut: '12:00:00',
    };

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
      expect(storedValue(SEUILS['facteurDetour'], '1.35')).toBeCloseTo(1.35, 10);
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
