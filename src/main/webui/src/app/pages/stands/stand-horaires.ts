// What the two stand dialogs say about opening hours, kept out of them so it
// is unit tested without rendering — the convention `stand-draft.ts` already
// follows for the rest of the stand form (AGENTS.md).
//
// The validation loop and the weekday labels were written twice, character for
// character, in `stand-form-dialog.ts` and `stand-bulk-edit-dialog.ts`: the
// single-stand form and the bulk editor apply the very same rules to the very
// same recurring horaires. They now read them here.
//
// Every `$localize` below sits inside a function body on purpose: called at
// module scope it would run before `main.ts` has loaded the translations.

import { Creneau, JourSemaine } from '../../core/models';
import {
  ErreurSaisieFenetres,
  JourResolu,
  conflitDeMode,
  decrireFenetre,
  erreurHoraire,
  parseFenetres,
} from '../../core/horaire-stand';
import { HoraireDraft } from './stand-draft';

/**
 * Why one rule cannot be saved as entered, or `null` — the compact line first
 * when it is what the user is typing (a line that does not parse leaves the
 * previous windows in place, and those may well be valid), then the windows
 * and the day selector, as the backend checks them.
 */
export function erreurRegle(horaire: HoraireDraft, effectifMax?: number): string | null {
  if (typeof horaire.saisie === 'string') {
    const saisie = parseFenetres(horaire.saisie);
    if (saisie.erreur !== null) {
      return messageSaisie(saisie.erreur, saisie.morceau);
    }
  }
  return erreurHoraire(
    horaire,
    {
      fenetreRequise: $localize`:@@stands.horaires.error.fenetreRequise:Chaque horaire doit porter au moins une fenêtre.`,
      heureDebutRequise: $localize`:@@stands.horaires.error.heureDebutRequise:Chaque fenêtre doit avoir une heure de début.`,
      fenetreInversee: $localize`:@@stands.horaires.error.fenetreInversee:L'heure de fin doit être après l'heure de début (laissez-la vide pour aller jusqu'à la fermeture).`,
      effectifInvalide: $localize`:@@stands.horaires.error.effectifInvalide:L'effectif d'une fenêtre, s'il est renseigné, doit être un entier d'au moins 1 (vide = l'effectif minimum du stand).`,
      effectifDepasse: (effectif, effectifMax) =>
        $localize`:@@stands.horaires.error.effectifDepasse:L'effectif d'une fenêtre (${effectif}:effectif:) dépasse l'effectif maximum du stand (${effectifMax}:effectifMax:) : relevez le maximum, ou baissez celui de la fenêtre.`,
      joursSemaineRequis: $localize`:@@stands.horaires.error.joursSemaineRequis:Choisissez au moins un jour de la semaine.`,
      plageRequise: $localize`:@@stands.horaires.error.plageRequise:Renseignez une date de début et une date de fin cohérentes.`,
      datesRequises: $localize`:@@stands.horaires.error.datesRequises:Choisissez au moins une date.`,
    },
    effectifMax,
  );
}

function messageSaisie(erreur: ErreurSaisieFenetres, morceau: string): string {
  switch (erreur) {
    case 'VIDE':
      return $localize`:@@stands.horaires.error.saisieVide:Indiquez au moins une fenêtre, par exemple « 10:00-12:00, 14:00- ».`;
    case 'FORME':
      return $localize`:@@stands.horaires.error.saisieForme:« ${morceau}:morceau: » n'est pas une fenêtre : attendu « début-fin », ou « début- » jusqu'à la fermeture.`;
    case 'HEURE':
      return $localize`:@@stands.horaires.error.saisieHeure:« ${morceau}:morceau: » contient une heure illisible : écrivez 10:00, 10h ou 10h30.`;
    case 'EFFECTIF':
      return $localize`:@@stands.horaires.error.saisieEffectif:« ${morceau}:morceau: » : après « @ », un entier d'au moins 1 (l'effectif de cette fenêtre).`;
  }
}

/**
 * First problem among the recurring rules, or `null` — mirrors the backend's
 * own check, and blocks the submit button of both dialogs.
 */
export function premiereErreurHoraire(
  horaires: readonly HoraireDraft[],
  effectifMax?: number,
): string | null {
  for (const horaire of horaires) {
    const erreur = erreurRegle(horaire, effectifMax);
    if (erreur) {
      return erreur;
    }
  }
  return messageConflitDeMode(horaires);
}

/** The one check that spans several rules, or `null`: same scope, same days, opposite modes. */
export function messageConflitDeMode(horaires: readonly HoraireDraft[]): string | null {
  return conflitDeMode(horaires)
    ? $localize`:@@stands.horaires.error.conflitMode:Deux horaires de même portée sur les mêmes jours ne peuvent pas être l'un une ouverture et l'autre une fermeture : précisez la portée de celui qui doit primer.`
    : null;
}

/**
 * Weekday label of the `JOURS_SEMAINE` checkboxes. Written out rather than
 * derived from `Intl`, because the locale here is the app's own (translated at
 * runtime, see AGENTS.md) and not the browser's.
 */
export function libelleJourSemaine(jour: JourSemaine): string {
  switch (jour) {
    case 'MONDAY':
      return $localize`:@@common.weekday.monday:Lundi`;
    case 'TUESDAY':
      return $localize`:@@common.weekday.tuesday:Mardi`;
    case 'WEDNESDAY':
      return $localize`:@@common.weekday.wednesday:Mercredi`;
    case 'THURSDAY':
      return $localize`:@@common.weekday.thursday:Jeudi`;
    case 'FRIDAY':
      return $localize`:@@common.weekday.friday:Vendredi`;
    case 'SATURDAY':
      return $localize`:@@common.weekday.saturday:Samedi`;
    case 'SUNDAY':
      return $localize`:@@common.weekday.sunday:Dimanche`;
  }
}

/** Days the preview covers: the edition's créneaux — what the solver builds from. */
export function datesEvenement(creneaux: readonly Creneau[]): string[] {
  return [...new Set(creneaux.map((creneau) => creneau.date))].sort();
}

/** Day label of the preview strip: `08/07`, short enough for a dozen cells in a row. */
export function libelleJour(date: string): string {
  const [, mois, jour] = date.split('-');
  return `${jour}/${mois}`;
}

/** One resolved day of the preview strip, in words. */
export function decrireJour(jour: JourResolu): string {
  if (jour.mode === null) {
    return $localize`:@@stands.apercu.ouvertToutLeJour:Ouvert toute la journée`;
  }
  const fenetres = jour.fenetres
    .map((fenetre) => decrireFenetre(fenetre, $localize`:@@stands.apercu.fermeture:fermeture`))
    .join(', ');
  return jour.mode === 'OUVERTURE'
    ? $localize`:@@stands.apercu.ouvertSur:Ouvert ${fenetres}:fenetres:`
    : $localize`:@@stands.apercu.fermeSur:Fermé ${fenetres}:fenetres:`;
}

/**
 * An emptied number field of the bulk editor means "ne pas modifier", not zero:
 * anything that is not a number leaves each stand's own bound alone.
 */
export function effectifDepuisSaisie(valeur: unknown): number | null {
  const count = valeur === '' || valeur === null || valeur === undefined ? null : Number(valeur);
  return count === null || Number.isNaN(count) ? null : count;
}
