import { describe, expect, it } from 'vitest';
import { LayerCell, OpeningLayers, RapportOuvertures } from '../../core/models';
import {
  COUCHES,
  buildCalendrierCouches,
  explainCell,
  neighbourPage,
  pageDays,
  readCouchesParam,
  toggleCouche,
  writeCouchesParam,
} from './calendrier-couches';
import { buildJourneeStands } from './journee-stands';

const SAMEDI = '2026-07-11';
const DIMANCHE = '2026-07-12';

/** Two days of one timeslot, 14-20; stand A open 14-18 on Saturday (a consigne took 18-20), 14-20 on Sunday. */
function rapport(): RapportOuvertures {
  const jour = (date: string, numero: number, id: number) => ({
    date,
    jour: numero,
    heureDebut: '14:00',
    heureFin: '20:00',
    minutes: 360,
    nombreCreneaux: 1,
    ferie: null,
    creneaux: [{ id, tranche: 0, heureDebut: '14:00', heureFin: '20:00', couverturePause: false }],
  });
  const cellule = (date: string, id: number, fin: string) => ({
    date,
    etat: 'OUVERT_PARTIEL' as const,
    source: 'REGLE' as const,
    fenetres: [{ heureDebut: '14:00', heureFin: fin }],
    minutesOuvertes: 240,
    minutesAmplitude: 360,
    postes: 3,
    creneaux: [
      {
        creneauId: id,
        tranche: 0,
        effectif: 3,
        partiel: true,
        segments: [{ heureDebut: '14:00', heureFin: fin, effectif: 3 }],
      },
    ],
  });
  return {
    jours: [jour(SAMEDI, 1, 1), jour(DIMANCHE, 2, 2)],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 1,
        jours: [cellule(SAMEDI, 1, '18:00'), cellule(DIMANCHE, 2, '20:00')],
        minutesOuvertes: 600,
        postes: 6,
        modifieLe: null,
      },
      {
        standId: 'B',
        nom: 'Stand B',
        effectifMin: 1,
        jours: [cellule(SAMEDI, 1, '18:00'), cellule(DIMANCHE, 2, '20:00')],
        minutesOuvertes: 600,
        postes: 6,
        modifieLe: null,
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: 12,
    anomalies: [],
  };
}

function celluleSamedi(): LayerCell {
  return {
    date: SAMEDI,
    source: 'REGLE',
    horaireIds: [7],
    motif: null,
    nominal: [{ debutMinutes: 840, finMinutes: 1200, effectif: 3 }],
    reopenings: [],
    effective: [{ debutMinutes: 840, finMinutes: 1080, effectif: 3 }],
  };
}

function couches(): OpeningLayers {
  const dimanche = (source: LayerCell['source']): LayerCell => ({
    date: DIMANCHE,
    source,
    horaireIds: [],
    motif: source === 'EXCEPTION' ? 'Tournoi' : null,
    nominal: [{ debutMinutes: 840, finMinutes: 1200, effectif: 3 }],
    reopenings: [],
    effective: [{ debutMinutes: 840, finMinutes: 1200, effectif: 3 }],
  });
  return {
    jours: [
      {
        date: SAMEDI,
        jour: 1,
        ferie: null,
        vacations: [
          {
            id: 1,
            heureDebut: '14:00',
            heureFin: '20:00',
            debutMinutes: 840,
            finMinutes: 1200,
            couverturePause: false,
            addedByConsigne: false,
          },
        ],
        consigne: {
          fermetureDebut: '18:00',
          fermetureFin: '20:00',
          debutMinutes: 1080,
          finMinutes: 1200,
          motif: 'Arrêté préfectoral',
          prereglage: 'Plan canicule',
        },
      },
      {
        date: DIMANCHE,
        jour: 2,
        ferie: null,
        vacations: [
          {
            id: 2,
            heureDebut: '14:00',
            heureFin: '20:00',
            debutMinutes: 840,
            finMinutes: 1200,
            couverturePause: false,
            addedByConsigne: false,
          },
        ],
        consigne: null,
      },
    ],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 1,
        jours: [celluleSamedi(), dimanche('EXCEPTION')],
      },
      { standId: 'B', nom: 'Stand B', effectifMin: 1, jours: [celluleSamedi(), dimanche('REGLE')] },
    ],
  };
}

describe('calendrier-couches — address', () => {
  it('reads every layer when the param is absent, and writes none back for that default', () => {
    expect(readCouchesParam(null)).toEqual([...COUCHES]);
    expect(writeCouchesParam(COUCHES)).toBeNull();
    expect(readCouchesParam('resultat,inconnue,stand')).toEqual(['stand', 'resultat']);
  });

  it('keeps « no layer » distinct from « every layer »', () => {
    const aucune = toggleCouche(['stand'], 'stand', false);
    expect(aucune).toEqual([]);
    expect(readCouchesParam(writeCouchesParam(aucune))).toEqual([]);
    expect(toggleCouche(['resultat'], 'stand', true)).toEqual(['stand', 'resultat']);
  });

  it('pages the event a week at a time', () => {
    const dates = Array.from(
      { length: 10 },
      (_, index) => `2026-07-${String(index + 1).padStart(2, '0')}`,
    );
    const firstPage = pageDays(dates, null);
    expect(firstPage).toHaveLength(7);
    expect(neighbourPage(dates, firstPage, -1)).toBeNull();
    expect(neighbourPage(dates, firstPage, 1)).toBe('2026-07-08');
    const seconde = pageDays(dates, '2026-07-08');
    expect(seconde).toEqual(['2026-07-08', '2026-07-09', '2026-07-10']);
    expect(neighbourPage(dates, seconde, 1)).toBeNull();
    expect(neighbourPage(dates, seconde, -1)).toBe('2026-07-01');
    // A day between two event days lands on the next one.
    expect(pageDays(['2026-07-01', '2026-07-05'], '2026-07-03')).toEqual(['2026-07-05']);
  });
});

describe('buildCalendrierCouches', () => {
  it('draws the seats exactly as the Journée view does, on the same scale', () => {
    const view = buildCalendrierCouches(couches(), rapport());

    for (const date of [SAMEDI, DIMANCHE]) {
      const journee = buildJourneeStands(rapport(), date)!;
      for (const ligne of view.lignes) {
        const cellule = ligne.cellules.find((each) => each.date === date)!;
        const attendus = journee.lignes.find((each) => each.standId === ligne.standId)!.blocs;
        expect(cellule.resultat).toEqual(attendus);
      }
    }
  });

  it('lays the nominal window, the band and the timeslot on the day scale, and tells an exception from a rule', () => {
    const [a] = buildCalendrierCouches(couches(), rapport()).lignes;
    const [samedi, dimanche] = a.cellules;

    // The scale runs 14:00-20:00: the rule covers it all, the band its last third.
    expect(samedi.nominales).toEqual([
      { offsetPercent: 0, widthPercent: 100, label: '14:00–20:00 · 3', exception: false },
    ]);
    expect(samedi.bande?.offsetPercent).toBeCloseTo(66.67, 1);
    expect(samedi.bande?.widthPercent).toBeCloseTo(33.33, 1);
    expect(samedi.sousConsigne).toBe(true);
    expect(samedi.vacations).toHaveLength(1);
    expect(dimanche.nominales[0].exception).toBe(true);
    expect(dimanche.bande).toBeNull();
    expect(dimanche.sousConsigne).toBe(false);
  });

  it('keeps only the stands the filter keeps', () => {
    const view = buildCalendrierCouches(couches(), rapport(), new Set(['B']));

    expect(view.lignes.map((ligne) => ligne.standId)).toEqual(['B']);
    expect(view.jours.map((jour) => jour.date)).toEqual([SAMEDI, DIMANCHE]);
  });
});

describe('explainCell', () => {
  it('names the rule and the consigne that shaped a day under consigne', () => {
    expect(explainCell(celluleSamedi(), couches().jours[0].consigne)).toBe(
      'Ouvert 14:00–18:00 : règle récurrente 14:00–20:00, amputé par la consigne « Plan canicule » 18:00–20:00',
    );
  });

  it('says a stand without hours is open by default, and a dated exception with its reason', () => {
    const defaut: LayerCell = {
      ...celluleSamedi(),
      source: 'DEFAUT',
      nominal: [{ debutMinutes: 0, finMinutes: 1440, effectif: 1 }],
      effective: [{ debutMinutes: 0, finMinutes: 1440, effectif: 1 }],
    };
    expect(explainCell(defaut, null)).toBe(
      'Ouvert 00:00–24:00 : aucun horaire déclaré, ouvert par défaut',
    );
    const exception = couches().stands[0].jours[1];
    expect(explainCell(exception, null)).toBe(
      'Ouvert 14:00–20:00 : exception datée 14:00–20:00 (Tournoi)',
    );
  });
});
