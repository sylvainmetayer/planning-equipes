import { describe, expect, it } from 'vitest';
import { LayerCell, OpeningLayers } from '../../core/models';
import {
  COUCHES,
  explainCell,
  readCouchesParam,
  toggleCouche,
  writeCouchesParam,
} from './calendrier-couches';

const SAMEDI = '2026-07-11';
const DIMANCHE = '2026-07-12';

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
