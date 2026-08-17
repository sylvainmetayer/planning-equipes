import { describe, expect, it } from 'vitest';
import {
  conflitDeMode,
  couvreJour,
  decrireFenetre,
  erreurHoraire,
  horaireVide,
  jourSemaineDe,
  resoudreHoraires,
  resoudreJour,
  resumerHoraires
} from './horaire-stand';
import { HoraireStand, Stand } from './models';

const MESSAGES = {
  fenetreRequise: 'fenetreRequise',
  heureDebutRequise: 'heureDebutRequise',
  fenetreInversee: 'fenetreInversee',
  joursSemaineRequis: 'joursSemaineRequis',
  plageRequise: 'plageRequise',
  datesRequises: 'datesRequises'
};

function regle(patch: Partial<HoraireStand>): HoraireStand {
  return { ...horaireVide(), ...patch };
}

function stand(patch: Partial<Stand>): Stand {
  return {
    id: 'S1',
    nom: 'Stand',
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...patch
  };
}

/** 2026-07-08 is a Wednesday, 2026-07-11 a Saturday — the fixture festival's own calendar. */
const DATES = ['2026-07-08', '2026-07-09', '2026-07-10', '2026-07-11', '2026-07-12'];

describe('jourSemaineDe', () => {
  it('lit le jour de la semaine sans décalage de fuseau', () => {
    expect(jourSemaineDe('2026-07-08')).toBe('WEDNESDAY');
    expect(jourSemaineDe('2026-07-11')).toBe('SATURDAY');
    expect(jourSemaineDe('2026-07-12')).toBe('SUNDAY');
  });
});

describe('couvreJour', () => {
  it('TOUS couvre tout', () => {
    expect(couvreJour(regle({ jours: 'TOUS' }), '2026-07-08')).toBe(true);
  });

  it('JOURS_SEMAINE ne couvre que les jours listés', () => {
    const weekend = regle({ jours: 'JOURS_SEMAINE', joursSemaine: ['SATURDAY', 'SUNDAY'] });
    expect(couvreJour(weekend, '2026-07-11')).toBe(true);
    expect(couvreJour(weekend, '2026-07-08')).toBe(false);
  });

  it('PLAGE inclut ses deux bornes', () => {
    const plage = regle({ jours: 'PLAGE', dateDebut: '2026-07-09', dateFin: '2026-07-11' });
    expect(couvreJour(plage, '2026-07-09')).toBe(true);
    expect(couvreJour(plage, '2026-07-11')).toBe(true);
    expect(couvreJour(plage, '2026-07-12')).toBe(false);
  });

  it('DATES ne couvre que les dates énumérées', () => {
    const dates = regle({ jours: 'DATES', dates: ['2026-07-10'] });
    expect(couvreJour(dates, '2026-07-10')).toBe(true);
    expect(couvreJour(dates, '2026-07-11')).toBe(false);
  });
});

describe('resoudreJour', () => {
  it("laisse le jour ouvert par défaut quand rien ne le concerne", () => {
    const resolu = resoudreJour(stand({}), '2026-07-08');
    expect(resolu.mode).toBeNull();
    expect(resolu.source).toBe('DEFAUT');
  });

  // Le cas AUTRES-BOURSE : une règle, deux fenêtres, douze jours.
  it('applique une règle TOUS à chaque jour, coupure méridienne comprise', () => {
    const bourse = stand({
      horaires: [
        regle({
          mode: 'OUVERTURE',
          jours: 'TOUS',
          fenetres: [
            { heureDebut: '10:00', heureFin: '12:00' },
            { heureDebut: '14:00', heureFin: null }
          ]
        })
      ]
    });

    const resolus = resoudreHoraires(bourse, DATES);

    expect(resolus).toHaveLength(5);
    for (const jour of resolus) {
      expect(jour.mode).toBe('OUVERTURE');
      expect(jour.source).toBe('REGLE');
      expect(jour.fenetres).toEqual([
        { heureDebut: '10:00', heureFin: '12:00' },
        { heureDebut: '14:00', heureFin: null }
      ]);
    }
  });

  it('fait primer la règle la plus spécifique', () => {
    const avecWeekend = stand({
      horaires: [
        regle({ jours: 'TOUS', fenetres: [{ heureDebut: '14:00', heureFin: null }] }),
        regle({
          jours: 'JOURS_SEMAINE',
          joursSemaine: ['SATURDAY'],
          fenetres: [{ heureDebut: '10:00', heureFin: null }]
        })
      ]
    });

    expect(resoudreJour(avecWeekend, '2026-07-08').fenetres).toEqual([{ heureDebut: '14:00', heureFin: null }]);
    expect(resoudreJour(avecWeekend, '2026-07-11').fenetres).toEqual([{ heureDebut: '10:00', heureFin: null }]);
  });

  it("fait primer une exception datée sur les règles, et la signale comme telle", () => {
    const avecException = stand({
      horaires: [regle({ jours: 'TOUS', fenetres: [{ heureDebut: '14:00', heureFin: null }] })],
      indisponibilites: [{ id: null, date: '2026-07-10', heureDebut: '10:00', heureFin: null, motif: 'Férié' }]
    });

    const resolu = resoudreJour(avecException, '2026-07-10');

    expect(resolu.mode).toBe('FERMETURE');
    expect(resolu.source).toBe('EXCEPTION');
    expect(resolu.fenetres).toEqual([{ heureDebut: '10:00', heureFin: null }]);
  });

  it("donne l'ouverture gagnante en cas d'égalité de spécificité — le choix le plus restrictif", () => {
    const ambigu = stand({
      horaires: [
        regle({ mode: 'FERMETURE', jours: 'TOUS', fenetres: [{ heureDebut: '14:00', heureFin: '16:00' }] }),
        regle({ mode: 'OUVERTURE', jours: 'TOUS', fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] })
      ]
    });

    const resolu = resoudreJour(ambigu, '2026-07-08');

    expect(resolu.mode).toBe('OUVERTURE');
    expect(resolu.fenetres).toEqual([{ heureDebut: '10:00', heureFin: '12:00' }]);
  });

  it('ignore une fenêtre à moitié saisie', () => {
    const incomplet = stand({
      horaires: [regle({ jours: 'TOUS', fenetres: [{ heureDebut: '', heureFin: null }] })]
    });

    expect(resoudreJour(incomplet, '2026-07-08').mode).toBeNull();
  });
});

describe('erreurHoraire', () => {
  it('accepte une fenêtre sans heure de fin : jusqu’à la fermeture', () => {
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '14:00', heureFin: null }] }), MESSAGES)).toBeNull();
  });

  it('refuse une fenêtre inversée', () => {
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '18:00', heureFin: '14:00' }] }), MESSAGES)).toBe(
      'fenetreInversee'
    );
  });

  it('refuse un sélecteur sans les données qu’il exige', () => {
    const base = { fenetres: [{ heureDebut: '14:00', heureFin: null }] };
    expect(erreurHoraire(regle({ ...base, jours: 'JOURS_SEMAINE' }), MESSAGES)).toBe('joursSemaineRequis');
    expect(erreurHoraire(regle({ ...base, jours: 'PLAGE' }), MESSAGES)).toBe('plageRequise');
    expect(erreurHoraire(regle({ ...base, jours: 'DATES' }), MESSAGES)).toBe('datesRequises');
  });

  it('refuse une plage dont la fin précède le début', () => {
    const plage = regle({
      jours: 'PLAGE',
      dateDebut: '2026-07-11',
      dateFin: '2026-07-09',
      fenetres: [{ heureDebut: '14:00', heureFin: null }]
    });
    expect(erreurHoraire(plage, MESSAGES)).toBe('plageRequise');
  });
});

describe('conflitDeMode', () => {
  it('signale deux règles de même portée et de modes opposés', () => {
    expect(
      conflitDeMode([regle({ mode: 'OUVERTURE', jours: 'TOUS' }), regle({ mode: 'FERMETURE', jours: 'TOUS' })])
    ).toBe(true);
  });

  it('accepte deux modes opposés dès que les portées diffèrent', () => {
    expect(
      conflitDeMode([
        regle({ mode: 'OUVERTURE', jours: 'TOUS' }),
        regle({ mode: 'FERMETURE', jours: 'DATES', dates: ['2026-07-14'] })
      ])
    ).toBe(false);
  });

  it('accepte deux règles DATES de modes opposés sur des dates disjointes', () => {
    expect(
      conflitDeMode([
        regle({ mode: 'OUVERTURE', jours: 'DATES', dates: ['2026-07-14'] }),
        regle({ mode: 'FERMETURE', jours: 'DATES', dates: ['2026-07-19'] })
      ])
    ).toBe(false);
  });
});

describe('decrireFenetre / resumerHoraires', () => {
  it('nomme la fermeture plutôt qu’une heure inventée', () => {
    expect(decrireFenetre({ heureDebut: '14:00', heureFin: null }, 'fermeture')).toBe('14:00 → fermeture');
    expect(decrireFenetre({ heureDebut: '10:00:00', heureFin: '12:00:00' }, 'fermeture')).toBe('10:00 → 12:00');
  });

  it('résume règles et exceptions au lieu du compte brut de fenêtres', () => {
    const libelles = {
      aucun: '—',
      regles: (n: number) => `${n} règle(s)`,
      exceptions: (n: number) => `${n} exception(s)`
    };
    expect(resumerHoraires(stand({}), libelles)).toBe('—');
    expect(
      resumerHoraires(
        stand({
          horaires: [regle({}), regle({})],
          indisponibilites: [{ id: null, date: '2026-07-10', heureDebut: '10:00', heureFin: null, motif: null }]
        }),
        libelles
      )
    ).toBe('2 règle(s) · 1 exception(s)');
  });
});
