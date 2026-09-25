// The grid read as a whole, on the Créneaux page: a recurrence rule typed in a
// dialog, and the verdict the server gives on the grid. Pure functions, unit
// tested without rendering — same split as `jours-resume.ts` next door.

import { parseFenetres } from '../../core/horaire-stand';
import {
  AnomalieGrille,
  FenetreHoraire,
  JourSemaine,
  RapportGrille,
  RegleRecurrence,
  TypeJoursHoraire,
} from '../../core/models';

/** The dialog's own state: strings where the inputs are, the windows as one line. */
export interface SerieDraft {
  jours: TypeJoursHoraire;
  dateDebut: string;
  dateFin: string;
  joursSemaine: JourSemaine[];
  dates: string;
  exclusions: string;
  fenetres: string;
}

export function serieVide(): SerieDraft {
  return {
    jours: 'TOUS',
    dateDebut: '',
    dateFin: '',
    joursSemaine: [],
    dates: '',
    exclusions: '',
    fenetres: '',
  };
}

/** Comma-separated ISO dates; anything that is not one is dropped. */
export function datesFromText(text: string): string[] {
  return text
    .split(/[,;\s]+/)
    .map((date) => date.trim())
    .filter((date) => /^\d{4}-\d{2}-\d{2}$/.test(date));
}

/**
 * The rule as the server reads it, or what stops it from being sent. A
 * créneau is the day's amplitude itself, so every window needs its end, and a
 * headcount means nothing on it — both refused here, in words, rather than by
 * a 400 the dialog would have to translate.
 */
export type ErreurSerie =
  | 'FENETRES_VIDES'
  | 'FENETRE_ILLISIBLE'
  | 'FIN_REQUISE'
  | 'EFFECTIF_REFUSE'
  | 'PLAGE_REQUISE'
  | 'PLAGE_INVERSEE'
  | 'JOURS_SEMAINE_REQUIS'
  | 'DATES_REQUISES'
  | 'DATES_ILLISIBLES';

export type RegleOuErreur =
  | { readonly regle: RegleRecurrence; readonly erreur: null; readonly morceau: null }
  | { readonly regle: null; readonly erreur: ErreurSerie; readonly morceau: string | null };

/** Dates the user typed that are not dates at all — dropped, and worth saying. */
export function datesIllisibles(text: string): string[] {
  return text
    .split(/[,;\s]+/)
    .map((date) => date.trim())
    .filter((date) => date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date));
}

type ErreurRegle = Extract<RegleOuErreur, { regle: null }>;

/** The windows of a series: closed, and without a headcount — a créneau has neither open end nor effectif. */
function fenetresSerie(texte: string): FenetreHoraire[] | ErreurRegle {
  const saisie = parseFenetres(texte);
  if (saisie.erreur !== null) {
    return {
      regle: null,
      erreur: saisie.erreur === 'VIDE' ? 'FENETRES_VIDES' : 'FENETRE_ILLISIBLE',
      morceau: saisie.morceau,
    };
  }
  const fenetres: FenetreHoraire[] = [];
  for (const fenetre of saisie.fenetres) {
    if (!fenetre.heureFin) {
      return { regle: null, erreur: 'FIN_REQUISE', morceau: fenetre.heureDebut };
    }
    if (fenetre.effectif !== null && fenetre.effectif !== undefined) {
      return {
        regle: null,
        erreur: 'EFFECTIF_REFUSE',
        morceau: `${fenetre.heureDebut}-${fenetre.heureFin}@${fenetre.effectif}`,
      };
    }
    fenetres.push({ heureDebut: fenetre.heureDebut, heureFin: fenetre.heureFin });
  }
  return fenetres;
}

/** What is missing from the days a series runs on, or `null` when they are complete. */
function erreurJoursSerie(draft: SerieDraft, dates: string[]): ErreurRegle | null {
  if (draft.jours === 'DATES') {
    return dates.length === 0 ? { regle: null, erreur: 'DATES_REQUISES', morceau: null } : null;
  }
  if (!draft.dateDebut || !draft.dateFin) {
    return { regle: null, erreur: 'PLAGE_REQUISE', morceau: null };
  }
  if (draft.dateFin < draft.dateDebut) {
    return { regle: null, erreur: 'PLAGE_INVERSEE', morceau: null };
  }
  if (draft.jours === 'JOURS_SEMAINE' && draft.joursSemaine.length === 0) {
    return { regle: null, erreur: 'JOURS_SEMAINE_REQUIS', morceau: null };
  }
  return null;
}

export function regleDepuis(draft: SerieDraft): RegleOuErreur {
  const fenetres = fenetresSerie(draft.fenetres);
  if (!Array.isArray(fenetres)) {
    return fenetres;
  }
  const illisibles = datesIllisibles(draft.exclusions).concat(
    draft.jours === 'DATES' ? datesIllisibles(draft.dates) : [],
  );
  if (illisibles.length > 0) {
    // Silently dropped before: a « 14/07/2026 » among the exclusions wrote the
    // créneaux of 14 July anyway, and nothing said the input was ignored.
    return { regle: null, erreur: 'DATES_ILLISIBLES', morceau: illisibles.join(', ') };
  }
  const dates = draft.jours === 'DATES' ? datesFromText(draft.dates) : [];
  const erreurJours = erreurJoursSerie(draft, dates);
  if (erreurJours) {
    return erreurJours;
  }
  return {
    regle: {
      jours: draft.jours,
      dateDebut: draft.jours === 'DATES' ? null : draft.dateDebut,
      dateFin: draft.jours === 'DATES' ? null : draft.dateFin,
      joursSemaine: draft.jours === 'JOURS_SEMAINE' ? draft.joursSemaine : [],
      dates,
      exclusions: datesFromText(draft.exclusions),
      fenetres,
    },
    erreur: null,
    morceau: null,
  };
}

/** What a preview was taken on, so a rule edited since is previewed again before being created. */
export function signatureSerie(draft: SerieDraft): string {
  return JSON.stringify(draft);
}

export interface BilanGrille {
  erreurs: number;
  avertissements: number;
  ouvertures: number;
  faisable: boolean | null;
}

export function bilanGrille(rapport: RapportGrille): BilanGrille {
  return {
    erreurs: rapport.anomalies.filter((anomaly) => anomaly.severite === 'ERREUR').length,
    avertissements: rapport.anomalies.filter((anomaly) => anomaly.severite === 'AVERTISSEMENT')
      .length,
    ouvertures: rapport.ouvertures.length,
    faisable: rapport.faisabilite ? rapport.faisabilite.feasible : null,
  };
}

/**
 * The errors the previewed grid has and the current one does not — what the
 * rule would introduce.
 *
 * Compared against the grid as it stands, because the verdict covers the whole
 * resulting grid: an edition already carrying one long slot would otherwise
 * make every rule unwritable, blaming a rule that is fine for an error about
 * another date.
 */
export function erreursIntroduites(
  after: RapportGrille,
  before: RapportGrille | null,
): AnomalieGrille[] {
  const connues = new Set(
    (before?.anomalies ?? [])
      .filter((anomaly) => anomaly.severite === 'ERREUR')
      .map((anomaly) => `${anomaly.type}#${anomaly.date}#${anomaly.message}`),
  );
  return after.anomalies.filter(
    (anomaly) =>
      anomaly.severite === 'ERREUR' &&
      !connues.has(`${anomaly.type}#${anomaly.date}#${anomaly.message}`),
  );
}

/** Whether the verdict allows a rule to be written: warnings do, an error (a doublon, say) does not. */
export function grilleBloquee(
  rapport: RapportGrille | null,
  before: RapportGrille | null = null,
): boolean {
  return rapport !== null && erreursIntroduites(rapport, before).length > 0;
}

/** Errors first, then warnings; within a severity, by date then message — the order a reader wants. */
export function trierAnomalies(anomalies: readonly AnomalieGrille[]): AnomalieGrille[] {
  return [...anomalies].sort((a, b) => {
    if (a.severite !== b.severite) {
      return a.severite === 'ERREUR' ? -1 : 1;
    }
    return (a.date ?? '').localeCompare(b.date ?? '') || a.message.localeCompare(b.message);
  });
}

/** Icon of a grid anomaly, so the list reads without colour alone. */
export function gridAnomalyIcon(anomaly: AnomalieGrille): string {
  return anomaly.severite === 'ERREUR' ? 'error' : 'warning';
}
