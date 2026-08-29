import { describe, expect, it } from 'vitest';
import type {
  AnimateurAffecte,
  ApercuPublication,
  CreneauJourJ,
  EtatJourJ,
  PosteAPourvoir,
  SuggestionsReparation
} from '../../core/models';
import {
  aucuneSuggestion,
  blocageDuPoste,
  chargeRestante,
  dejaDeService,
  heure,
  heureDe,
  libelleCreneau,
  nomDuCandidat,
  plage,
  porteeDesSuggestions,
  rappelPublication,
  resumeDuJour
} from './jour-j-wording';

function etat(overrides: Partial<EtatJourJ> = {}): EtatJourJ {
  return {
    date: '2026-07-08',
    maintenant: '2026-07-08T13:30:00',
    creneauxDuJour: 4,
    creneauxRestants: [],
    animateursDeService: [],
    postesAPourvoir: [],
    absences: [],
    animateurs: [],
    ...overrides
  };
}

function creneau(overrides: Partial<CreneauJourJ> = {}): CreneauJourJ {
  return {
    id: 2,
    date: '2026-07-08',
    heureDebut: '14:00:00',
    heureFin: '18:00:00',
    enCours: false,
    ...overrides
  };
}

function animateur(overrides: Partial<AnimateurAffecte> = {}): AnimateurAffecte {
  return {
    animateurId: 'A1',
    nomAffiche: 'Alice Referente',
    postesRestants: 2,
    absent: false,
    ...overrides
  };
}

function poste(overrides: Partial<PosteAPourvoir> = {}): PosteAPourvoir {
  return {
    posteId: 'P2',
    standId: 'STAND-STRAT',
    standNom: 'Stand stratégie',
    creneauId: 2,
    heureDebut: '14:00:00',
    heureFin: '18:00:00',
    verrouille: false,
    ...overrides
  };
}

function suggestions(overrides: Partial<SuggestionsReparation> = {}): SuggestionsReparation {
  return {
    posteId: 'P2',
    animateurActuelId: null,
    scoreAvant: { hardScore: 0, mediumScore: 0, softScore: 0 },
    contraintesVioleesAvant: [],
    candidatsEligibles: 20,
    candidatsEvalues: 20,
    plafond: 20,
    suggestions: [],
    ...overrides
  };
}

describe('heure / plage', () => {
  it('trims the seconds the server sends', () => {
    expect(heure('09:05:00')).toBe('09:05');
    expect(heure(null)).toBe('');
    expect(plage('14:00:00', '18:00:00')).toBe('14:00 – 18:00');
  });

  /** The reference moment is an instant, since a journée can run past midnight. */
  it('reads the time of day off a full instant', () => {
    expect(heureDe('2026-07-09T01:00:00')).toBe('01:00');
    expect(heureDe(null)).toBe('');
  });
});

describe('resumeDuJour', () => {
  it('states the remaining count against the whole day', () => {
    const texte = resumeDuJour(etat({ creneauxRestants: [creneau(), creneau({ id: 3 })] }));
    expect(texte).toContain('2');
    expect(texte).toContain('4');
    expect(texte).toContain('13:30');
  });

  it('says the day is over rather than showing a zero', () => {
    expect(resumeDuJour(etat({ creneauxDuJour: 4 }))).toContain('terminée');
  });

  /**
   * A date carrying no timeslot has no first one, so no journée at all. Calling
   * that "over" would be a lie the operator acts on.
   */
  it('tells an empty journée from a finished one', () => {
    const texte = resumeDuJour(etat({ creneauxDuJour: 0 }));
    expect(texte).toContain('Aucun créneau');
    expect(texte).not.toContain('terminée');
  });

  it('says nothing before the state has loaded', () => {
    expect(resumeDuJour(null)).toBe('');
  });
});

describe('rappelPublication', () => {
  const apercu = (nombreConcernes: number): ApercuPublication => ({
    jamaisPublie: false,
    planVide: false,
    solveEnCours: false,
    dernierePublicationLe: null,
    nombreConcernes,
    destinataires: []
  });

  it('stays silent when nobody is waiting', () => {
    expect(rappelPublication(apercu(0))).toBe('');
    expect(rappelPublication(null)).toBe('');
  });

  it('counts the people concerned', () => {
    expect(rappelPublication(apercu(1))).toContain('1 personne');
    expect(rappelPublication(apercu(3))).toContain('3');
  });
});

describe('porteeDesSuggestions', () => {
  /**
   * The decision behind this screen's plafond: a truncated list shown bare
   * reads as "there is nobody else", which is the one thing it must not say.
   */
  it('names both counts when the search stopped at the plafond', () => {
    const texte = porteeDesSuggestions(
      suggestions({ candidatsEligibles: 137, candidatsEvalues: 20 })
    );
    expect(texte).toContain('20');
    expect(texte).toContain('137');
    expect(texte).toContain('pas tout le vivier');
  });

  it('says so plainly when the whole pool was evaluated', () => {
    const texte = porteeDesSuggestions(
      suggestions({ candidatsEligibles: 12, candidatsEvalues: 12 })
    );
    expect(texte).toContain('12');
    expect(texte).not.toContain('pas tout le vivier');
  });

  it('says nothing before a search has run', () => {
    expect(porteeDesSuggestions(null)).toBe('');
  });
});

describe('aucuneSuggestion', () => {
  it('tells an empty answer from no answer at all', () => {
    expect(aucuneSuggestion(null)).toBe(false);
    expect(aucuneSuggestion(suggestions())).toBe(true);
  });
});

describe('blocageDuPoste', () => {
  it('explains a lock instead of offering a button that would be refused', () => {
    expect(blocageDuPoste(poste())).toBe('');
    expect(blocageDuPoste(poste({ verrouille: true }))).toContain('verrou');
  });
});

describe('libelleCreneau', () => {
  it('marks the timeslot under way', () => {
    expect(libelleCreneau(creneau())).toBe('14:00 – 18:00');
    expect(libelleCreneau(creneau({ enCours: true }))).toContain('en cours');
  });
});

describe('chargeRestante', () => {
  it('says how much of the day the person still holds', () => {
    expect(chargeRestante(animateur({ postesRestants: 1 }))).toContain('1 créneau restant');
    expect(chargeRestante(animateur({ postesRestants: 3 }))).toContain('3');
  });
});

describe('nomDuCandidat', () => {
  const charge = etat({
    animateursDeService: [animateur()],
    animateurs: [
      { animateurId: 'A1', nomAffiche: 'Alice Referente' },
      { animateurId: 'A2', nomAffiche: 'Bruno Autonome' }
    ]
  });

  it('names people from the roster', () => {
    expect(nomDuCandidat(charge, 'A1')).toBe('Alice Referente');
  });

  /**
   * The regression that made this a roster lookup: the best replacement is
   * somebody *free* at that hour, so they are precisely the one missing from
   * the on-duty list — and the main action button read « A2 ».
   */
  it('names a candidate who is not on duty at all', () => {
    expect(nomDuCandidat(charge, 'A2')).toBe('Bruno Autonome');
  });

  it('falls back to the id rather than hiding a real candidate', () => {
    expect(nomDuCandidat(charge, 'A9')).toBe('A9');
    expect(nomDuCandidat(null, 'A9')).toBe('A9');
  });
});

describe('dejaDeService', () => {
  it('flags a candidate already working the remaining timeslots', () => {
    const charge = etat({ animateursDeService: [animateur()] });
    expect(dejaDeService(charge, 'A1')).toBe(true);
    expect(dejaDeService(charge, 'A2')).toBe(false);
    expect(dejaDeService(null, 'A1')).toBe(false);
  });
});
