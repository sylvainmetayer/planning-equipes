// The pure side of the Consignes page: which dates can still take a consigne,
// the request built from the form and what blocks it, the meal windows
// restated for the day, the merge with what the server proposes, the filters
// and the two bulk moves of the stands list. The wording is tested with
// `core/consigne-wording.ts`.

import { describe, expect, it } from 'vitest';
import { ConsigneEdition, Creneau, LigneStandConsigne, Stand } from '../../core/models';
import {
  ConsigneForm,
  FILTRES_VIDES,
  FenetreSaisie,
  RepasSaisie,
  StandForm,
  alignSoirOnCompensation,
  applyToSelection,
  buildDemande,
  cocherAffiches,
  datesCandidates,
  erreursForm,
  isPast,
  fenetresDemandees,
  fenetresSaisies,
  filterStands,
  formVide,
  formatFenetresSaisie,
  mergePreselection,
  openedStandsCount,
  ouverturesSaisies,
  parseFenetresSaisie,
  followDefaultWindows,
  erreursRepas,
  repasDemande,
  repasFormEmpty,
  repasSaisie,
  repasVide,
} from './consignes';

/** A window as typed, the headcount empty unless given. */
function fenetre(debut: string, fin: string, effectif = ''): FenetreSaisie {
  return { debut, fin, effectif };
}

function creneau(id: number, date: string): Creneau {
  return { id, jour: 1, date, heureDebut: '10:00', heureFin: '12:00' };
}

function ligne(overrides: Partial<LigneStandConsigne> & { standId: string }): LigneStandConsigne {
  return {
    standNom: `Stand ${overrides.standId}`,
    minutesPerdues: 360,
    effectifHerite: 2,
    exceptionDatee: false,
    motif: null,
    preCoche: true,
    ouvertures: [],
    ...overrides,
  };
}

function standForm(overrides: Partial<StandForm> & { standId: string }): StandForm {
  return {
    standNom: `Stand ${overrides.standId}`,
    coche: true,
    minutesPerdues: 360,
    effectifHerite: 2,
    exceptionDatee: false,
    motif: null,
    preCoche: true,
    fenetres: [fenetre('18:00', '22:00')],
    effectifMax: 4,
    ...overrides,
  };
}

function stand(overrides: Partial<Stand> & { id: string }): Stand {
  return {
    nom: overrides.id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...overrides,
  };
}

describe('the hours read into the form and back', () => {
  it('reads the server hours into the form and back, an empty end travelling as null', () => {
    const saisies = fenetresSaisies([
      { debut: '18:00:00', fin: '22:00:00' },
      { debut: '20:00:00', fin: null },
    ]);
    expect(saisies).toEqual([fenetre('18:00', '22:00'), fenetre('20:00', '')]);
    expect(fenetresDemandees(saisies)).toEqual([
      { debut: '18:00', fin: '22:00' },
      { debut: '20:00', fin: null },
    ]);
    // A window with no start is not a window: it is dropped rather than sent.
    expect(fenetresDemandees([fenetre('', '22:00')])).toEqual([]);
  });

  it('reads a stand openings with the headcount typed on each window', () => {
    expect(
      ouverturesSaisies([
        { standId: 'A', debut: '18:00:00', fin: '22:00:00', effectif: 2 },
        { standId: 'A', debut: '09:00:00', fin: null, effectif: null },
      ]),
    ).toEqual([fenetre('18:00', '22:00', '2'), fenetre('09:00', '', '')]);
  });
});

describe('the dates a consigne can be laid on', () => {
  const creneaux = [
    creneau(1, '2026-07-10'),
    creneau(2, '2026-07-10'),
    creneau(3, '2026-07-11'),
    creneau(4, '2026-07-12'),
  ];

  it('offers the grid dates strictly after today, once each, sorted', () => {
    expect(datesCandidates(creneaux, '2026-07-10')).toEqual(['2026-07-11', '2026-07-12']);
  });

  it('offers nothing before today is known', () => {
    expect(datesCandidates(creneaux, null)).toEqual([]);
  });

  it('reads today and before as past, and tomorrow as to come', () => {
    expect(isPast('2026-07-10', '2026-07-10')).toBe(true);
    expect(isPast('2026-07-09', '2026-07-10')).toBe(true);
    expect(isPast('2026-07-11', '2026-07-10')).toBe(false);
    expect(isPast('2026-07-11', null)).toBe(false);
  });

  it('counts a stand once however many windows it holds', () => {
    const consigne: ConsigneEdition = {
      date: '2026-07-11',
      fermetureDebut: '12:00:00',
      fermetureFin: '18:00:00',
      motif: 'Canicule',
      prereglage: null,
      fenetres: [],
      ouvertures: [
        { standId: 'A', debut: '18:00:00', fin: '22:00:00', effectif: null },
        { standId: 'A', debut: '09:00:00', fin: '12:00:00', effectif: null },
        { standId: 'B', debut: '18:00:00', fin: '22:00:00', effectif: 3 },
      ],
      repas: null,
      creneauxAjoutes: [],
      creeLe: null,
      modifieLe: null,
    };
    expect(openedStandsCount(consigne)).toBe(2);
  });
});

describe('the request built from the form', () => {
  const form: ConsigneForm = {
    ...formVide(),
    dates: ['2026-07-12', '2026-07-11'],
    fermetureDebut: '12:00',
    fermetureFin: '',
    motif: '  Arrêté canicule ',
    prereglage: 'Plan canicule',
    fenetres: [fenetre('18:00', '22:00')],
    stands: [
      standForm({ standId: 'A', fenetres: [fenetre('18:00', '22:00', '3')] }),
      standForm({
        standId: 'B',
        fenetres: [fenetre('18:00', '', ' 2 '), fenetre('09:00', '11:00')],
      }),
      standForm({ standId: 'C', coche: false }),
      standForm({ standId: 'D', fenetres: [] }),
    ],
  };

  it('sends one opening per window of every ticked stand, each with its own headcount, and an open end as null', () => {
    const demande = buildDemande(form);

    expect(demande.dates).toEqual(['2026-07-12', '2026-07-11']);
    expect(demande.fermetureDebut).toBe('12:00');
    expect(demande.fermetureFin).toBeNull();
    expect(demande.motif).toBe('Arrêté canicule');
    expect(demande.prereglage).toBe('Plan canicule');
    expect(demande.fenetres).toEqual([{ debut: '18:00', fin: '22:00' }]);
    expect(demande.ouvertures).toEqual([
      { standId: 'A', debut: '18:00', fin: '22:00', effectif: 3 },
      { standId: 'B', debut: '18:00', fin: null, effectif: 2 },
      { standId: 'B', debut: '09:00', fin: '11:00', effectif: null },
    ]);
    // Nothing restated in the meal section: the edition's windows apply, no override travels.
    expect(demande.repas).toBeNull();
  });

  it('round-trips a consigne opening a stand at 2 then at 4 on two windows, unchanged', () => {
    const ouvertures = [
      { standId: 'A', debut: '08:00:00', fin: '10:00:00', effectif: 2 },
      { standId: 'A', debut: '18:00:00', fin: '22:00:00', effectif: 4 },
    ];
    const rows = mergePreselection([ligne({ standId: 'A', ouvertures })], [], []);

    expect(buildDemande({ ...form, stands: rows }).ouvertures).toEqual([
      { standId: 'A', debut: '08:00', fin: '10:00', effectif: 2 },
      { standId: 'A', debut: '18:00', fin: '22:00', effectif: 4 },
    ]);
  });

  it('sends the meal windows restated for the day, an empty field keeping the edition value', () => {
    const demande = buildDemande({
      ...form,
      repas: {
        ...repasVide(),
        soirDebut: '18:00',
        soirFin: '22:00',
        justification: ' Les équipes mangent pendant la fermeture ',
      },
    });

    expect(demande.repas).toEqual({
      midiDebut: null,
      midiFin: null,
      soirDebut: '18:00',
      soirFin: '22:00',
      coupureMinutes: null,
      justification: 'Les équipes mangent pendant la fermeture',
    });
    expect(
      buildDemande({
        ...form,
        repas: { ...repasVide(), coupureMinutes: ' 45 ', justification: 'x' },
      }).repas,
    ).toMatchObject({ coupureMinutes: 45, soirDebut: null });
  });

  // The band above runs to midnight and would swallow the evening windows:
  // the rules below are read on a band that leaves the evening open.
  const validForm: ConsigneForm = { ...form, fermetureFin: '16:00' };

  it('names what blocks the request, in the order of the fields', () => {
    expect(erreursForm(validForm)).toEqual([]);
    expect(erreursForm({ ...validForm, dates: [], motif: ' ' })).toEqual(['DATES', 'MOTIF']);
    expect(erreursForm({ ...validForm, fermetureDebut: '', fermetureFin: 'soir' })).toEqual([
      'FERMETURE_DEBUT',
      'FERMETURE_FIN',
    ]);
    // A window with no start on a ticked stand blocks; the same on an unticked one does not.
    expect(
      erreursForm({
        ...validForm,
        stands: [standForm({ standId: 'A', fenetres: [fenetre('', '22:00')] })],
      }),
    ).toEqual(['FENETRE']);
    expect(
      erreursForm({
        ...validForm,
        stands: [standForm({ standId: 'A', coche: false, fenetres: [fenetre('', '22:00')] })],
      }),
    ).toEqual([]);
    // The meal section's codes come last, after the fields above it.
    expect(
      erreursForm({ ...validForm, motif: '', repas: { ...repasVide(), soirDebut: '18:00' } }),
    ).toEqual(['MOTIF', 'REPAS_FENETRE', 'REPAS_JUSTIFICATION']);
  });

  it('refuses a date the form does not offer — a deep link on a day already begun', () => {
    expect(erreursForm(validForm, ['2026-07-11', '2026-07-12'])).toEqual([]);
    expect(erreursForm(validForm, ['2026-07-12'])).toEqual(['DATES']);
  });

  it('mirrors the server: a window ending at or before its start, or swallowed by the band', () => {
    const errorsWith = (fenetres: FenetreSaisie[]) =>
      erreursForm({
        ...form,
        fermetureFin: '18:00',
        stands: [standForm({ standId: 'A', fenetres })],
      });

    expect(errorsWith([fenetre('22:00', '20:00')])).toEqual(['FENETRE_ORDRE']);
    expect(errorsWith([fenetre('20:00', '20:00')])).toEqual(['FENETRE_ORDRE']);
    // An end of 00:00 is midnight, not a reversed window.
    expect(errorsWith([fenetre('20:00', '00:00')])).toEqual([]);
    expect(errorsWith([fenetre('13:00', '17:00')])).toEqual(['FENETRE_BANDE']);
    expect(errorsWith([fenetre('12:00', '18:00')])).toEqual(['FENETRE_BANDE']);
    // Overlapping the band on one side opens something: allowed.
    expect(errorsWith([fenetre('16:00', '20:00')])).toEqual([]);
    // A band until midnight swallows an evening window whole.
    expect(
      erreursForm({
        ...form,
        fermetureFin: '',
        stands: [standForm({ standId: 'A', fenetres: [fenetre('18:00', '')] })],
      }),
    ).toEqual(['FENETRE_BANDE']);
    // The day's default windows follow the same rules.
    expect(
      erreursForm({ ...form, fermetureFin: '18:00', fenetres: [fenetre('14:00', '16:00')] }),
    ).toEqual(['FENETRE_BANDE']);
  });

  it('refuses a headcount that is not a positive whole number, or above the stand ceiling', () => {
    const errorsWith = (effectif: string, effectifMax: number | null = 4) =>
      erreursForm({
        ...validForm,
        stands: [
          standForm({ standId: 'A', effectifMax, fenetres: [fenetre('18:00', '22:00', effectif)] }),
        ],
      });

    expect(errorsWith('')).toEqual([]);
    expect(errorsWith('4')).toEqual([]);
    expect(errorsWith('0')).toEqual(['EFFECTIF']);
    expect(errorsWith('-1')).toEqual(['EFFECTIF']);
    expect(errorsWith('2.5')).toEqual(['EFFECTIF']);
    expect(errorsWith('abc')).toEqual(['EFFECTIF']);
    expect(errorsWith('5')).toEqual(['EFFECTIF_MAX']);
    // A stand the store does not know has no ceiling to check against.
    expect(errorsWith('50', null)).toEqual([]);
    // An unticked stand's headcount is not judged.
    expect(
      erreursForm({
        ...validForm,
        stands: [
          standForm({ standId: 'A', coche: false, fenetres: [fenetre('18:00', '22:00', '0')] }),
        ],
      }),
    ).toEqual([]);
  });
});

describe('the meal windows restated for the day', () => {
  const justified = (patch: Partial<RepasSaisie>): RepasSaisie => ({
    ...repasVide(),
    justification: 'Les équipes mangent pendant la fermeture',
    ...patch,
  });

  it('reads what a consigne or a preset carries into the fields, and null as nothing typed', () => {
    expect(repasSaisie(null)).toEqual(repasVide());
    expect(repasFormEmpty(repasVide())).toBe(true);
    const saisie = repasSaisie({
      midiDebut: null,
      midiFin: null,
      soirDebut: '18:00:00',
      soirFin: '22:00:00',
      coupureMinutes: 45,
      justification: 'Fermeture',
    });
    expect(saisie).toEqual({
      midiDebut: '',
      midiFin: '',
      soirDebut: '18:00',
      soirFin: '22:00',
      coupureMinutes: '45',
      justification: 'Fermeture',
    });
    expect(repasFormEmpty(saisie)).toBe(false);
    // A justification alone restates nothing: the edition's windows apply.
    expect(repasFormEmpty({ ...repasVide(), justification: 'x' })).toBe(true);
    expect(repasDemande({ ...repasVide(), justification: 'x' })).toBeNull();
  });

  it('blocks a window with one bound, a break that is not a positive whole number, and a missing justification', () => {
    expect(erreursRepas(repasVide())).toEqual([]);
    expect(erreursRepas(justified({ soirDebut: '18:00', soirFin: '22:00' }))).toEqual([]);
    expect(erreursRepas(justified({ midiDebut: '12:00' }))).toEqual(['REPAS_FENETRE']);
    expect(erreursRepas(justified({ soirDebut: '18:00', soirFin: 'soir' }))).toEqual([
      'REPAS_FENETRE',
    ]);
    expect(erreursRepas(justified({ coupureMinutes: '0' }))).toEqual(['REPAS_COUPURE']);
    expect(erreursRepas(justified({ coupureMinutes: '4.5' }))).toEqual(['REPAS_COUPURE']);
    expect(erreursRepas(justified({ coupureMinutes: '30' }))).toEqual([]);
    expect(erreursRepas(justified({ soirDebut: '21:00', soirFin: '19:00' }))).toEqual([
      'REPAS_ORDRE',
    ]);
    expect(erreursRepas(justified({ midiDebut: '12:00', midiFin: '12:00' }))).toEqual([
      'REPAS_ORDRE',
    ]);
    expect(
      erreursRepas({ ...repasVide(), soirDebut: '18:00', soirFin: '22:00', justification: ' ' }),
    ).toEqual(['REPAS_JUSTIFICATION']);
    expect(erreursRepas({ ...repasVide(), midiDebut: '12:00', coupureMinutes: '-1' })).toEqual([
      'REPAS_FENETRE',
      'REPAS_COUPURE',
      'REPAS_JUSTIFICATION',
    ]);
  });

  it('aligns the evening on the earliest start and the latest end of the compensation, and fills an empty justification', () => {
    const aligned = alignSoirOnCompensation(repasVide(), [
      fenetre('19:00', '21:00'),
      fenetre('18:00', '20:00'),
      fenetre('20:00', '22:00'),
    ]);
    expect(aligned).toMatchObject({
      soirDebut: '18:00',
      soirFin: '22:00',
      midiDebut: '',
      coupureMinutes: '',
      justification: 'Les équipes mangent pendant la fermeture',
    });
    expect(erreursRepas(aligned)).toEqual([]);

    // A justification already typed is kept; an open end reads as the last minute of the day.
    const kept = alignSoirOnCompensation(justified({ justification: 'Arrêté' }), [
      fenetre('20:00', ''),
    ]);
    expect(kept).toMatchObject({ soirDebut: '20:00', soirFin: '23:59', justification: 'Arrêté' });

    // Without a readable window, nothing moves.
    const untouched = justified({ midiDebut: '12:00', midiFin: '14:00' });
    expect(alignSoirOnCompensation(untouched, [])).toBe(untouched);
    expect(alignSoirOnCompensation(untouched, [fenetre('', '')])).toBe(untouched);
  });
});

describe('merging the stands the server proposes', () => {
  const defauts = [fenetre('18:00', '22:00')];

  it('ticks a proposed stand with the default windows, and sets aside one with dated hours', () => {
    const rows = mergePreselection(
      [
        ligne({ standId: 'A' }),
        ligne({ standId: 'B', preCoche: false, minutesPerdues: 0 }),
        ligne({ standId: 'C', exceptionDatee: true, motif: 'Horaires posés', preCoche: false }),
      ],
      defauts,
      [],
    );

    expect(rows.map((row) => [row.standId, row.coche])).toEqual([
      ['A', true],
      ['B', false],
      ['C', false],
    ]);
    expect(rows[0].fenetres).toEqual(defauts);
    expect(rows[0].fenetres).not.toBe(defauts);
    expect(rows[2].motif).toBe('Horaires posés');
    expect(rows[2].effectifMax).toBeNull();
  });

  it('reads each stand ceiling from the referential, for the headcount rule', () => {
    const rows = mergePreselection(
      [ligne({ standId: 'A' }), ligne({ standId: 'Z' })],
      defauts,
      [],
      [],
      new Map([['A', 3]]),
    );
    expect(rows.map((row) => row.effectifMax)).toEqual([3, null]);
  });

  it('starts from what the date already opens on a stand, headcount of each window included', () => {
    const rows = mergePreselection(
      [
        ligne({
          standId: 'A',
          preCoche: false,
          ouvertures: [
            { standId: 'A', debut: '19:00:00', fin: null, effectif: 4 },
            { standId: 'A', debut: '09:00:00', fin: '11:00:00', effectif: null },
          ],
        }),
      ],
      defauts,
      [],
    );

    expect(rows[0].coche).toBe(true);
    expect(rows[0].fenetres).toEqual([fenetre('19:00', '', '4'), fenetre('09:00', '11:00')]);
  });

  it('seeds a stand from the openings given for it — a row being modified or prolonged', () => {
    const rows = mergePreselection(
      [ligne({ standId: 'A', preCoche: false }), ligne({ standId: 'B' })],
      defauts,
      [],
      [{ standId: 'A', debut: '20:00:00', fin: '23:00:00', effectif: 2 }],
    );

    expect(rows[0]).toMatchObject({
      coche: true,
      fenetres: [fenetre('20:00', '23:00', '2')],
    });
    expect(rows[1]).toMatchObject({ coche: true, fenetres: defauts });
  });

  it('keeps what was chosen for a stand across a re-read, and drops the stands the server no longer lists', () => {
    const precedents = [
      standForm({
        standId: 'A',
        coche: false,
        fenetres: [fenetre('20:00', '', '5')],
      }),
      standForm({ standId: 'Z' }),
    ];
    const rows = mergePreselection(
      [ligne({ standId: 'A', minutesPerdues: 120 }), ligne({ standId: 'B' })],
      defauts,
      precedents,
    );

    expect(rows.map((row) => row.standId)).toEqual(['A', 'B']);
    expect(rows[0]).toMatchObject({
      coche: false,
      fenetres: [fenetre('20:00', '', '5')],
      // What the server says of the stand is refreshed even so.
      minutesPerdues: 120,
    });
    expect(rows[1]).toMatchObject({ coche: true, fenetres: defauts });
  });

  it('makes the untouched rows follow a change of the default windows, and leaves the typed ones alone', () => {
    const nouvelles = [fenetre('19:00', '23:00')];
    const rows = followDefaultWindows(
      [
        standForm({ standId: 'A' }),
        standForm({ standId: 'B', fenetres: [fenetre('09:00', '12:00')] }),
        // The hours are the defaults, the headcount was typed: the hours move, the headcount stays.
        standForm({ standId: 'C', fenetres: [fenetre('18:00', '22:00', '3')] }),
      ],
      defauts,
      nouvelles,
    );

    expect(rows[0].fenetres).toEqual(nouvelles);
    expect(rows[1].fenetres).toEqual([fenetre('09:00', '12:00')]);
    expect(rows[2].fenetres).toEqual([fenetre('19:00', '23:00', '3')]);
  });
});

describe('the stands list', () => {
  const rows = [
    standForm({ standId: 'A', standNom: 'Échecs géants' }),
    standForm({ standId: 'B', standNom: 'Tir à l’arc', coche: false }),
    standForm({ standId: 'C', standNom: 'Dixit' }),
    standForm({ standId: 'X', standNom: 'Stand disparu' }),
  ];
  const stands = [
    stand({ id: 'A', typologiesProposees: ['STRATEGIE'], premium: true }),
    stand({
      id: 'B',
      typologiesProposees: ['ADRESSE'],
      emplacement: { id: 'HALL', nom: 'Hall', latitude: null, longitude: null },
    }),
    stand({
      id: 'C',
      typologiesProposees: ['STRATEGIE'],
      emplacement: { id: 'HALL', nom: 'Hall', latitude: null, longitude: null },
    }),
  ];

  it('filters by name without accents or case, by category, by location and by premium tier', () => {
    const ids = (recherche = '', reste: Partial<typeof FILTRES_VIDES> = {}) =>
      filterStands(rows, stands, { ...FILTRES_VIDES, recherche, ...reste }).map((r) => r.standId);

    expect(ids()).toEqual(['A', 'B', 'C', 'X']);
    expect(ids('echecs')).toEqual(['A']);
    expect(ids('arc tir')).toEqual(['B']);
    expect(ids('', { typologie: 'STRATEGIE' })).toEqual(['A', 'C']);
    expect(ids('', { emplacement: 'HALL' })).toEqual(['B', 'C']);
    expect(ids('', { premium: 'PREMIUM' })).toEqual(['A']);
    // A stand the referential no longer knows is neither premium nor anything else, but stays by name.
    expect(ids('', { premium: 'STANDARD' })).toEqual(['B', 'C', 'X']);
    expect(ids('disparu')).toEqual(['X']);
  });

  it('ticks and unticks the displayed rows only', () => {
    const affiches = new Set(['A', 'B']);
    const coches = cocherAffiches(rows, affiches, true);
    expect(coches.map((row) => row.coche)).toEqual([true, true, true, true]);
    const decoches = cocherAffiches(rows, affiches, false);
    expect(decoches.map((row) => row.coche)).toEqual([false, false, true, true]);
  });

  it('applies windows and a headcount to every window of the displayed ticked rows, and only what was given', () => {
    const affiches = new Set(['A', 'B', 'C']);
    const fenetres = [fenetre('09:00', '11:00'), fenetre('18:00', '')];

    const withWindows = applyToSelection(rows, affiches, { fenetres });
    expect(withWindows[0].fenetres).toEqual(fenetres);
    // Unticked: untouched. Hidden: untouched.
    expect(withWindows[1].fenetres).toEqual(rows[1].fenetres);
    expect(withWindows[3].fenetres).toEqual(rows[3].fenetres);

    const withEffectif = applyToSelection(withWindows, affiches, { effectif: '4' });
    expect(withEffectif[0].fenetres).toEqual([
      fenetre('09:00', '11:00', '4'),
      fenetre('18:00', '', '4'),
    ]);
    expect(withEffectif[2].fenetres).toEqual([
      fenetre('09:00', '11:00', '4'),
      fenetre('18:00', '', '4'),
    ]);
    expect(withEffectif[1].fenetres).toEqual(rows[1].fenetres);

    // Windows alone keep the headcount typed on the window of the same rank.
    const rehoused = applyToSelection(withEffectif, affiches, { fenetres: [fenetre('20:00', '')] });
    expect(rehoused[0].fenetres).toEqual([fenetre('20:00', '', '4')]);

    const remisAHeriter = applyToSelection(withEffectif, affiches, { effectif: '' });
    expect(remisAHeriter[0].fenetres.map((f) => f.effectif)).toEqual(['', '']);
  });

  it('reads the bulk line, an open end for midnight, and refuses anything unreadable whole', () => {
    expect(parseFenetresSaisie('18h-22h, 9:00-12:00; 20h-')).toEqual([
      fenetre('18:00', '22:00'),
      fenetre('09:00', '12:00'),
      fenetre('20:00', ''),
    ]);
    expect(parseFenetresSaisie('')).toEqual([]);
    expect(parseFenetresSaisie('18h-22h, soir')).toBeNull();
    expect(parseFenetresSaisie('18h-25h')).toBeNull();
    expect(formatFenetresSaisie([fenetre('18:00', '22:00'), fenetre('20:00', '')])).toBe(
      '18:00-22:00, 20:00-',
    );
  });
});
