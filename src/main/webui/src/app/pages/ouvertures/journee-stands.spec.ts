import { describe, expect, it } from 'vitest';
import { RapportOuvertures } from '../../core/models';
import { buildJourneeStands, minutesDe, pasHoraire } from './journee-stands';

function rapport(): RapportOuvertures {
  return {
    jours: [
      {
        date: '2027-07-12',
        jour: 1,
        heureDebut: '09:00',
        heureFin: '20:00',
        minutes: 660,
        nombreCreneaux: 3,
        creneaux: [
          { id: 1, tranche: 0, heureDebut: '09:00', heureFin: '12:00', couverturePause: false },
          { id: 2, tranche: 0, heureDebut: '12:00', heureFin: '13:00', couverturePause: true },
          { id: 3, tranche: 0, heureDebut: '14:00', heureFin: '20:00', couverturePause: false },
        ],
      },
    ],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 2,
        jours: [
          {
            date: '2027-07-12',
            etat: 'OUVERT_PARTIEL',
            source: 'REGLE',
            fenetres: [{ heureDebut: '09:00', heureFin: '13:00' }],
            minutesOuvertes: 240,
            minutesAmplitude: 660,
            postes: 3,
            creneaux: [
              {
                creneauId: 1,
                tranche: 0,
                effectif: 2,
                partiel: false,
                segments: [{ heureDebut: '09:00', heureFin: '12:00', effectif: 2 }],
              },
              {
                creneauId: 2,
                tranche: 0,
                effectif: 2,
                partiel: false,
                segments: [{ heureDebut: '12:00', heureFin: '13:00', effectif: 2 }],
              },
              { creneauId: 3, tranche: 0, effectif: null, partiel: false, segments: [] },
            ],
          },
        ],
        minutesOuvertes: 240,
        postes: 3,
        modifieLe: null,
      },
      {
        standId: 'B',
        nom: 'Stand B',
        effectifMin: 1,
        jours: [
          {
            date: '2027-07-12',
            etat: 'FERME',
            source: 'EXCEPTION',
            fenetres: [],
            minutesOuvertes: 0,
            minutesAmplitude: 660,
            postes: 0,
            creneaux: [],
          },
        ],
        minutesOuvertes: 0,
        postes: 0,
        modifieLe: null,
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: 3,
    anomalies: [
      {
        type: 'FENETRE_SANS_EFFET',
        standId: 'B',
        standNom: 'Stand B',
        date: '2027-07-12',
        heureDebut: '07:00',
        heureFin: '08:00',
        message:
          "L'ouverture de 07:00 à 08:00 ne recoupe aucun créneau de ce jour : elle ne change rien.",
      },
    ],
  };
}

describe('buildJourneeStands', () => {
  it('lays the créneaux as bands and each open stretch as a block with its headcount and relay flag', () => {
    const vue = buildJourneeStands(rapport(), '2027-07-12')!;

    // Stretched to 07:00 so the window outside the grid is seen; whole hours.
    expect(vue.debutMinutes).toBe(7 * 60);
    expect(vue.finMinutes).toBe(20 * 60);
    expect(vue.heures.map((heure) => heure.label)).toEqual([
      '07:00',
      '08:00',
      '09:00',
      '10:00',
      '11:00',
      '12:00',
      '13:00',
      '14:00',
      '15:00',
      '16:00',
      '17:00',
      '18:00',
      '19:00',
      '20:00',
    ]);
    expect(vue.bandes.map((bande) => [bande.label, bande.couverturePause])).toEqual([
      ['09:00–12:00', false],
      ['12:00–13:00', true],
      ['14:00–20:00', false],
    ]);
    const standA = vue.lignes[0];
    expect(
      standA.blocs.map((bloc) => [bloc.heureDebut, bloc.effectif, bloc.couverturePause]),
    ).toEqual([
      ['09:00', 2, false],
      ['12:00', 2, true],
    ]);
    // 13 hours on the scale: the 09:00 block starts two hours in.
    expect(standA.blocs[0].offsetPercent).toBeCloseTo((2 / 13) * 100);
    expect(standA.blocs[0].widthPercent).toBeCloseTo((3 / 13) * 100);
    expect(standA.resume).toBe('Stand A : ouvert 09:00–12:00 (2), 12:00–13:00 (2)');
  });

  it('hatches a window outside every vacation where it falls, and says so', () => {
    const vue = buildJourneeStands(rapport(), '2027-07-12')!;
    const standB = vue.lignes[1];

    expect(standB.etat).toBe('FERME');
    expect(standB.blocs).toEqual([]);
    expect(standB.horsGrille).toHaveLength(1);
    expect(standB.horsGrille[0].offsetPercent).toBe(0);
    expect(standB.horsGrille[0].widthPercent).toBeCloseTo((1 / 13) * 100);
    expect(standB.resume).toContain('hors de toute vacation 07:00–08:00');
  });

  it('narrows to the stands the grid filter keeps, and answers null for an unknown day', () => {
    expect(buildJourneeStands(rapport(), '2027-07-12', new Set(['B']))!.lignes).toHaveLength(1);
    expect(buildJourneeStands(rapport(), '2027-07-13')).toBeNull();
  });

  it('reads an evening crossing midnight as running past 24 h', () => {
    expect(minutesDe('20:00:00')).toBe(1200);
    const nuit = rapport();
    nuit.jours[0] = { ...nuit.jours[0], heureDebut: '20:00', heureFin: '00:00', creneaux: [] };
    nuit.anomalies = [];
    const vue = buildJourneeStands(nuit, '2027-07-12')!;
    expect(vue.finMinutes).toBe(24 * 60);
    expect(pasHoraire(vue)).toBe('25% 100%');
  });
});
