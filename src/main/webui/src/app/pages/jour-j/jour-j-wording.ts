// What the event-day screen says, kept out of the component so the sentences
// are pinned down without a DOM — the convention `publication.ts` and
// `affectation-explanation-rules.ts` already follow.
//
// Everything here is a pure function of what the server answered. The screen is
// read standing up, on a phone, while somebody is waiting: each sentence has to
// be true and short, and none of them may imply more than the server said.

import type {
  AnimateurAffecte,
  ApercuPublication,
  CreneauJourJ,
  EtatJourJ,
  PosteAPourvoir,
  SuggestionsReparation,
} from '../../core/models';

/** `09:00:00` → `09:00`. The server sends whole `LocalTime`s; the aisle reads hours. */
export function heure(brut: string | null | undefined): string {
  return brut ? brut.slice(0, 5) : '';
}

/**
 * `2026-07-09T01:00:00` → `01:00`. The reference moment is a full instant, since
 * a journée running past midnight ends on the next calendar date; what the
 * operator needs off it is the time of day.
 */
export function heureDe(instant: string | null | undefined): string {
  return instant ? heure(instant.split('T')[1]) : '';
}

/** `14:00 – 18:00`, the one label every row of this screen is anchored on. */
export function plage(debut: string | null | undefined, fin: string | null | undefined): string {
  return `${heure(debut)} – ${heure(fin)}`;
}

/**
 * How much of the journée is still ahead: « 3 créneaux restants sur 8 ». States
 * the denominator on purpose — "3 remaining" alone reads as "3 in total" at a
 * glance, and the whole screen is about what is left rather than what was.
 *
 * A journée with no timeslot at all is not a finished one, and saying so would
 * be a lie the operator acts on: it gets its own sentence.
 */
export function resumeDuJour(etat: EtatJourJ | null): string {
  if (!etat) {
    return '';
  }
  if (etat.creneauxDuJour === 0) {
    return $localize`:@@jourJ.resume.vide:Aucun créneau n'est programmé ce jour-là : il n'y a pas de journée à couvrir.`;
  }
  const restants = etat.creneauxRestants.length;
  if (restants === 0) {
    return $localize`:@@jourJ.resume.termine:La journée est terminée : plus aucun créneau à couvrir.`;
  }
  return $localize`:@@jourJ.resume.restants:${restants}:restants: créneau(x) restants sur ${etat.creneauxDuJour}:total:, à partir de ${heureDe(etat.maintenant)}:heure: (heure du serveur)`;
}

/**
 * The banner of issue #245's publication count. It is a *reminder*, never an
 * action: putting a mail-to-150-people button one tap away inside an emergency
 * screen is exactly the mistake this wording exists to avoid — the screen says
 * how many are waiting and links to the page that owns the button.
 */
export function rappelPublication(apercu: ApercuPublication | null): string {
  if (!apercu || apercu.nombreConcernes === 0) {
    return '';
  }
  return apercu.nombreConcernes === 1
    ? $localize`:@@jourJ.publication.une:1 personne est concernée par un changement non publié.`
    : $localize`:@@jourJ.publication.plusieurs:${apercu.nombreConcernes}:count: personnes sont concernées par des changements non publiés.`;
}

/** « 2 créneaux restants » under a name, so the tap target says what it costs. */
export function chargeRestante(animateur: AnimateurAffecte): string {
  return animateur.postesRestants === 1
    ? $localize`:@@jourJ.charge.un:1 créneau restant`
    : $localize`:@@jourJ.charge.plusieurs:${animateur.postesRestants}:count: créneaux restants`;
}

/**
 * What the suggestion list is, said in full: the assistant stops at its
 * plafond, and a truncated list presented bare reads as "there is nobody else".
 * Always states the two counts when they differ, and says nothing when they do
 * not — a caveat that shows every time stops being read.
 */
export function porteeDesSuggestions(suggestions: SuggestionsReparation | null): string {
  if (!suggestions) {
    return '';
  }
  if (suggestions.candidatsEvalues >= suggestions.candidatsEligibles) {
    return $localize`:@@jourJ.suggestions.exhaustif:Tous les candidats éligibles (${suggestions.candidatsEligibles}:eligibles:) ont été évalués.`;
  }
  return $localize`:@@jourJ.suggestions.tronque:Les ${suggestions.candidatsEvalues}:evalues: premiers candidats éligibles sur ${suggestions.candidatsEligibles}:eligibles: ont été évalués : la liste est la meilleure de ce qui a été vu, pas tout le vivier.`;
}

/** Nobody viable, said as a result rather than as an empty list. */
export function aucuneSuggestion(suggestions: SuggestionsReparation | null): boolean {
  return suggestions !== null && suggestions.suggestions.length === 0;
}

/**
 * Why a hole cannot be repaired from here. A lock is the operator saying "this
 * one does not move", and the write refuses it server-side: better to say so
 * than to offer a button that answers 400.
 */
export function blocageDuPoste(poste: PosteAPourvoir): string {
  return poste.verrouille
    ? $localize`:@@jourJ.poste.verrouille:Ce poste est verrouillé : levez le verrou avant d'y affecter quelqu'un.`
    : '';
}

/** `HH:MM` of a timeslot, with the one under way marked as such. */
export function libelleCreneau(creneau: CreneauJourJ): string {
  const bornes = plage(creneau.heureDebut, creneau.heureFin);
  return creneau.enCours
    ? $localize`:@@jourJ.creneau.enCours:${bornes}:bornes: (en cours)`
    : bornes;
}

/**
 * Display name of an animateur the suggestions only name by id.
 *
 * Read from the roster, not from the on-duty list: the best replacement is
 * somebody *free* at that hour, so the people this has to name are precisely
 * the ones missing from `animateursDeService` — named from there alone, the
 * main action button read « anim-73 ».
 *
 * Falls back to the raw id rather than to an empty cell: an unnamed candidate is
 * still a candidate, and blanking them would hide a real option.
 */
export function nomDuCandidat(etat: EtatJourJ | null, animateurId: string): string {
  const connu = (etat?.animateurs ?? []).find((candidat) => candidat.animateurId === animateurId);
  return connu ? connu.nomAffiche : animateurId;
}

/**
 * True when this candidate is already holding a seat over the remaining
 * timeslots — worth flagging on the button, because taking the hole means
 * adding to a day they are already working, not filling an idle one.
 */
export function dejaDeService(etat: EtatJourJ | null, animateurId: string): boolean {
  return (etat?.animateursDeService ?? []).some((candidat) => candidat.animateurId === animateurId);
}
