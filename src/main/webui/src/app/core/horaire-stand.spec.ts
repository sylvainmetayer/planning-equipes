import { describe, expect, it } from 'vitest';
import {
  conflitDeMode,
  couvreJour,
  decrireFenetre,
  erreurHoraire,
  estCasParticulier,
  formaterFenetres,
  horaireVide,
  jourSemaineDe,
  normaliseHour,
  parseFenetres,
  resoudreHoraires,
  resoudreJour,
  resumerHoraires
} from './horaire-stand';
import { HoraireStand, Stand } from './models';

const MESSAGES = {
  fenetreRequise: 'fenetreRequise',
  heureDebutRequise: 'heureDebutRequise',
  fenetreInversee: 'fenetreInversee',
  effectifInvalide: 'effectifInvalide',
  effectifDepasse: (effectif: number, effectifMax: number) => `effectifDepasse ${effectif}>${effectifMax}`,
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

/** 2026-07-08 is a Wednesday, 2026-07-11 a Saturday — the fixture event's own calendar. */
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

    const solved = resoudreHoraires(bourse, DATES);

    expect(solved).toHaveLength(5);
    for (const jour of solved) {
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

  it('accepte une fenêtre sans effectif ou avec un entier d’au moins 1', () => {
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: null }] }), MESSAGES)).toBeNull();
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 1 }] }), MESSAGES)).toBeNull();
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00', effectif: 7 }] }), MESSAGES)).toBeNull();
  });

  it('refuse un effectif de fenêtre nul, négatif ou fractionnaire', () => {
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 0 }] }), MESSAGES)).toBe('effectifInvalide');
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: -1 }] }), MESSAGES)).toBe('effectifInvalide');
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 2.5 }] }), MESSAGES)).toBe('effectifInvalide');
  });

  it('nomme les deux nombres quand une fenêtre dépasse la capacité du stand', () => {
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 4 }] }), MESSAGES, 2)).toBe(
      'effectifDepasse 4>2'
    );
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 2 }] }), MESSAGES, 2)).toBeNull();
    // A zero is a zero before it is "above the maximum".
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '10:00', heureFin: null, effectif: 0 }] }), MESSAGES, 2)).toBe('effectifInvalide');
  });

  it('signale une fenêtre inversée avant son effectif', () => {
    expect(erreurHoraire(regle({ fenetres: [{ heureDebut: '12:00', heureFin: '10:00', effectif: 0 }] }), MESSAGES)).toBe('fenetreInversee');
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

  it('montre l’effectif d’une fenêtre qui en nomme un, et rien sinon', () => {
    expect(decrireFenetre({ heureDebut: '14:00', heureFin: '20:00', effectif: 3 }, 'fermeture')).toBe('14:00 → 20:00 ×3');
    expect(decrireFenetre({ heureDebut: '14:00', heureFin: null, effectif: 1 }, 'fermeture')).toBe('14:00 → fermeture ×1');
    expect(decrireFenetre({ heureDebut: '14:00', heureFin: null, effectif: null }, 'fermeture')).toBe('14:00 → fermeture');
    expect(decrireFenetre({ heureDebut: '14:00', heureFin: null, effectif: undefined }, 'fermeture')).toBe('14:00 → fermeture');
  });

  it('garde l’effectif des fenêtres quand il résout un jour', () => {
    const jour = resoudreJour(
      stand({
        horaires: [regle({ fenetres: [{ heureDebut: '14:00', heureFin: '20:00', effectif: 4 }, { heureDebut: '10:00', heureFin: '12:00' }] })]
      }),
      '2026-07-10'
    );

    expect(jour.fenetres.map((fenetre) => fenetre.effectif)).toEqual([undefined, 4]);
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

describe('parseFenetres', () => {
  it('lit la ligne compacte que les outils MCP acceptent déjà', () => {
    expect(parseFenetres('10:00-12:00@2,14:00-')).toEqual({
      erreur: null,
      morceau: null,
      fenetres: [
        { heureDebut: '10:00', heureFin: '12:00', effectif: 2 },
        { heureDebut: '14:00', heureFin: null, effectif: null }
      ]
    });
  });

  it('tolère les espaces, le point-virgule et les heures écrites à la main', () => {
    const saisie = parseFenetres(' 9h-12h30 ; 14 - 18:50 @ 3 ');
    expect(saisie.fenetres).toEqual([
      { heureDebut: '09:00', heureFin: '12:30', effectif: null },
      { heureDebut: '14:00', heureFin: '18:50', effectif: 3 }
    ]);
  });

  it('accepte le tiret cadratin et la flèche que l’aperçu affiche', () => {
    expect(parseFenetres('10:00 → 12:00, 14:00 – 20:00').fenetres?.map((f) => f.heureFin)).toEqual(['12:00', '20:00']);
  });

  it('refuse une ligne vide, en nommant l’erreur plutôt que de renvoyer zéro fenêtre', () => {
    expect(parseFenetres('')).toMatchObject({ fenetres: null, erreur: 'VIDE' });
    expect(parseFenetres(' , ; ')).toMatchObject({ fenetres: null, erreur: 'VIDE' });
  });

  it('nomme le morceau fautif d’une fenêtre sans séparateur', () => {
    expect(parseFenetres('10:00-12:00, 14:00')).toMatchObject({ erreur: 'FORME', morceau: '14:00' });
  });

  it('refuse une heure illisible ou hors du cadran', () => {
    expect(parseFenetres('10:00-25:00')).toMatchObject({ erreur: 'HEURE', morceau: '10:00-25:00' });
    expect(parseFenetres('dix-12:00')).toMatchObject({ erreur: 'HEURE' });
    expect(parseFenetres('10:60-12:00')).toMatchObject({ erreur: 'HEURE' });
  });

  it('refuse un effectif nul, non entier ou absent après le @', () => {
    expect(parseFenetres('10:00-12:00@0')).toMatchObject({ erreur: 'EFFECTIF', morceau: '10:00-12:00@0' });
    expect(parseFenetres('10:00-12:00@2.5')).toMatchObject({ erreur: 'EFFECTIF' });
    expect(parseFenetres('10:00-12:00@')).toMatchObject({ erreur: 'EFFECTIF' });
  });

  // A window may not cross midnight, so a reversed window is not the parser's
  // business: it comes out as typed and `erreurHoraire` refuses it, with the
  // same sentence as the detailed rows.
  it('laisse une fenêtre inversée au validateur des règles', () => {
    const saisie = parseFenetres('18:00-14:00');
    expect(saisie.erreur).toBeNull();
    expect(erreurHoraire(regle({ fenetres: saisie.fenetres! }), MESSAGES)).toBe('fenetreInversee');
  });
});

describe('normaliserHeure', () => {
  it('ramène toute écriture usuelle à HH:MM', () => {
    expect(normaliseHour('9')).toBe('09:00');
    expect(normaliseHour('9h')).toBe('09:00');
    expect(normaliseHour('09h05')).toBe('09:05');
    expect(normaliseHour('23.59')).toBe('23:59');
    expect(normaliseHour('0:00')).toBe('00:00');
  });

  // « 9:5 » se lit 9 h 50 pour l'un et 9 h 05 pour l'autre : deviner réécrirait
  // une heure que l'utilisateur croit avoir saisie.
  it('refuse une minute à un seul chiffre plutôt que de la compléter', () => {
    expect(normaliseHour('9:5')).toBeNull();
    expect(normaliseHour('9h5')).toBeNull();
    expect(normaliseHour('9.5')).toBeNull();
    expect(normaliseHour('09:05')).toBe('09:05');
  });

  it('refuse ce qui n’est pas une heure du jour', () => {
    expect(normaliseHour('24:00')).toBeNull();
    expect(normaliseHour('12:60')).toBeNull();
    expect(normaliseHour('midi')).toBeNull();
    expect(normaliseHour('')).toBeNull();
    expect(normaliseHour('1:2:3')).toBeNull();
  });
});

describe('formaterFenetres', () => {
  it('écrit ce que parseFenetres relit à l’identique', () => {
    const fenetres = [
      { heureDebut: '10:00', heureFin: '12:00', effectif: 2 },
      { heureDebut: '14:00', heureFin: null, effectif: null }
    ];
    const ligne = formaterFenetres(fenetres);
    expect(ligne).toBe('10:00-12:00@2, 14:00-');
    expect(parseFenetres(ligne).fenetres).toEqual(fenetres);
  });

  it('raccourcit les heures à la seconde et laisse de côté une fenêtre sans début', () => {
    expect(formaterFenetres([{ heureDebut: '10:00:00', heureFin: '12:00:00' }])).toBe('10:00-12:00');
    // Une ligne « - » était refusée par le parseur de ce même fichier à la
    // frappe suivante : la fenêtre à moitié saisie reste dans la règle, et
    // c'est « heure de début » qui la signale.
    expect(formaterFenetres([{ heureDebut: '', heureFin: null }])).toBe('');
    expect(
      formaterFenetres([{ heureDebut: '10:00', heureFin: '12:00' }, { heureDebut: '', heureFin: null }])
    ).toBe('10:00-12:00');
    expect(formaterFenetres([])).toBe('');
  });
});

describe('estCasParticulier', () => {
  it('ne l’est pas pour une règle ouverte tous les jours sans motif', () => {
    expect(estCasParticulier(regle({}))).toBe(false);
    expect(estCasParticulier(regle({ motif: '' }))).toBe(false);
  });

  it('l’est dès que la règle ferme, cible des jours ou porte un motif', () => {
    expect(estCasParticulier(regle({ mode: 'FERMETURE' }))).toBe(true);
    expect(estCasParticulier(regle({ jours: 'JOURS_SEMAINE', joursSemaine: ['MONDAY'] }))).toBe(true);
    expect(estCasParticulier(regle({ jours: 'PLAGE' }))).toBe(true);
    expect(estCasParticulier(regle({ jours: 'DATES' }))).toBe(true);
    expect(estCasParticulier(regle({ motif: 'canicule' }))).toBe(true);
  });
});
