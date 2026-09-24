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

import { Creneau, HoraireStand, Stand } from '../../core/models';
import {
  ErreurSaisieFenetres,
  FenetresChevauchantes,
  JourEdition,
  JourResolu,
  Priorite,
  RegleMasquee,
  ReglesChevauchantes,
  anomaliesHoraires,
  conflitDeMode,
  decrireFenetre,
  erreurHoraire,
  libelleJour,
  parseFenetres,
  priorites,
} from '../../core/horaire-stand';
import { getStoredLocale, intlLocale } from '../../core/locale';
import { HoraireDraft } from './stand-draft';
import { describeDays } from './stand-detail';

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

/** Days the preview covers: the edition's créneaux — what the solver builds from. */
export function datesEvenement(creneaux: readonly Creneau[]): string[] {
  return [...new Set(creneaux.map((creneau) => creneau.date))].sort();
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

/* ------------------ warnings that never block a save ------------------ */

/** What one rule card says below its fields, on top of its errors: warnings, then the priority it takes. */
export interface NotesRegle {
  avertissements: string[];
  priorites: string[];
}

/** A rule as a reader recognises it: its days, then its windows. */
function libelleRegle(horaire: HoraireStand): string {
  const fenetres = horaire.fenetres
    .filter((fenetre) => !!fenetre.heureDebut)
    .map((fenetre) => decrireFenetre(fenetre, $localize`:@@stands.apercu.fermeture:fermeture`))
    .join(', ');
  return fenetres ? `${describeDays(horaire)} ${fenetres}` : describeDays(horaire);
}

/**
 * Per rule, what the analysis of the Ouvertures screen will say about it once
 * saved — overlapping rules (on the later one of the pair), a rule no day
 * reads, overlapping windows — and, below the rule that wins, the rules of the
 * opposite mode it takes over. Computed by the mirror of `core/horaire-stand.ts`
 * on the rules as typed: nothing here blocks the submit.
 *
 * @param stand the dated exceptions, which win over every rule on their day
 * @param jours the edition's days; empty while it has no créneau
 * @param effectifMin what a window naming no headcount asks for, when known
 */
export function notesRegles(
  horaires: readonly HoraireStand[],
  stand: Pick<Stand, 'ouvertures' | 'indisponibilites'>,
  jours: readonly JourEdition[],
  effectifMin: number | null,
): NotesRegle[] {
  const notes: NotesRegle[] = horaires.map(() => ({ avertissements: [], priorites: [] }));
  const anomalies = anomaliesHoraires(
    {
      horaires: [...horaires],
      ouvertures: stand.ouvertures,
      indisponibilites: stand.indisponibilites,
    },
    jours,
    effectifMin,
  );
  for (const recouvrement of anomalies.reglesChevauchantes) {
    notes[recouvrement.autreRegle].avertissements.push(
      messageReglesChevauchantes(horaires, recouvrement),
    );
  }
  for (const masquee of anomalies.reglesMasquees) {
    notes[masquee.regle].avertissements.push(messageRegleMasquee(horaires, masquee));
  }
  for (const recouvrement of anomalies.fenetresChevauchantes) {
    if (recouvrement.regle !== null) {
      notes[recouvrement.regle].avertissements.push(
        messageFenetresChevauchantes(horaires[recouvrement.regle], recouvrement),
      );
    }
  }
  for (const priorite of priorites(horaires, jours)) {
    notes[priorite.regle].priorites.push(messagePriorite(horaires, priorite));
  }
  return notes;
}

/**
 * {@link notesRegles} for rules several stands will carry at once — the bulk
 * edit — each stand with its own dated exceptions: a warning is shown when
 * any of them would report it, said once however many do. Stands carrying
 * the same exceptions (most often none) are judged once.
 *
 * @param stands the dated exceptions of every stand the rules go to; empty
 *               reads as a single stand without any
 */
export function notesReglesForStands(
  horaires: readonly HoraireStand[],
  stands: readonly Pick<Stand, 'ouvertures' | 'indisponibilites'>[],
  jours: readonly JourEdition[],
  effectifMin: number | null,
): NotesRegle[] {
  const distinct = new Map<string, Pick<Stand, 'ouvertures' | 'indisponibilites'>>();
  for (const stand of stands.length > 0 ? stands : [{ ouvertures: [], indisponibilites: [] }]) {
    const exceptions = {
      ouvertures: stand.ouvertures ?? [],
      indisponibilites: stand.indisponibilites ?? [],
    };
    distinct.set(JSON.stringify(exceptions), exceptions);
  }
  const merged: NotesRegle[] = horaires.map(() => ({ avertissements: [], priorites: [] }));
  for (const exceptions of distinct.values()) {
    notesRegles(horaires, exceptions, jours, effectifMin).forEach((note, index) => {
      for (const avertissement of note.avertissements) {
        if (!merged[index].avertissements.includes(avertissement)) {
          merged[index].avertissements.push(avertissement);
        }
      }
      for (const priorite of note.priorites) {
        if (!merged[index].priorites.includes(priorite)) {
          merged[index].priorites.push(priorite);
        }
      }
    });
  }
  return merged;
}

function messageReglesChevauchantes(
  horaires: readonly HoraireStand[],
  recouvrement: ReglesChevauchantes,
): string {
  const autre = libelleRegle(horaires[recouvrement.regle]);
  const { debut, fin, effectif, autreEffectif } = recouvrement;
  if (horaires[recouvrement.regle].mode === 'FERMETURE') {
    return $localize`:@@stands.horaires.avertissement.fermeturesChevauchantes:Recouvre la règle « ${autre}:regle: » de ${debut}:debut: à ${fin}:fin: : la même fermeture est dite deux fois, une seule règle suffit.`;
  }
  if (effectif === null || autreEffectif === null) {
    return $localize`:@@stands.horaires.avertissement.reglesChevauchantesSansEffectif:Recouvre la règle « ${autre}:regle: » de ${debut}:debut: à ${fin}:fin: : c'est le plus haut des deux effectifs qui est retenu.`;
  }
  if (effectif === autreEffectif) {
    return $localize`:@@stands.horaires.avertissement.reglesEnDouble:Recouvre la règle « ${autre}:regle: » de ${debut}:debut: à ${fin}:fin: : la même fenêtre est dite deux fois, avec le même effectif (${effectif}:effectif:).`;
  }
  const kept = Math.max(effectif, autreEffectif);
  const dropped = Math.min(effectif, autreEffectif);
  return $localize`:@@stands.horaires.avertissement.reglesChevauchantes:Recouvre la règle « ${autre}:regle: » de ${debut}:debut: à ${fin}:fin: : l'effectif retenu est ${kept}:retenu: (le plus haut), pas ${dropped}:ecarte:.`;
}

function messageRegleMasquee(horaires: readonly HoraireStand[], masquee: RegleMasquee): string {
  const regles = masquee.masquantes.map((index) => `« ${libelleRegle(horaires[index])} »`);
  if (masquee.exceptions && regles.length === 0) {
    return $localize`:@@stands.horaires.avertissement.masqueeParExceptions:Cette règle n'est appliquée à aucun jour de l'édition : des exceptions datées la remplacent partout.`;
  }
  const qui = regles.join(', ');
  return masquee.exceptions
    ? $localize`:@@stands.horaires.avertissement.masqueeParReglesEtExceptions:Cette règle n'est appliquée à aucun jour de l'édition : ${qui}:regles: et des exceptions datées la remplacent partout.`
    : $localize`:@@stands.horaires.avertissement.masquee:Cette règle n'est appliquée à aucun jour de l'édition : ${qui}:regles: la remplace partout.`;
}

function messageFenetresChevauchantes(
  horaire: HoraireStand,
  recouvrement: FenetresChevauchantes,
): string {
  const libelle = (index: number, effectif: number | null) =>
    decrireFenetre(
      { ...horaire.fenetres[index], effectif },
      $localize`:@@stands.apercu.fermeture:fermeture`,
    );
  const first = libelle(recouvrement.fenetre, recouvrement.effectif);
  const second = libelle(recouvrement.autreFenetre, recouvrement.autreEffectif);
  const { debut, fin, effectif, autreEffectif } = recouvrement;
  if (effectif === null || autreEffectif === null) {
    // An unknown headcount (the bulk editor has no stand minimum) is not zero.
    return $localize`:@@stands.horaires.avertissement.fenetresChevauchantesSansEffectif:${first}:premiere: et ${second}:seconde: se recouvrent de ${debut}:debut: à ${fin}:fin: : c'est le plus haut des deux effectifs qui est retenu.`;
  }
  const kept = Math.max(effectif, autreEffectif);
  return $localize`:@@stands.horaires.avertissement.fenetresChevauchantes:${first}:premiere: et ${second}:seconde: se recouvrent : ${kept}:retenu: personne(s) de ${debut}:debut: à ${fin}:fin:.`;
}

/** How many dates a priority spells out before it stops. */
const CITED_DATES = 5;

/**
 * The days of a rule inside a sentence: lower-cased in French (« samedi,
 * dimanche »), left alone in English, where weekday names keep their capital.
 */
function daysInSentence(horaire: HoraireStand): string {
  const days = describeDays(horaire);
  return getStoredLocale() === 'fr' ? days.toLocaleLowerCase(intlLocale()) : days;
}

function messagePriorite(horaires: readonly HoraireStand[], priorite: Priorite): string {
  const autre = daysInSentence(horaires[priorite.surRegle]);
  const horaire = horaires[priorite.regle];
  if (priorite.dates.length === 0) {
    return $localize`:@@stands.horaires.priorite.sansJour:Prime sur « ${autre}:regle: » les jours qu'elle couvre.`;
  }
  // A weekday rule is read by its weekdays; the others by their dates.
  const quand =
    horaire.jours === 'JOURS_SEMAINE'
      ? daysInSentence(horaire)
      : priorite.dates.slice(0, CITED_DATES).map(libelleJour).join(', ') +
        (priorite.dates.length > CITED_DATES ? '…' : '');
  return $localize`:@@stands.horaires.priorite:Prime sur « ${autre}:regle: » : ${quand}:quand:.`;
}
