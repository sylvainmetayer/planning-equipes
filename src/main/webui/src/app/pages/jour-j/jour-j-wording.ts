// What the event-day screen says, kept out of the component so the sentences
// are pinned down without a DOM — the convention `publication.ts` and
// `affectation-explanation-rules.ts` already follow.
//
// Everything here is a pure function of what the server answered. The screen is
// read standing up, on a phone, while somebody is waiting: each sentence has to
// be true and short, and none of them may imply more than the server said.

import type {
  AnimateurAffecte,
  CreneauJourJ,
  EtatJourJ,
  MuralAlert,
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
 * « J5 · 60 stands ouverts · 2 vacations restantes »: the day at a glance, as
 * the header of Aujourd'hui states it. The rank is left out before the event
 * (a day before the first one has none).
 */
export function dayHeader(etat: EtatJourJ | null): string {
  if (!etat) {
    return '';
  }
  const stands =
    etat.standsOuverts === 1
      ? $localize`:@@aujourdhui.entete.stand:1 stand ouvert`
      : $localize`:@@aujourdhui.entete.stands:${etat.standsOuverts}:count: stands ouverts`;
  const restants = etat.creneauxRestants.length;
  const vacations =
    restants === 1
      ? $localize`:@@aujourdhui.entete.vacation:1 vacation restante`
      : $localize`:@@aujourdhui.entete.vacations:${restants}:count: vacations restantes`;
  const parties =
    etat.jourNumero > 0 ? [`J${etat.jourNumero}`, stands, vacations] : [stands, vacations];
  return parties.join(' · ');
}

/** The counters of the header of Aujourd'hui, each one a link to where it is dealt with. */
export interface DayCounters {
  missing: string;
  nouvelles: string;
  connues: string;
  echanges: string;
  aPrevenir: string;
}

/** « 3 absents », « 1 place vide nouvelle »…: singular and plural said whole, never « (s) ». */
export function dayCounters(etat: EtatJourJ, nouvelles: number, connues: number): DayCounters {
  const missing = etat.absences.length;
  const echanges = etat.echangesAArbitrer;
  const aPrevenir = etat.aPrevenir.length;
  return {
    missing:
      missing === 0
        ? $localize`:@@aujourdhui.compteur.absents.aucun:Aucun absent`
        : missing === 1
          ? $localize`:@@aujourdhui.compteur.absents.un:1 absent`
          : $localize`:@@aujourdhui.compteur.absents:${missing}:count: absents`,
    nouvelles:
      nouvelles === 0
        ? $localize`:@@aujourdhui.compteur.nouvelles.aucune:Aucune place vide nouvelle`
        : nouvelles === 1
          ? $localize`:@@aujourdhui.compteur.nouvelles.une:1 place vide nouvelle`
          : $localize`:@@aujourdhui.compteur.nouvelles:${nouvelles}:count: places vides nouvelles`,
    connues:
      connues === 0
        ? $localize`:@@aujourdhui.compteur.connues.aucune:Aucune place vide connue`
        : connues === 1
          ? $localize`:@@aujourdhui.compteur.connues.une:1 place vide connue`
          : $localize`:@@aujourdhui.compteur.connues:${connues}:count: places vides connues`,
    echanges:
      echanges === 0
        ? $localize`:@@aujourdhui.compteur.echanges.aucun:Aucun échange à arbitrer`
        : echanges === 1
          ? $localize`:@@aujourdhui.compteur.echanges.un:1 échange à arbitrer`
          : $localize`:@@aujourdhui.compteur.echanges:${echanges}:count: échanges à arbitrer`,
    aPrevenir:
      aPrevenir === 0
        ? $localize`:@@aujourdhui.compteur.aPrevenir.aucun:Rien de non publié`
        : aPrevenir === 1
          ? $localize`:@@aujourdhui.compteur.aPrevenir.un:1 personne à prévenir`
          : $localize`:@@aujourdhui.compteur.aPrevenir:${aPrevenir}:count: personnes à prévenir`,
  };
}

/**
 * What is left unpublished, said where the replacement was made: « Prévenir
 * les N personnes » publishes to them and nobody else. Empty when nothing
 * differs from what was sent.
 */
export function prevenirLibelle(nombre: number): string {
  if (nombre === 0) {
    return '';
  }
  return nombre === 1
    ? $localize`:@@aujourdhui.prevenir.une:Prévenir la personne concernée`
    : $localize`:@@aujourdhui.prevenir.plusieurs:Prévenir les ${nombre}:count: personnes`;
}

/** The sentence above that button: what still differs from the plan sent. */
export function nonPublieLibelle(nombre: number): string {
  if (nombre === 0) {
    return $localize`:@@aujourdhui.nonPublie.aucun:Tout le monde a reçu la dernière version de son planning.`;
  }
  return nombre === 1
    ? $localize`:@@aujourdhui.nonPublie.une:1 personne a un planning différent de celui qu'elle a reçu.`
    : $localize`:@@aujourdhui.nonPublie.plusieurs:${nombre}:count: personnes ont un planning différent de celui qu'elles ont reçu.`;
}

/** One person found by the search: named, reachable, and what marking them absent frees. */
export interface PersonneTrouvee {
  animateurId: string;
  nomAffiche: string;
  telephone: string | null;
  /** Seats held over the remaining timeslots, 0 for somebody not on duty. */
  postesRestants: number;
  absent: boolean;
}

/** Lower case, accents dropped: « Hélène » is found by « helene ». */
function plie(haystack: string): string {
  return haystack
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase();
}

/**
 * The search of Aujourd'hui: every word typed must appear in the name or the
 * id. Nothing below two characters — a single letter matches half the roster
 * and the list would be the 86 buttons this search replaced. At most
 * {@link RESULTATS_MAX} people, on duty first.
 */
export function searchPeople(etat: EtatJourJ | null, requete: string): PersonneTrouvee[] {
  const mots = plie(requete.trim()).split(/\s+/).filter(Boolean);
  if (!etat || mots.join('').length < 2) {
    return [];
  }
  const onDuty = new Map(
    etat.animateursDeService.map((animateur) => [animateur.animateurId, animateur]),
  );
  return etat.animateurs
    .filter((animateur) => {
      const haystack = plie(`${animateur.nomAffiche} ${animateur.animateurId}`);
      return mots.every((mot) => haystack.includes(mot));
    })
    .map((animateur) => ({
      animateurId: animateur.animateurId,
      nomAffiche: animateur.nomAffiche,
      telephone: animateur.telephone,
      postesRestants: onDuty.get(animateur.animateurId)?.postesRestants ?? 0,
      absent: onDuty.get(animateur.animateurId)?.absent ?? false,
    }))
    .sort(
      (gauche, droite) =>
        Number(droite.postesRestants > 0) - Number(gauche.postesRestants > 0) ||
        gauche.nomAffiche.localeCompare(droite.nomAffiche),
    )
    .slice(0, RESULTATS_MAX);
}

/** How many people the search lists at most: a phone screen, not a roster. */
export const RESULTATS_MAX = 8;

/** The wall display's alert, worded for Aujourd'hui — the same calculation, read on a phone. */
export function alerteLibelle(alerte: MuralAlert): string {
  const bornes = plage(heureDe(alerte.start), heureDe(alerte.end));
  switch (alerte.type) {
    case 'NEW_EMPTY_SEATS':
      return $localize`:@@aujourdhui.alerte.nouveaux:${alerte.standNom}:stand: · ${bornes}:bornes: : ${alerte.count}:count: place(s) vide(s) depuis ce matin`;
    case 'STARTING_SOON':
      return $localize`:@@aujourdhui.alerte.bientot:${alerte.standNom}:stand: · commence à ${heureDe(alerte.start)}:heure: avec ${alerte.count}:count: place(s) vide(s)`;
    case 'BREAK_WITHOUT_RELAY':
      return $localize`:@@aujourdhui.alerte.pause:${alerte.standNom}:stand: · pause de ${alerte.nom ?? ''}:nom: sans relais, ${bornes}:bornes:`;
  }
}

/** « 2 créneaux restants » under a name, so the tap target says what it costs. */
export function chargeRestante(animateur: Pick<AnimateurAffecte, 'postesRestants'>): string {
  if (animateur.postesRestants === 0) {
    return $localize`:@@aujourdhui.charge.aucune:Pas de service sur les créneaux restants`;
  }
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
