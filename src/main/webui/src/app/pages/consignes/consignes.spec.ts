// The pure side of the Consignes page: how a band reads, which dates can still
// take a consigne, the request built from the form, the meal windows restated
// for the day, the merge with what the server proposes, the filters and the
// two bulk moves of the stands list.

import { describe, expect, it } from 'vitest';
import { ConsigneEdition, Creneau, LigneStandConsigne, Stand } from '../../core/models';
import {
  ConsigneForm,
  FILTRES_VIDES,
  RepasSaisie,
  StandForm,
  alignSoirOnCompensation,
  applyToSelection,
  bandeLabel,
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
  heureLabel,
  mergePreselection,
  openedStandsCount,
  parseFenetresSaisie,
  followDefaultWindows,
  erreursRepas,
  repasDemande,
  repasFormEmpty,
  repasLabel,
  repasSaisie,
  repasSurcharge,
  repasVide,
} from './consignes';

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
    fenetres: [{ debut: '18:00', fin: '22:00' }],
    effectif: null,
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

describe('the wording of a band', () => {
  it('says an hour the way an organiser does, and midnight for an open end', () => {
    expect(heureLabel('12:00:00')).toBe('12h');
    expect(heureLabel('09:30:00')).toBe('9h30');
    expect(heureLabel('18:00')).toBe('18h');
    expect(bandeLabel('12:00:00', '18:00:00')).toBe('12h–18h');
    expect(bandeLabel('12:00:00', null)).toBe('12h–minuit');
  });

  it('reads the server hours into the form and back, an empty end travelling as null', () => {
    const saisies = fenetresSaisies([
      { debut: '18:00:00', fin: '22:00:00' },
      { debut: '20:00:00', fin: null },
    ]);
    expect(saisies).toEqual([
      { debut: '18:00', fin: '22:00' },
      { debut: '20:00', fin: '' },
    ]);
    expect(fenetresDemandees(saisies)).toEqual([
      { debut: '18:00', fin: '22:00' },
      { debut: '20:00', fin: null },
    ]);
    // A window with no start is not a window: it is dropped rather than sent.
    expect(fenetresDemandees([{ debut: '', fin: '22:00' }])).toEqual([]);
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
    fenetres: [{ debut: '18:00', fin: '22:00' }],
    stands: [
      standForm({ standId: 'A', effectif: 3 }),
      standForm({
        standId: 'B',
        fenetres: [
          { debut: '18:00', fin: '' },
          { debut: '09:00', fin: '11:00' },
        ],
      }),
      standForm({ standId: 'C', coche: false }),
      standForm({ standId: 'D', fenetres: [] }),
    ],
  };

  it('sends one opening per window of every ticked stand, with its headcount, and an open end as null', () => {
    const demande = buildDemande(form);

    expect(demande.dates).toEqual(['2026-07-12', '2026-07-11']);
    expect(demande.fermetureDebut).toBe('12:00');
    expect(demande.fermetureFin).toBeNull();
    expect(demande.motif).toBe('Arrêté canicule');
    expect(demande.prereglage).toBe('Plan canicule');
    expect(demande.fenetres).toEqual([{ debut: '18:00', fin: '22:00' }]);
    expect(demande.ouvertures).toEqual([
      { standId: 'A', debut: '18:00', fin: '22:00', effectif: 3 },
      { standId: 'B', debut: '18:00', fin: null, effectif: null },
      { standId: 'B', debut: '09:00', fin: '11:00', effectif: null },
    ]);
    // Nothing restated in the meal section: the edition's windows apply, no override travels.
    expect(demande.repas).toBeNull();
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

  it('names what blocks the request, in the order of the fields', () => {
    expect(erreursForm(form)).toEqual([]);
    expect(erreursForm({ ...form, dates: [], motif: ' ' })).toEqual(['DATES', 'MOTIF']);
    expect(erreursForm({ ...form, fermetureDebut: '', fermetureFin: 'soir' })).toEqual([
      'FERMETURE_DEBUT',
      'FERMETURE_FIN',
    ]);
    // A window with no start on a ticked stand blocks; the same on an unticked one does not.
    expect(
      erreursForm({
        ...form,
        stands: [standForm({ standId: 'A', fenetres: [{ debut: '', fin: '22:00' }] })],
      }),
    ).toEqual(['FENETRE']);
    expect(
      erreursForm({
        ...form,
        stands: [
          standForm({ standId: 'A', coche: false, fenetres: [{ debut: '', fin: '22:00' }] }),
        ],
      }),
    ).toEqual([]);
    // The meal section's codes come last, after the fields above it.
    expect(
      erreursForm({ ...form, motif: '', repas: { ...repasVide(), soirDebut: '18:00' } }),
    ).toEqual(['MOTIF', 'REPAS_FENETRE', 'REPAS_JUSTIFICATION']);
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
      { debut: '19:00', fin: '21:00' },
      { debut: '18:00', fin: '20:00' },
      { debut: '20:00', fin: '22:00' },
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
      { debut: '20:00', fin: '' },
    ]);
    expect(kept).toMatchObject({ soirDebut: '20:00', soirFin: '23:59', justification: 'Arrêté' });

    // Without a readable window, nothing moves.
    const untouched = justified({ midiDebut: '12:00', midiFin: '14:00' });
    expect(alignSoirOnCompensation(untouched, [])).toBe(untouched);
    expect(alignSoirOnCompensation(untouched, [{ debut: '', fin: '' }])).toBe(untouched);
  });

  it('words the chip: the justification, then what is restated', () => {
    const repas = {
      midiDebut: '12:00:00',
      midiFin: '14:00:00',
      soirDebut: '18:00:00',
      soirFin: '22:00:00',
      coupureMinutes: 45,
      justification: 'Fermeture',
    };
    expect(repasSurcharge(repas)).toBe(true);
    expect(repasSurcharge(null)).toBe(false);
    expect(
      repasSurcharge({
        ...repas,
        midiDebut: null,
        midiFin: null,
        soirDebut: null,
        soirFin: null,
        coupureMinutes: null,
      }),
    ).toBe(false);
    expect(repasLabel(repas)).toBe('Fermeture · midi 12h–14h · soir 18h–22h · coupure 45 min');
    expect(repasLabel({ ...repas, midiDebut: null, midiFin: null, coupureMinutes: null })).toBe(
      'Fermeture · soir 18h–22h',
    );
  });
});

describe('merging the stands the server proposes', () => {
  const defauts = [{ debut: '18:00', fin: '22:00' }];

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
    expect(rows[2].effectif).toBeNull();
  });

  it('starts from what the date already opens on a stand, headcount included', () => {
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
    expect(rows[0].fenetres).toEqual([
      { debut: '19:00', fin: '' },
      { debut: '09:00', fin: '11:00' },
    ]);
    expect(rows[0].effectif).toBe(4);
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
      fenetres: [{ debut: '20:00', fin: '23:00' }],
      effectif: 2,
    });
    expect(rows[1]).toMatchObject({ coche: true, fenetres: defauts, effectif: null });
  });

  it('keeps what was chosen for a stand across a re-read, and drops the stands the server no longer lists', () => {
    const precedents = [
      standForm({
        standId: 'A',
        coche: false,
        fenetres: [{ debut: '20:00', fin: '' }],
        effectif: 5,
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
      fenetres: [{ debut: '20:00', fin: '' }],
      effectif: 5,
      // What the server says of the stand is refreshed even so.
      minutesPerdues: 120,
    });
    expect(rows[1]).toMatchObject({ coche: true, fenetres: defauts });
  });

  it('makes the untouched rows follow a change of the default windows, and leaves the typed ones alone', () => {
    const nouvelles = [{ debut: '19:00', fin: '23:00' }];
    const rows = followDefaultWindows(
      [
        standForm({ standId: 'A' }),
        standForm({ standId: 'B', fenetres: [{ debut: '09:00', fin: '12:00' }] }),
      ],
      defauts,
      nouvelles,
    );

    expect(rows[0].fenetres).toEqual(nouvelles);
    expect(rows[1].fenetres).toEqual([{ debut: '09:00', fin: '12:00' }]);
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

  it('applies windows and a headcount to the displayed ticked rows, and only what was given', () => {
    const affiches = new Set(['A', 'B', 'C']);
    const fenetres = [{ debut: '09:00', fin: '11:00' }];

    const withWindows = applyToSelection(rows, affiches, { fenetres });
    expect(withWindows[0].fenetres).toEqual(fenetres);
    expect(withWindows[0].effectif).toBeNull();
    // Unticked: untouched. Hidden: untouched.
    expect(withWindows[1].fenetres).toEqual(rows[1].fenetres);
    expect(withWindows[3].fenetres).toEqual(rows[3].fenetres);

    const withEffectif = applyToSelection(rows, affiches, { effectif: 4 });
    expect(withEffectif[0]).toMatchObject({ fenetres: rows[0].fenetres, effectif: 4 });
    expect(withEffectif[2].effectif).toBe(4);

    const remisAHeriter = applyToSelection(withEffectif, affiches, { effectif: null });
    expect(remisAHeriter[0].effectif).toBeNull();
  });

  it('reads the bulk line, an open end for midnight, and refuses anything unreadable whole', () => {
    expect(parseFenetresSaisie('18h-22h, 9:00-12:00; 20h-')).toEqual([
      { debut: '18:00', fin: '22:00' },
      { debut: '09:00', fin: '12:00' },
      { debut: '20:00', fin: '' },
    ]);
    expect(parseFenetresSaisie('')).toEqual([]);
    expect(parseFenetresSaisie('18h-22h, soir')).toBeNull();
    expect(parseFenetresSaisie('18h-25h')).toBeNull();
    expect(
      formatFenetresSaisie([
        { debut: '18:00', fin: '22:00' },
        { debut: '20:00', fin: '' },
      ]),
    ).toBe('18:00-22:00, 20:00-');
  });
});
