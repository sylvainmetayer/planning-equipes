import { describe, expect, it } from 'vitest';
import {
  Animateur,
  HardMediumSoftScore,
  NiveauCompetence,
  PlanningEvenement,
  Stand,
  SuggestionReparation,
  SuggestionsReparation
} from '../core/models';
import {
  aUneAppreciationPour,
  compareDelta,
  meilleuresSuggestions,
  nomAnimateur,
  suggestionsTronquees
} from './affectation-explanation-rules';

function score(hardScore: number, mediumScore: number, softScore: number): HardMediumSoftScore {
  return { hardScore, mediumScore, softScore };
}

function animateur(id: string, competences: Record<string, NiveauCompetence> = {}): Animateur {
  return {
    id,
    prenom: id,
    nom: '',
    dateNaissance: '2000-01-01',
    manager: false,
    competences,
    souhaits: [],
    joursIndisponibles: []
  };
}

function stand(typologiesProposees: string[]): Stand {
  return {
    id: 's1',
    nom: 's1',
    typologiesProposees,
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: []
  };
}

describe('compareDelta', () => {
  // The whole point of the coloured delta: a swap that clears a hard violation
  // is an improvement even when it costs soft points, because that is the order
  // Timefold itself compares scores in.
  it('lets the hard level decide alone, whatever the medium and soft levels say', () => {
    expect(compareDelta(score(1, -100, -100))).toBe('better');
    expect(compareDelta(score(-1, 100, 100))).toBe('worse');
  });

  it('falls back to the medium level only when hard is unchanged', () => {
    expect(compareDelta(score(0, 1, -100))).toBe('better');
    expect(compareDelta(score(0, -1, 100))).toBe('worse');
  });

  it('falls back to the soft level only when hard and medium are unchanged', () => {
    expect(compareDelta(score(0, 0, 1))).toBe('better');
    expect(compareDelta(score(0, 0, -1))).toBe('worse');
  });

  it('reports a strictly unchanged score as neither better nor worse', () => {
    expect(compareDelta(score(0, 0, 0))).toBe('same');
  });
});

describe('aUneAppreciationPour', () => {
  it('is true as soon as one typologie of the stand is appreciated', () => {
    expect(aUneAppreciationPour(animateur('a1', { ambiance: 'DEBUTANT' }), stand(['expert', 'ambiance']))).toBe(true);
  });

  it('is false when none of the stand typologies is appreciated', () => {
    expect(aUneAppreciationPour(animateur('a1', { ambiance: 'REFERENT' }), stand(['expert']))).toBe(false);
  });

  it('is false for a stand offering no typologie at all', () => {
    expect(aUneAppreciationPour(animateur('a1', { ambiance: 'REFERENT' }), stand([]))).toBe(false);
  });

  // The appreciation is a level, and `DEBUTANT` is a real one: keying on
  // presence rather than truthiness is what keeps a beginner a candidate.
  it('counts an appreciation whose level is the lowest one', () => {
    expect(aUneAppreciationPour(animateur('a1', { ambiance: 'DEBUTANT' }), stand(['ambiance']))).toBe(true);
  });
});

describe('suggestions de réparation (issue #71)', () => {
  function suggestion(animateurId: string, delta: HardMediumSoftScore): SuggestionReparation {
    return { animateurId, scoreApres: score(0, 0, 0), delta, violationsResolues: [], violationsIntroduites: [] };
  }

  function reparations(overrides: Partial<SuggestionsReparation> = {}): SuggestionsReparation {
    return {
      posteId: 'p1',
      animateurActuelId: 'a1',
      scoreAvant: score(0, 0, 0),
      contraintesVioleesAvant: [],
      candidatsEligibles: 3,
      candidatsEvalues: 3,
      plafond: 20,
      suggestions: [],
      ...overrides
    };
  }

  function planning(animateurs: Animateur[]): PlanningEvenement {
    return { animateurs, postes: [], score: null };
  }

  function personne(id: string, prenom: string, nom: string): Animateur {
    return { ...animateur(id), prenom, nom };
  }

  it('does not call the answer truncated when every eligible candidate was evaluated', () => {
    expect(suggestionsTronquees(reparations({ candidatsEligibles: 3, candidatsEvalues: 3 }))).toBe(false);
  });

  it('calls it truncated as soon as the plafond stopped the search short', () => {
    expect(suggestionsTronquees(reparations({ candidatsEligibles: 137, candidatsEvalues: 20 }))).toBe(true);
  });

  it('reports nothing truncated while no search has run', () => {
    expect(suggestionsTronquees(null)).toBe(false);
  });

  it('keeps the server ranking untouched and only trims to the display cap', () => {
    const rangees = ['a1', 'a2', 'a3', 'a4', 'a5', 'a6'].map((id, index) =>
      suggestion(id, score(0, 0, -index))
    );

    expect(meilleuresSuggestions(reparations({ suggestions: rangees })).map((each) => each.animateurId)).toEqual([
      'a1',
      'a2',
      'a3',
      'a4',
      'a5'
    ]);
  });

  it('returns every suggestion when there are fewer than the cap', () => {
    const deux = [suggestion('a1', score(0, 0, 0)), suggestion('a2', score(0, 0, -1))];

    expect(meilleuresSuggestions(reparations({ suggestions: deux }))).toHaveLength(2);
  });

  it('returns an empty list rather than throwing while no search has run', () => {
    expect(meilleuresSuggestions(null)).toEqual([]);
  });

  it('names a suggested animateur from the planning the dialog holds', () => {
    expect(nomAnimateur(planning([personne('a1', 'Camille', 'Durand')]), 'a1')).toBe('Camille Durand');
  });

  it('falls back to the raw id when the referential no longer knows that animateur', () => {
    expect(nomAnimateur(planning([personne('a1', 'Camille', 'Durand')]), 'disparu')).toBe('disparu');
  });
});
