import { describe, expect, it } from 'vitest';
import { AnomalieGrille, RapportGrille } from '../../core/models';
import {
  bilanGrille,
  datesFromText,
  erreursIntroduites,
  grilleBloquee,
  gridAnomalyIcon,
  regleDepuis,
  serieVide,
  signatureSerie,
  trierAnomalies
} from './grille-creneaux';

function anomaly(patch: Partial<AnomalieGrille>): AnomalieGrille {
  return { severite: 'AVERTISSEMENT', type: 'TROU_DANS_LA_JOURNEE', date: '2026-07-08', message: 'trou', ...patch };
}

function rapport(anomalies: AnomalieGrille[], faisable: boolean | null = true): RapportGrille {
  return {
    mode: 'AMPLITUDES',
    nombreCreneaux: 3,
    anomalies,
    ouvertures: [],
    faisabilite: faisable === null ? null : { feasible: faisable, manqueAnimateurs: 0, causes: [], totalCauses: 0, message: '' }
  };
}

describe('regleDepuis', () => {
  it('lit une journée type sur une ligne et une plage de jours', () => {
    const resultat = regleDepuis({ ...serieVide(), fenetres: '9h-12h, 14:00-18:00', dateDebut: '2026-07-06', dateFin: '2026-07-19', exclusions: '2026-07-14' });

    expect(resultat.erreur).toBeNull();
    expect(resultat.regle).toEqual({
      jours: 'TOUS',
      dateDebut: '2026-07-06',
      dateFin: '2026-07-19',
      joursSemaine: [],
      dates: [],
      exclusions: ['2026-07-14'],
      fenetres: [
        { heureDebut: '09:00', heureFin: '12:00' },
        { heureDebut: '14:00', heureFin: '18:00' }
      ]
    });
  });

  it('ne garde que les jours de semaine cochés, et que les dates listées', () => {
    const semaine = regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', jours: 'JOURS_SEMAINE', joursSemaine: ['MONDAY'], dateDebut: '2026-07-06', dateFin: '2026-07-19', dates: '2026-07-01' });
    expect(semaine.regle?.joursSemaine).toEqual(['MONDAY']);
    expect(semaine.regle?.dates).toEqual([]);

    const dates = regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', jours: 'DATES', dates: '2026-07-14, 2026-07-19', dateDebut: '2026-01-01' });
    expect(dates.regle?.dates).toEqual(['2026-07-14', '2026-07-19']);
    expect(dates.regle?.dateDebut).toBeNull();
  });

  it('refuse, en nommant le morceau, une fenêtre sans fin ou avec un effectif', () => {
    expect(regleDepuis({ ...serieVide(), fenetres: '09:00-', dateDebut: '2026-07-06', dateFin: '2026-07-07' })).toMatchObject({ erreur: 'FIN_REQUISE', morceau: '09:00' });
    expect(regleDepuis({ ...serieVide(), fenetres: '09:00-12:00@2', dateDebut: '2026-07-06', dateFin: '2026-07-07' })).toMatchObject({ erreur: 'EFFECTIF_REFUSE', morceau: '09:00-12:00@2' });
    expect(regleDepuis({ ...serieVide(), fenetres: '', dateDebut: '2026-07-06', dateFin: '2026-07-07' })).toMatchObject({ erreur: 'FENETRES_VIDES' });
    expect(regleDepuis({ ...serieVide(), fenetres: 'matin', dateDebut: '2026-07-06', dateFin: '2026-07-07' })).toMatchObject({ erreur: 'FENETRE_ILLISIBLE', morceau: 'matin' });
  });

  it('refuse un sélecteur sans les données qu’il exige', () => {
    expect(regleDepuis({ ...serieVide(), fenetres: '10:00-12:00' })).toMatchObject({ erreur: 'PLAGE_REQUISE' });
    expect(regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', dateDebut: '2026-07-09', dateFin: '2026-07-06' })).toMatchObject({ erreur: 'PLAGE_INVERSEE' });
    expect(regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', jours: 'JOURS_SEMAINE', dateDebut: '2026-07-06', dateFin: '2026-07-09' })).toMatchObject({ erreur: 'JOURS_SEMAINE_REQUIS' });
    expect(regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', jours: 'DATES', dates: '' })).toMatchObject({ erreur: 'DATES_REQUISES' });
  });

  // Silencieusement ignorée avant : « 14/07/2026 » en exclusion laissait créer
  // les créneaux du 14 juillet sans un mot.
  it('refuse une date qui n’en est pas une, plutôt que de l’ignorer', () => {
    expect(
      regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', dateDebut: '2026-07-06', dateFin: '2026-07-19', exclusions: '14/07/2026' })
    ).toMatchObject({ erreur: 'DATES_ILLISIBLES', morceau: '14/07/2026' });
    expect(
      regleDepuis({ ...serieVide(), fenetres: '10:00-12:00', jours: 'DATES', dates: '2026-07-14, lundi' })
    ).toMatchObject({ erreur: 'DATES_ILLISIBLES', morceau: 'lundi' });
  });

  it('change de signature dès qu’un champ change', () => {
    const a = serieVide();
    expect(signatureSerie(a)).toBe(signatureSerie({ ...a }));
    expect(signatureSerie(a)).not.toBe(signatureSerie({ ...a, exclusions: '2026-07-14' }));
  });

  it('lit des dates séparées par des virgules, des points-virgules ou des espaces', () => {
    expect(datesFromText('2026-07-14,2026-07-15; 2026-07-16 2026-07-17 x')).toEqual(['2026-07-14', '2026-07-15', '2026-07-16', '2026-07-17']);
  });
});

describe('verdict', () => {
  it('compte erreurs, avertissements et ouvertures, et lit la faisabilité', () => {
    const bilan = bilanGrille({ ...rapport([anomaly({ severite: 'ERREUR' }), anomaly({})], false), ouvertures: [{ type: 'STAND_JAMAIS_OUVERT', standId: 'S', standNom: 'S', date: null, message: '' }] });
    expect(bilan).toEqual({ erreurs: 1, avertissements: 1, ouvertures: 1, faisable: false });
    expect(bilanGrille(rapport([], null)).faisable).toBeNull();
  });

  it('ne bloque que sur une erreur', () => {
    expect(grilleBloquee(null)).toBe(false);
    expect(grilleBloquee(rapport([anomaly({})]))).toBe(false);
    expect(grilleBloquee(rapport([anomaly({ severite: 'ERREUR', type: 'DOUBLON' })]))).toBe(true);
  });

  // Le verdict porte sur toute la grille obtenue : une édition qui traîne déjà
  // une erreur rendrait sinon toute règle inécrivable, en accusant la règle.
  it('ne bloque pas sur une erreur que la grille portait déjà', () => {
    const deja = anomaly({ severite: 'ERREUR', type: 'REPOS_QUOTIDIEN_IMPOSSIBLE', message: 'trop long' });
    const nouvelle = anomaly({ severite: 'ERREUR', type: 'DOUBLON', message: 'doublon' });

    expect(grilleBloquee(rapport([deja]), rapport([deja]))).toBe(false);
    expect(grilleBloquee(rapport([deja, nouvelle]), rapport([deja]))).toBe(true);
    expect(erreursIntroduites(rapport([deja, nouvelle]), rapport([deja]))).toEqual([nouvelle]);
  });

  it('trie les erreurs avant les avertissements, puis par date', () => {
    const triees = trierAnomalies([
      anomaly({ date: '2026-07-09', message: 'b' }),
      anomaly({ severite: 'ERREUR', date: '2026-07-10', message: 'c' }),
      anomaly({ date: null, message: 'a' })
    ]);
    expect(triees.map((each) => each.message)).toEqual(['c', 'a', 'b']);
    expect(gridAnomalyIcon(triees[0])).toBe('error');
    expect(gridAnomalyIcon(triees[1])).toBe('warning');
  });
});
