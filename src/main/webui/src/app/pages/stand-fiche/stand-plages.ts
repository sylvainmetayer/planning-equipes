import { ModeHoraire, PlageStand } from '../../core/models';
import { compareCodeUnits } from '../../core/string-order';

/** The most plages drawn while no day is chosen; a day's filter shows all of its own. */
export const MAX_PLAGES_AFFICHEES = 200;

/** The plages of the stand, by day then by start. */
export function sortedPlages(plages: readonly PlageStand[]): PlageStand[] {
  return [...plages].sort(
    (left, right) =>
      compareCodeUnits(left.date, right.date) ||
      compareCodeUnits(left.heureDebut, right.heureDebut) ||
      compareCodeUnits(left.mode, right.mode),
  );
}

/** The days that carry at least one plage, in order: what the day filter offers. */
export function plageDays(plages: readonly PlageStand[]): string[] {
  return [...new Set(plages.map((plage) => plage.date))].sort(compareCodeUnits);
}

/** The plages of `jour`, all of them when `jour` is empty; none when it names a day without plage. */
export function plagesOfDay(plages: readonly PlageStand[], jour: string): PlageStand[] {
  return jour ? plages.filter((plage) => plage.date === jour) : [...plages];
}

/** What the add form holds, as typed. */
export interface PlageForm {
  mode: ModeHoraire;
  date: string;
  heureDebut: string;
  heureFin: string;
  effectif: string;
  motif: string;
}

export function emptyPlageForm(date = ''): PlageForm {
  return { mode: 'OUVERTURE', date, heureDebut: '', heureFin: '', effectif: '', motif: '' };
}

/** Why the form cannot be sent, or `null`. Only what the server would refuse by lack of a value. */
export function plageFormError(form: PlageForm, effectifMax: number): string | null {
  if (!form.date || !form.heureDebut) {
    return $localize`:@@standFiche.plages.erreur.manque:Un jour et une heure de début sont nécessaires.`;
  }
  if (form.heureFin && form.heureFin <= form.heureDebut) {
    return $localize`:@@standFiche.plages.erreur.fin:La fin doit suivre le début (vide = jusqu'à la fermeture).`;
  }
  if (form.mode === 'OUVERTURE' && form.effectif !== '') {
    const effectif = Number(form.effectif);
    if (!Number.isInteger(effectif) || effectif < 1) {
      return $localize`:@@standFiche.plages.erreur.effectif:L'effectif d'une ouverture est un entier d'au moins 1.`;
    }
    if (effectifMax > 0 && effectif > effectifMax) {
      return $localize`:@@standFiche.plages.erreur.effectifMax:L'effectif ne peut pas dépasser celui du stand (${effectifMax}:max:).`;
    }
  }
  return null;
}

/** The body of `POST /api/stands/{id}/plages`: an empty end is « until closing », a closure has no headcount. */
export function plageOfForm(form: PlageForm): PlageStand {
  return {
    id: null,
    mode: form.mode,
    date: form.date,
    heureDebut: form.heureDebut,
    heureFin: form.heureFin || null,
    motif: form.motif.trim() || null,
    effectif: form.mode === 'OUVERTURE' && form.effectif !== '' ? Number(form.effectif) : null,
  };
}
