// The grid read as a whole, on the Créneaux page: a recurrence rule typed in a
// dialog, and the verdict the server gives on the grid. Pure functions, unit
// tested without rendering — same split as `decoupage.ts` next door.

import { parseFenetres } from '../../core/horaire-stand';
import {
  AnomalieGrille,
  FenetreHoraire,
  JourSemaine,
  RapportGrille,
  RegleRecurrence,
  TypeJoursHoraire
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
  return { jours: 'TOUS', dateDebut: '', dateFin: '', joursSemaine: [], dates: '', exclusions: '', fenetres: '' };
}

/** Comma-separated ISO dates; anything that is not one is dropped. */
export function datesDepuisTexte(texte: string): string[] {
  return texte
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
  | 'DATES_REQUISES';

export type RegleOuErreur =
  | { readonly regle: RegleRecurrence; readonly erreur: null; readonly morceau: null }
  | { readonly regle: null; readonly erreur: ErreurSerie; readonly morceau: string | null };

export function regleDepuis(draft: SerieDraft): RegleOuErreur {
  const saisie = parseFenetres(draft.fenetres);
  if (saisie.erreur !== null) {
    return { regle: null, erreur: saisie.erreur === 'VIDE' ? 'FENETRES_VIDES' : 'FENETRE_ILLISIBLE', morceau: saisie.morceau };
  }
  const fenetres: FenetreHoraire[] = [];
  for (const fenetre of saisie.fenetres) {
    if (!fenetre.heureFin) {
      return { regle: null, erreur: 'FIN_REQUISE', morceau: fenetre.heureDebut };
    }
    if (fenetre.effectif !== null && fenetre.effectif !== undefined) {
      return { regle: null, erreur: 'EFFECTIF_REFUSE', morceau: `${fenetre.heureDebut}-${fenetre.heureFin}@${fenetre.effectif}` };
    }
    fenetres.push({ heureDebut: fenetre.heureDebut, heureFin: fenetre.heureFin });
  }
  const dates = draft.jours === 'DATES' ? datesDepuisTexte(draft.dates) : [];
  if (draft.jours === 'DATES') {
    if (dates.length === 0) {
      return { regle: null, erreur: 'DATES_REQUISES', morceau: null };
    }
  } else {
    if (!draft.dateDebut || !draft.dateFin) {
      return { regle: null, erreur: 'PLAGE_REQUISE', morceau: null };
    }
    if (draft.dateFin < draft.dateDebut) {
      return { regle: null, erreur: 'PLAGE_INVERSEE', morceau: null };
    }
    if (draft.jours === 'JOURS_SEMAINE' && draft.joursSemaine.length === 0) {
      return { regle: null, erreur: 'JOURS_SEMAINE_REQUIS', morceau: null };
    }
  }
  return {
    regle: {
      jours: draft.jours,
      dateDebut: draft.jours === 'DATES' ? null : draft.dateDebut,
      dateFin: draft.jours === 'DATES' ? null : draft.dateFin,
      joursSemaine: draft.jours === 'JOURS_SEMAINE' ? draft.joursSemaine : [],
      dates,
      exclusions: datesDepuisTexte(draft.exclusions),
      fenetres
    },
    erreur: null,
    morceau: null
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
    erreurs: rapport.anomalies.filter((anomalie) => anomalie.severite === 'ERREUR').length,
    avertissements: rapport.anomalies.filter((anomalie) => anomalie.severite === 'AVERTISSEMENT').length,
    ouvertures: rapport.ouvertures.length,
    faisable: rapport.faisabilite ? rapport.faisabilite.feasible : null
  };
}

/** Whether the verdict allows a rule to be written: warnings do, an error (a doublon, say) does not. */
export function grilleBloquee(rapport: RapportGrille | null): boolean {
  return rapport !== null && rapport.anomalies.some((anomalie) => anomalie.severite === 'ERREUR');
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
export function iconeAnomalieGrille(anomalie: AnomalieGrille): string {
  return anomalie.severite === 'ERREUR' ? 'error' : 'warning';
}
