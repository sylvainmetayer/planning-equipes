import { describe, expect, it } from 'vitest';
import {
  Animateur,
  ContrainteImpact,
  HardMediumSoftScore,
  NiveauCompetence,
  PlanningEvenement,
  PosteAffectation,
  Stand,
  SwapSimulation
} from '../core/models';
import {
  aUneAppreciationPour,
  candidatsPour,
  compareDelta,
  nouvellesViolations,
  sensDuDelta,
  violationsResolues
} from './affectation-explanation-rules';

function score(hardScore: number, mediumScore: number, softScore: number): HardMediumSoftScore {
  return { hardScore, mediumScore, softScore };
}

function impact(name: string): ContrainteImpact {
  return { name, niveau: 'HARD', categorie: null, description: null, matchCount: 1, details: [] };
}

function simulation(avant: string[], apres: string[]): SwapSimulation {
  return {
    posteId: 'p1',
    animateurActuelId: 'a1',
    animateurCandidatId: 'a2',
    scoreAvant: score(0, 0, 0),
    scoreApres: score(0, 0, 0),
    delta: score(0, 0, 0),
    contraintesVioleesAvant: avant.map(impact),
    contraintesVioleesApres: apres.map(impact)
  };
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

function noms(impacts: ContrainteImpact[]): string[] {
  return impacts.map((each) => each.name);
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

describe('sensDuDelta', () => {
  it('stays neutral while no simulation is loaded', () => {
    expect(sensDuDelta(null)).toBe('same');
  });

  it('reads the loaded simulation delta', () => {
    expect(sensDuDelta({ ...simulation([], []), delta: score(-3, 0, 0) })).toBe('worse');
  });
});

describe('violationsResolues', () => {
  it('keeps only what the swap would clear, matched on the constraint name', () => {
    expect(noms(violationsResolues(simulation(['mineurApres22h', 'reposQuotidien'], ['reposQuotidien'])))).toEqual([
      'mineurApres22h'
    ]);
  });

  it('reports nothing when the swap clears nothing', () => {
    expect(violationsResolues(simulation(['reposQuotidien'], ['reposQuotidien']))).toEqual([]);
  });

  it('is empty while no simulation is loaded', () => {
    expect(violationsResolues(null)).toEqual([]);
  });
});

describe('nouvellesViolations', () => {
  it('keeps only what the swap would introduce, matched on the constraint name', () => {
    expect(noms(nouvellesViolations(simulation(['reposQuotidien'], ['reposQuotidien', 'competenceRequise'])))).toEqual([
      'competenceRequise'
    ]);
  });

  it('does not count a violation that was already there as new', () => {
    expect(nouvellesViolations(simulation(['reposQuotidien'], ['reposQuotidien']))).toEqual([]);
  });

  it('is empty while no simulation is loaded', () => {
    expect(nouvellesViolations(null)).toEqual([]);
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

describe('candidatsPour', () => {
  const ambiance = stand(['ambiance']);

  function planning(animateurs: Animateur[]): PlanningEvenement {
    return { animateurs, postes: [], score: null };
  }

  function poste(occupant: Animateur | null, sur: Stand | null = ambiance): PosteAffectation {
    return { id: 'p1', stand: sur, creneau: null, animateur: occupant };
  }

  it('never offers the animateur already on the poste', () => {
    const titulaire = animateur('a1', { ambiance: 'REFERENT' });
    const autre = animateur('a2', { ambiance: 'REFERENT' });

    expect(candidatsPour(planning([titulaire, autre]), poste(titulaire)).map((each) => each.id)).toEqual(['a2']);
  });

  it('leaves out anyone without an appreciation for the stand', () => {
    const titulaire = animateur('a1', { ambiance: 'REFERENT' });
    const sansAppreciation = animateur('a2');

    expect(candidatsPour(planning([titulaire, sansAppreciation]), poste(titulaire))).toEqual([]);
  });

  it('offers nobody for a poste carrying no stand, rather than everybody', () => {
    const titulaire = animateur('a1', { ambiance: 'REFERENT' });
    const autre = animateur('a2', { ambiance: 'REFERENT' });

    expect(candidatsPour(planning([titulaire, autre]), poste(titulaire, null))).toEqual([]);
  });

  it('offers every appreciated animateur when the seat is empty', () => {
    const un = animateur('a1', { ambiance: 'REFERENT' });
    const deux = animateur('a2', { ambiance: 'DEBUTANT' });

    expect(candidatsPour(planning([un, deux]), poste(null)).map((each) => each.id)).toEqual(['a1', 'a2']);
  });
});
