// The entry rules of one stand — the largest untested logic of the repository
// before this file: 442 lines of dialog holding six validation rules and
// fifteen list mutations, covered by nothing.

import { describe, expect, it } from 'vitest';
import {
  StandDraft,
  ajouterA,
  basculerJour,
  brouillonInvalide,
  conflitOuvertureFermeture,
  datesFromText,
  effectifInvalide,
  indisponibiliteInvalide,
  normaliserHoraire,
  ouvertureInvalide,
  patchDansListe,
  plageVide,
  retirerDe,
  effectifFenetreInvalide,
  effectifOuvertureInvalide,
  fenetreVide,
  horairesCopiedFrom,
  windowsBeyondMaximumCount,
  normaliserEffectif,
  ouvertureVide,
  toDraft,
  typologiesVides,
  versStand,
} from './stand-draft';
import type { Emplacement, HoraireStand, Stand } from '../../core/models';

function draft(overrides: Partial<StandDraft> = {}): StandDraft {
  return {
    ...toDraft(null),
    id: 'S1',
    nom: 'Stand 1',
    typologiesProposees: ['STRATEGIE'],
    ...overrides,
  };
}

function plage(overrides: Partial<ReturnType<typeof plageVide>> = {}) {
  return { ...plageVide(), date: '2026-07-10', heureDebut: '10:00', ...overrides };
}

function horaire(overrides: Partial<HoraireStand> = {}): HoraireStand {
  return {
    id: null,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: [{ heureDebut: '10:00', heureFin: '18:00' }],
    ...overrides,
  } as HoraireStand;
}

describe('list edits', () => {
  it('patches the item at the index and leaves its neighbours untouched', () => {
    const liste = [{ n: 1 }, { n: 2 }, { n: 3 }];

    const result = patchDansListe(liste, 1, { n: 20 });

    expect(result).toEqual([{ n: 1 }, { n: 20 }, { n: 3 }]);
    // A new array and a new item: a signal holding a mutated object notifies nobody.
    expect(result).not.toBe(liste);
    expect(result[1]).not.toBe(liste[1]);
    expect(result[0]).toBe(liste[0]);
  });

  it('leaves the list alone when the index is out of range', () => {
    const liste = [{ n: 1 }];
    expect(patchDansListe(liste, 5, { n: 9 })).toEqual([{ n: 1 }]);
    expect(patchDansListe(liste, -1, { n: 9 })).toEqual([{ n: 1 }]);
  });

  it('removes exactly one row, by position and not by value', () => {
    const liste = [{ n: 1 }, { n: 1 }, { n: 2 }];

    expect(retirerDe(liste, 0)).toEqual([{ n: 1 }, { n: 2 }]);
    expect(retirerDe(liste, 9)).toEqual(liste);
  });

  it('appends without touching the original', () => {
    const liste = [{ n: 1 }];

    expect(ajouterA(liste, { n: 2 })).toEqual([{ n: 1 }, { n: 2 }]);
    expect(liste).toHaveLength(1);
  });
});

describe('basculerJour', () => {
  it('adds a day once, however many times it is ticked', () => {
    expect(basculerJour(['MONDAY'], 'FRIDAY', true)).toEqual(['MONDAY', 'FRIDAY']);
    expect(basculerJour(['MONDAY', 'FRIDAY'], 'FRIDAY', true)).toEqual(['MONDAY', 'FRIDAY']);
  });

  it('removes a day, and does nothing when it was not there', () => {
    expect(basculerJour(['MONDAY', 'FRIDAY'], 'MONDAY', false)).toEqual(['FRIDAY']);
    expect(basculerJour(['FRIDAY'], 'MONDAY', false)).toEqual(['FRIDAY']);
  });
});

describe('datesDepuisTexte', () => {
  it('keeps the ISO dates and trims the spacing around them', () => {
    expect(datesFromText(' 2026-07-10 , 2026-07-11 ')).toEqual(['2026-07-10', '2026-07-11']);
  });

  it('drops anything that is not an ISO date rather than sending it to the backend', () => {
    expect(datesFromText('2026-07-10, 10/07/2026, demain, ')).toEqual(['2026-07-10']);
  });

  it('gives an empty list for an empty field', () => {
    expect(datesFromText('')).toEqual([]);
  });
});

describe('effectifInvalide', () => {
  it('refuses a maximum below the minimum', () => {
    expect(effectifInvalide(draft({ effectifMin: 3, effectifMax: 2 }))).toBe(true);
  });

  it('accepts a maximum equal to the minimum', () => {
    expect(effectifInvalide(draft({ effectifMin: 2, effectifMax: 2 }))).toBe(false);
  });
});

describe('dated exceptions', () => {
  it('refuses a closure without a date or without a start time', () => {
    expect(indisponibiliteInvalide(draft({ indisponibilites: [plage({ date: '' })] }))).toBe(true);
    expect(indisponibiliteInvalide(draft({ indisponibilites: [plage({ heureDebut: '' })] }))).toBe(
      true,
    );
  });

  it('refuses an end time that is not strictly after the start', () => {
    expect(
      indisponibiliteInvalide(draft({ indisponibilites: [plage({ heureFin: '10:00' })] })),
    ).toBe(true);
    expect(
      indisponibiliteInvalide(draft({ indisponibilites: [plage({ heureFin: '09:00' })] })),
    ).toBe(true);
  });

  it('accepts an empty end time, which means "until closing time"', () => {
    expect(indisponibiliteInvalide(draft({ indisponibilites: [plage({ heureFin: null })] }))).toBe(
      false,
    );
    expect(indisponibiliteInvalide(draft({ indisponibilites: [plage({ heureFin: '' })] }))).toBe(
      false,
    );
  });

  it('applies exactly the same rules to the opening exceptions', () => {
    expect(ouvertureInvalide(draft({ ouvertures: [plage({ date: '' })] }))).toBe(true);
    expect(ouvertureInvalide(draft({ ouvertures: [plage({ heureFin: '09:00' })] }))).toBe(true);
    expect(ouvertureInvalide(draft({ ouvertures: [plage()] }))).toBe(false);
  });

  it('is happy with no exception at all', () => {
    expect(indisponibiliteInvalide(draft())).toBe(false);
    expect(ouvertureInvalide(draft())).toBe(false);
  });
});

describe('window effectif', () => {
  it('opens a new window and a new opening with no effectif named', () => {
    expect(fenetreVide().effectif).toBeNull();
    expect(ouvertureVide().effectif).toBeNull();
    // A closure never carries one: the backend has no such field there.
    expect('effectif' in plageVide()).toBe(false);
  });

  it('accepts an absent effectif and any whole number from one up, within the stand capacity', () => {
    expect(effectifFenetreInvalide(4, 4)).toBe(false);
    expect(effectifFenetreInvalide(5, 4)).toBe(true);
    expect(effectifFenetreInvalide(null, 1)).toBe(false);
    expect(effectifFenetreInvalide(null)).toBe(false);
    expect(effectifFenetreInvalide(undefined)).toBe(false);
    expect(effectifFenetreInvalide(1)).toBe(false);
    expect(effectifFenetreInvalide(12)).toBe(false);
  });

  it('refuses zero, a negative number and a fraction', () => {
    expect(effectifFenetreInvalide(0)).toBe(true);
    expect(effectifFenetreInvalide(-2)).toBe(true);
    expect(effectifFenetreInvalide(1.5)).toBe(true);
    expect(effectifFenetreInvalide(Number.NaN)).toBe(true);
  });

  it('normalises the empty states to null and keeps a typed number as a number', () => {
    expect(normaliserEffectif(null)).toBeNull();
    expect(normaliserEffectif(undefined)).toBeNull();
    expect(normaliserEffectif('')).toBeNull();
    expect(normaliserEffectif(3)).toBe(3);
    expect(normaliserEffectif('2' as unknown as number)).toBe(2);
    // Zero is not "empty": it stays, and the validation is what refuses it.
    expect(normaliserEffectif(0)).toBe(0);
  });

  it('flags an opening whose effectif cannot be saved, and only that', () => {
    expect(effectifOuvertureInvalide(draft({ ouvertures: [{ ...plage(), effectif: 0 }] }))).toBe(
      true,
    );
    expect(
      effectifOuvertureInvalide(
        draft({ effectifMax: 4, ouvertures: [{ ...plage(), effectif: 2 }, plage()] }),
      ),
    ).toBe(false);
    // Above the stand's declared capacity, the server would refuse it too.
    expect(
      effectifOuvertureInvalide(
        draft({ effectifMax: 2, ouvertures: [{ ...plage(), effectif: 5 }] }),
      ),
    ).toBe(true);
    expect(effectifOuvertureInvalide(draft({ ouvertures: [] }))).toBe(false);
    // The generic opening check does not double-report it.
    expect(ouvertureInvalide(draft({ ouvertures: [{ ...plage(), effectif: 0 }] }))).toBe(false);
  });
});

describe('conflitOuvertureFermeture', () => {
  it('refuses the same day being both closed and opened', () => {
    const invalide = draft({
      indisponibilites: [plage({ date: '2026-07-10' })],
      ouvertures: [plage({ date: '2026-07-10' })],
    });

    expect(conflitOuvertureFermeture(invalide)).toBe(true);
  });

  it('accepts a closure and an opening on two different days', () => {
    const valid = draft({
      indisponibilites: [plage({ date: '2026-07-10' })],
      ouvertures: [plage({ date: '2026-07-11' })],
    });

    expect(conflitOuvertureFermeture(valid)).toBe(false);
  });

  it('does not count a row whose date has not been filled in yet as a conflict', () => {
    const enCoursDeSaisie = draft({
      indisponibilites: [plage({ date: '' })],
      ouvertures: [plage({ date: '' })],
    });

    expect(conflitOuvertureFermeture(enCoursDeSaisie)).toBe(false);
  });
});

describe('brouillonInvalide', () => {
  it('is false for a freshly opened form', () => {
    expect(brouillonInvalide(draft())).toBe(false);
  });

  it('catches each rule on its own', () => {
    expect(brouillonInvalide(draft({ effectifMin: 3, effectifMax: 1 }))).toBe(true);
    expect(brouillonInvalide(draft({ indisponibilites: [plage({ date: '' })] }))).toBe(true);
    expect(brouillonInvalide(draft({ ouvertures: [plage({ heureDebut: '' })] }))).toBe(true);
    expect(
      brouillonInvalide(
        draft({
          indisponibilites: [plage({ date: '2026-07-10' })],
          ouvertures: [plage({ date: '2026-07-10' })],
        }),
      ),
    ).toBe(true);
  });
});

describe('typologiesVides — un stand a toujours au moins une typologie (#343)', () => {
  it("rend le brouillon invalide tant qu'aucune typologie n'est choisie", () => {
    const without = { ...draft(), typologiesProposees: [] };
    expect(typologiesVides(without)).toBe(true);
    expect(brouillonInvalide(without)).toBe(true);

    const withTypologies = { ...without, typologiesProposees: ['STRATEGIE'] };
    expect(typologiesVides(withTypologies)).toBe(false);
    expect(brouillonInvalide(withTypologies)).toBe(false);
  });
});

describe('modifieLe — la précondition de #362 voyage de la fiche ouverte au payload', () => {
  it('reprend le modifieLe de la fiche et le renvoie tel quel', () => {
    const stand = {
      ...toDraft(null),
      id: 'S1',
      nom: 'Stand',
      modifieLe: '2026-09-06T10:00:00.123456Z',
    } as unknown as Stand;

    const draft = toDraft(stand);

    expect(draft.modifieLe).toBe('2026-09-06T10:00:00.123456Z');
    expect(versStand(draft, []).modifieLe).toBe('2026-09-06T10:00:00.123456Z');
  });

  it('vaut null sur une fiche neuve : une création ne porte pas de précondition', () => {
    expect(toDraft(null).modifieLe).toBeNull();
    expect(versStand(toDraft(null), []).modifieLe).toBeNull();
  });
});

describe('toDraft', () => {
  it('opens a new stand on a one-seat default rather than on zero', () => {
    const vierge = toDraft(null);

    expect(vierge.effectifMin).toBe(1);
    expect(vierge.effectifMax).toBe(1);
    expect(vierge.niveauEffort).toBe('NORMAL');
    expect(vierge.horaires).toEqual([]);
  });

  it('never aliases the store objects: editing the draft must not write through', () => {
    const stand = {
      id: 'S1',
      nom: 'Stand',
      effectifMin: 1,
      effectifMax: 2,
      typologiesProposees: ['t1'],
      indisponibilites: [plage()],
      ouvertures: [],
      horaires: [horaire({ joursSemaine: ['MONDAY'], dates: ['2026-07-10'] })],
    } as unknown as Stand;

    const copie = toDraft(stand);
    copie.typologiesProposees.push('t2');
    copie.indisponibilites[0].date = '1999-01-01';
    copie.horaires[0].joursSemaine.push('FRIDAY');
    copie.horaires[0].fenetres[0].heureDebut = '00:00';

    expect(stand.typologiesProposees).toEqual(['t1']);
    expect(stand.indisponibilites[0].date).toBe('2026-07-10');
    expect(stand.horaires[0].joursSemaine).toEqual(['MONDAY']);
    expect(stand.horaires[0].fenetres[0].heureDebut).toBe('10:00');
  });

  it('falls back on the defaults for the fields the server left null', () => {
    const stand = { id: 'S1', effectifMin: 1, effectifMax: 1 } as unknown as Stand;

    const copie = toDraft(stand);

    expect(copie.nom).toBe('');
    expect(copie.niveauEffort).toBe('NORMAL');
    expect(copie.reserveMajeurs).toBe(false);
    expect(copie.emplacementId).toBeNull();
    expect(copie.horaires).toEqual([]);
  });
});

describe('versStand', () => {
  it('sends the relay family as a number, and null to let the server pick', () => {});

  const emplacements = [{ id: 1, code: 'salle-1', nom: 'Salle 1' }] as Emplacement[];

  it('trims the identifier and the name', () => {
    const stand = versStand(draft({ id: '  S1  ', nom: '  Stand 1  ' }), emplacements);

    expect(stand.id).toBe('S1');
    expect(stand.nom).toBe('Stand 1');
  });

  it('resolves the emplacement against the store, and stays null when none is picked', () => {
    expect(versStand(draft({ emplacementId: 1 }), emplacements).emplacement).toEqual(
      emplacements[0],
    );
    expect(versStand(draft({ emplacementId: null }), emplacements).emplacement).toBeNull();
    // Picked then deleted elsewhere: null rather than a dangling reference.
    expect(versStand(draft({ emplacementId: 99 }), emplacements).emplacement).toBeNull();
  });

  it('turns an emptied time field back into "until closing time"', () => {
    const stand = versStand(
      draft({ indisponibilites: [plage({ heureFin: '' })], ouvertures: [plage({ heureFin: '' })] }),
      emplacements,
    );

    expect(stand.indisponibilites[0].heureFin).toBeNull();
    expect(stand.ouvertures[0].heureFin).toBeNull();
  });

  it('sends a window effectif as typed, and an emptied one as null — never as zero', () => {
    const stand = versStand(
      draft({
        ouvertures: [
          { ...plage(), effectif: 4 },
          { ...plage({ date: '2026-07-11' }), effectif: null },
          { ...plage({ date: '2026-07-12' }), effectif: '' as unknown as number },
        ],
        horaires: [
          horaire({
            fenetres: [
              { heureDebut: '10:00', heureFin: '12:00', effectif: 3 },
              { heureDebut: '14:00', heureFin: null, effectif: '' as unknown as number },
              { heureDebut: '16:00', heureFin: null },
            ],
          }),
        ],
      }),
      emplacements,
    );

    expect(stand.ouvertures.map((ouverture) => ouverture.effectif)).toEqual([4, null, null]);
    expect(stand.horaires[0].fenetres.map((fenetre) => fenetre.effectif)).toEqual([3, null, null]);
  });

  it('reads an empty effectif as zero rather than as NaN', () => {
    const stand = versStand(
      draft({ effectifMin: '' as unknown as number, effectifMax: '' as unknown as number }),
      emplacements,
    );

    expect(stand.effectifMin).toBe(0);
    expect(stand.effectifMax).toBe(0);
  });
});

describe('normaliserHoraire', () => {
  it('drops the bounds a rule switched away from PLAGE was still dragging along', () => {
    const regle = horaire({
      jours: 'TOUS',
      dateDebut: '2026-07-10',
      dateFin: '2026-07-12',
      dates: ['2026-07-10'],
      joursSemaine: ['MONDAY'],
    });

    const normalise = normaliserHoraire(regle);

    expect(normalise.dateDebut).toBeNull();
    expect(normalise.dateFin).toBeNull();
    expect(normalise.dates).toEqual([]);
    expect(normalise.joursSemaine).toEqual([]);
  });

  it('keeps only what the chosen scope uses', () => {
    expect(
      normaliserHoraire(
        horaire({ jours: 'JOURS_SEMAINE', joursSemaine: ['MONDAY'], dates: ['2026-07-10'] }),
      ).joursSemaine,
    ).toEqual(['MONDAY']);
    expect(
      normaliserHoraire(
        horaire({ jours: 'DATES', dates: ['2026-07-10'], joursSemaine: ['MONDAY'] }),
      ).dates,
    ).toEqual(['2026-07-10']);
    expect(
      normaliserHoraire(horaire({ jours: 'PLAGE', dateDebut: '2026-07-10', dateFin: '2026-07-12' }))
        .dateDebut,
    ).toBe('2026-07-10');
  });

  it('normalises the end time of every window, not just the first', () => {
    const regle = horaire({
      fenetres: [
        { heureDebut: '10:00', heureFin: '' },
        { heureDebut: '14:00', heureFin: '' },
      ],
    });

    expect(normaliserHoraire(regle).fenetres.every((fenetre) => fenetre.heureFin === null)).toBe(
      true,
    );
  });

  // The compact line and the two fold states belong to the form, not to the
  // entity: sent along, they would reach a backend that has no such fields.
  it('leaves the editing state of the form behind', () => {
    const normalise = normaliserHoraire({
      ...horaire(),
      saisie: '10:00-12:00',
      deplie: true,
      detail: true,
    });

    expect(normalise).not.toHaveProperty('saisie');
    expect(normalise).not.toHaveProperty('deplie');
    expect(normalise).not.toHaveProperty('detail');
    expect(normalise.fenetres).toEqual(
      horaire().fenetres.map((fenetre) => ({ ...fenetre, effectif: null })),
    );
  });
});

describe('horairesCopiedFrom — la journée type d’un stand reprise dans un autre', () => {
  function modele(): Stand {
    return {
      id: 'PAVILLON',
      nom: 'Pavillon',
      typologiesProposees: ['STRATEGIE'],
      effectifMin: 1,
      effectifMax: 4,
      reserveMajeurs: false,
      horaires: [
        { ...horaire({ id: 7, fenetres: [{ heureDebut: '14:00', heureFin: null, effectif: 3 }] }) },
      ],
      ouvertures: [
        {
          id: 11,
          date: '2026-07-10',
          heureDebut: '10:00',
          heureFin: '12:00',
          motif: 'Inauguration',
          effectif: 2,
        },
      ],
      indisponibilites: [
        { id: 12, date: '2026-07-11', heureDebut: '18:00', heureFin: null, motif: 'Concert' },
      ],
    } as Stand;
  }

  it('copies the three lists, window effectifs included', () => {
    const copie = horairesCopiedFrom(modele());

    expect(copie.horaires).toHaveLength(1);
    expect(copie.horaires[0].fenetres).toEqual([
      { heureDebut: '14:00', heureFin: null, effectif: 3 },
    ]);
    expect(copie.ouvertures[0]).toMatchObject({
      date: '2026-07-10',
      effectif: 2,
      motif: 'Inauguration',
    });
    expect(copie.indisponibilites[0]).toMatchObject({ date: '2026-07-11', motif: 'Concert' });
  });

  // A rule or an exception belongs to the stand it was read from: sent with
  // the source's id, the target would claim rows that are not its own.
  it('resets every id: these are new rows of the target stand', () => {
    const copie = horairesCopiedFrom(modele());

    expect(copie.horaires[0].id).toBeNull();
    expect(copie.ouvertures[0].id).toBeNull();
    expect(copie.indisponibilites[0].id).toBeNull();
  });

  it('never aliases the source: editing the copy must not write through', () => {
    const source = modele();
    const copie = horairesCopiedFrom(source);

    copie.horaires[0].fenetres[0].heureDebut = '09:00';
    copie.ouvertures[0].motif = 'changé';

    expect(source.horaires[0].fenetres[0].heureDebut).toBe('14:00');
    expect(source.ouvertures[0].motif).toBe('Inauguration');
  });

  it('copies an empty schedule as an empty schedule', () => {
    expect(
      horairesCopiedFrom({ ...modele(), horaires: [], ouvertures: [], indisponibilites: [] }),
    ).toEqual({
      horaires: [],
      ouvertures: [],
      indisponibilites: [],
    });
  });
});

describe('windowsBeyondMaximumCount', () => {
  it('counts the windows of the rules and of the dated openings above the maximum', () => {
    const horaires = {
      horaires: [
        horaire({
          fenetres: [
            { heureDebut: '10:00', heureFin: null, effectif: 5 },
            { heureDebut: '14:00', heureFin: null, effectif: 2 },
          ],
        }),
      ],
      ouvertures: [{ ...ouvertureVide(), date: '2026-07-10', heureDebut: '10:00', effectif: 3 }],
    };

    expect(windowsBeyondMaximumCount(horaires, 2)).toBe(2);
    expect(windowsBeyondMaximumCount(horaires, 5)).toBe(0);
  });

  it('ignores a window without effectif, which takes the stand minimum', () => {
    const horaires = {
      horaires: [horaire()],
      ouvertures: [{ ...ouvertureVide(), date: '2026-07-10', heureDebut: '10:00' }],
    };

    expect(windowsBeyondMaximumCount(horaires, 1)).toBe(0);
  });

  // A zero or a fraction is refused for another reason, with its own sentence.
  it('does not count a window whose effectif is invalid on its own', () => {
    const horaires = {
      horaires: [horaire({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 0 }] })],
      ouvertures: [],
    };

    expect(windowsBeyondMaximumCount(horaires, 1)).toBe(0);
  });
});
