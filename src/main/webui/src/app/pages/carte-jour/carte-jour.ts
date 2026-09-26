// Builders of the day map replay (issue #306): one event day, a time cursor,
// and which stands are open — and staffed — at the chosen instant.
//
// Pure functions, kept out of the components so "who is open at 15:30" is
// unit-tested without a map, a slider or a DOM — same split as
// `rail-jour.ts` and `ouvertures.ts`.
//
// **What "open" means here is a decision, not a detail.** A stand is open at
// an instant when the persisted plan holds at least one `PosteAffectation`
// whose window covers it. Nothing is recomputed from the stand's opening
// rules: a poste is generated per seat *from* those rules at scenario time, so
// the seats are the resolved schedule, and the issue asks for the persisted
// plan rather than a recalculation. The consequence is worth spelling out — a
// stand whose day was never turned into postes shows as closed, because as far
// as the plan is concerned it is.
//
// Being open then splits in three, and that is where the operational value of
// the screen sits: every seat filled (`pourvu`), some filled and some empty
// (`partiel`), and not one filled (`decouvert` — a door meant to be open with
// nobody behind it).
//
// Windows are half-open: a stand closing at 12:00 is closed at 12:00.

import { Emplacement, PosteAffectation } from '../../core/models';
import { endMinutesOfDay, formatHeure, minutesOfDay } from '../../core/time-of-day';

/** State of one stand at one instant. Order of severity: decouvert > partiel > pourvu > ferme. */
export type EtatStand = 'ferme' | 'decouvert' | 'partiel' | 'pourvu';

/** Same, aggregated over the stands of one emplacement, plus "no stand at all today". */
export type EtatEmplacement = EtatStand | 'sansStand';

/** One seat of one stand, reduced to the window it covers and whether it is filled. */
export interface FenetrePoste {
  /** The timeslot the seat belongs to: what opens the bench on it. */
  creneauId: number;
  debutMinutes: number;
  /** Exclusive: a seat ending at 12:00 does not cover 12:00. */
  finMinutes: number;
  heureDebut: string;
  heureFin: string;
  pourvu: boolean;
}

/** One stand of one event day, with its seats and the place it is set up at. */
export interface StandJour {
  standId: string;
  nom: string;
  /**
   * The place the stand is set up at, as the plan carries it — null when it is
   * tied to none. Kept whole rather than reduced to an id: it is the fallback
   * coordinates source when the emplacement referential could not be loaded.
   */
  emplacement: Emplacement | null;
  postes: FenetrePoste[];
}

/** One event day of the persisted plan, with the bounds its cursor runs between. */
export interface JourneeCarte {
  jour: number;
  date: string | null;
  title: string;
  /** Whole-hour bounds, rounded outwards from the day's first opening and last closing. */
  debutMinutes: number;
  finMinutes: number;
  stands: StandJour[];
}

/** One stand, resolved at the instant the cursor points at. */
export interface StandInstant {
  standId: string;
  nom: string;
  etat: EtatStand;
  /** The timeslot of the first seat covering the instant; null when the stand is closed then. */
  creneauId: number | null;
  /** Seats covering the instant, and how many of them are filled. */
  sieges: number;
  pourvus: number;
  /** `09:00 – 12:00` of the windows covering the instant; empty when closed. */
  horaire: string;
  emplacementNom: string | null;
  /** What a screen reader reads for this stand — the map itself says nothing. */
  resume: string;
}

/** One located emplacement, drawn as a single marker whatever the number of stands on it. */
export interface MarqueurJour {
  emplacementId: string;
  nom: string;
  latitude: number;
  longitude: number;
  etat: EtatEmplacement;
  /** Every stand of that place, worst state first — a marker is never one stand. */
  stands: StandInstant[];
  ouverts: number;
  sieges: number;
  pourvus: number;
  resume: string;
}

/** The at-a-glance line above the map. */
export interface CompteursInstant {
  standsOuverts: number;
  standsTotal: number;
  sieges: number;
  pourvus: number;
  decouverts: number;
  partiels: number;
  /** Stands the map cannot draw: no emplacement, or one without coordinates. */
  nonSitues: number;
  emplacementsSansStand: number;
}

/** Everything one instant of one day puts on screen. */
export interface InstantCarte {
  minutes: number;
  heure: string;
  marqueurs: MarqueurJour[];
  /** Stands with no point to draw, listed next to the map rather than dropped. */
  nonSitues: StandInstant[];
  compteurs: CompteursInstant;
}

/** Severity order, worst first: it drives both the marker's colour and the list's sort. */
const SEVERITE: Record<EtatEmplacement, number> = {
  decouvert: 0,
  partiel: 1,
  pourvu: 2,
  ferme: 3,
  sansStand: 4,
};

interface ContenuJour {
  date: string | null;
  stands: Map<string, StandJour>;
}

/**
 * One entry per event day the persisted plan holds a poste for, chronologically.
 *
 * A poste with no créneau or no stand is skipped: it can be attributed neither
 * to a day nor to a place, and inventing a bucket for it would put a phantom
 * on the map.
 */
export function buildJourneesCarte(postes: PosteAffectation[]): JourneeCarte[] {
  const jours = new Map<number, ContenuJour>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    const stand = poste.stand;
    if (!creneau || !stand) {
      return;
    }
    let jour = jours.get(creneau.jour);
    if (!jour) {
      jour = { date: creneau.date ?? null, stands: new Map() };
      jours.set(creneau.jour, jour);
    }
    let standJour = jour.stands.get(stand.id);
    if (!standJour) {
      standJour = {
        standId: stand.id,
        nom: stand.nom || stand.id,
        emplacement: stand.emplacement,
        postes: [],
      };
      jour.stands.set(stand.id, standJour);
    }
    // The poste's own window when a partial stand closure (issue #60) narrowed
    // it, the créneau's hours otherwise — same rule as the day calendar.
    const heureDebut = formatHeure(poste.heureDebutEffective ?? creneau.heureDebut);
    const heureFin = formatHeure(poste.heureFinEffective ?? creneau.heureFin);
    standJour.postes.push({
      creneauId: creneau.id,
      debutMinutes: minutesOfDay(heureDebut),
      finMinutes: endMinutesOfDay(heureFin),
      heureDebut,
      heureFin,
      pourvu: poste.animateur != null,
    });
  });

  return Array.from(jours.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([jour, contenu]) => buildJourneeCarte(jour, contenu));
}

function buildJourneeCarte(jour: number, contenu: ContenuJour): JourneeCarte {
  const stands = Array.from(contenu.stands.values()).sort((left, right) =>
    left.nom.localeCompare(right.nom),
  );
  const fenetres = stands.flatMap((stand) => stand.postes);
  const premier = Math.min(...fenetres.map((fenetre) => fenetre.debutMinutes));
  const dernier = Math.max(...fenetres.map((fenetre) => fenetre.finMinutes));
  // Rounded outwards to whole hours, so the cursor's scale reads in round
  // hours and every opening is reachable at both ends.
  const debutMinutes = Math.floor(premier / 60) * 60;
  const finMinutes = Math.max(Math.ceil(dernier / 60) * 60, debutMinutes + 60);
  return {
    jour,
    date: contenu.date,
    title: contenu.date
      ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${contenu.date}:date:`
      : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
    debutMinutes,
    finMinutes,
    stands,
  };
}

/** `HH:mm` of a number of minutes since midnight — the cursor's own label. */
export function formatMinutes(minutes: number): string {
  const borne = Math.max(0, Math.min(1440, Math.round(minutes)));
  return `${String(Math.floor(borne / 60)).padStart(2, '0')}:${String(borne % 60).padStart(2, '0')}`;
}

function etatStand(sieges: number, pourvus: number): EtatStand {
  if (sieges === 0) {
    return 'ferme';
  }
  if (pourvus === 0) {
    return 'decouvert';
  }
  return pourvus < sieges ? 'partiel' : 'pourvu';
}

/** One stand resolved at one instant: which of its seats cover it, and how many are filled. */
export function etatStandInstant(stand: StandJour, minutes: number): StandInstant {
  const couvrants = stand.postes.filter(
    (poste) => poste.debutMinutes <= minutes && minutes < poste.finMinutes,
  );
  const sieges = couvrants.length;
  const pourvus = couvrants.filter((poste) => poste.pourvu).length;
  const etat = etatStand(sieges, pourvus);
  const horaire = plagesDistinctes(couvrants);
  return {
    standId: stand.standId,
    nom: stand.nom,
    etat,
    // The timeslot the « Siège » button opens: the first one still
    // short of somebody, since that is the seat the reader is being sent to
    // fill. On a stand whose seats span two timeslots — the very shape that
    // makes it half-covered — the first seat is often the one already held.
    creneauId: (couvrants.find((poste) => !poste.pourvu) ?? couvrants[0])?.creneauId ?? null,
    sieges,
    pourvus,
    horaire,
    // `nom || id`, never `?? null`: an emplacement saved with an empty name
    // would otherwise read as no emplacement at all, and `resumeNonSitue` would
    // tell the operator to attach one instead of filling in the coordinates.
    emplacementNom: stand.emplacement ? stand.emplacement.nom || stand.emplacement.id : null,
    resume: resumeStand(stand.nom, etat, sieges, pourvus, horaire),
  };
}

/**
 * What a marker's badge shows: the number of people on the place at that
 * instant — the filled seats covering it, summed over its stands. Zero is
 * written as zero: a place whose stands are all closed holds nobody, and says
 * so. A place holding no stand at all that day carries no number: its colour
 * and its tooltip already say so, and « 0 » there would read as a closure.
 */
export function comptePastille(marqueur: Pick<MarqueurJour, 'etat' | 'pourvus'>): string {
  return marqueur.etat === 'sansStand' ? '' : String(marqueur.pourvus);
}

/** The seats planned there at that instant, written beside the badge; nothing when there are none. */
export function siegesPastille(marqueur: Pick<MarqueurJour, 'sieges'>): string {
  return marqueur.sieges > 0 ? `/${marqueur.sieges}` : '';
}

/** `09:00 – 12:00`, or several such ranges when two créneaux of the stand overlap on the instant. */
function plagesDistinctes(postes: FenetrePoste[]): string {
  const plages: string[] = [];
  postes.forEach((poste) => {
    const plage = `${poste.heureDebut} – ${poste.heureFin}`;
    if (!plages.includes(plage)) {
      plages.push(plage);
    }
  });
  return plages.join(' ; ');
}

function resumeStand(
  nom: string,
  etat: EtatStand,
  sieges: number,
  pourvus: number,
  horaire: string,
): string {
  switch (etat) {
    case 'ferme':
      return $localize`:@@carteJour.stand.ferme:${nom}:stand: — fermé à cette heure-là`;
    case 'decouvert':
      return $localize`:@@carteJour.stand.decouvert:${nom}:stand: — ouvert ${horaire}:horaire:, ${sieges}:sieges: place(s) et aucune pourvue`;
    case 'partiel':
      return $localize`:@@carteJour.stand.partiel:${nom}:stand: — ouvert ${horaire}:horaire:, ${pourvus}:pourvus: place(s) pourvue(s) sur ${sieges}:sieges:`;
    case 'pourvu':
      return $localize`:@@carteJour.stand.pourvu:${nom}:stand: — ouvert ${horaire}:horaire:, ${sieges}:sieges: place(s), toutes pourvues`;
  }
}

/** The worst state of a group of stands; `ferme` when none of them is open. */
function etatAgrege(stands: StandInstant[]): EtatStand {
  return stands.reduce<EtatStand>(
    (pire, stand) => (SEVERITE[stand.etat] < SEVERITE[pire] ? stand.etat : pire),
    'ferme',
  );
}

/**
 * The whole screen at one instant of one day.
 *
 * `emplacements` is the referential, read for two things the postes alone
 * cannot say: the coordinates of a place (a stand carries its emplacement, but
 * a failed referential load must not blank the map — hence the fallback on
 * what the poste carries), and the places that hold no stand at all that day,
 * which are drawn neutral rather than omitted so the site keeps its shape.
 */
export function instantCarte(
  journee: JourneeCarte | null,
  minutes: number,
  emplacements: readonly Emplacement[] = [],
): InstantCarte {
  const heure = formatMinutes(minutes);
  if (!journee) {
    return {
      minutes,
      heure,
      marqueurs: [],
      nonSitues: [],
      compteurs: {
        standsOuverts: 0,
        standsTotal: 0,
        sieges: 0,
        pourvus: 0,
        decouverts: 0,
        partiels: 0,
        nonSitues: 0,
        emplacementsSansStand: 0,
      },
    };
  }

  const referentiel = new Map(emplacements.map((emplacement) => [emplacement.id, emplacement]));
  const groupes = new Map<string, { point: PointEmplacement; stands: StandInstant[] }>();
  const nonSitues: StandInstant[] = [];
  const all: StandInstant[] = [];

  journee.stands.forEach((stand) => {
    const instant = etatStandInstant(stand, minutes);
    all.push(instant);
    const point = pointDe(stand, referentiel);
    if (!point) {
      // The list says *why* the stand is not on the map: "no emplacement" and
      // "an emplacement nobody geolocated" are two different things to fix.
      nonSitues.push({ ...instant, resume: resumeNonSitue(instant) });
      return;
    }
    const groupe = groupes.get(point.id) ?? { point, stands: [] };
    groupe.stands.push(instant);
    groupes.set(point.id, groupe);
  });

  const marqueurs = Array.from(groupes.values()).map((groupe) =>
    marqueur(groupe.point, groupe.stands),
  );
  // Located places holding no stand of the day — drawn neutral rather than
  // omitted, so the site keeps its shape as the cursor moves.
  let emplacementsSansStand = 0;
  emplacements.forEach((emplacement) => {
    const { id, nom, latitude, longitude } = emplacement;
    if (groupes.has(id) || latitude == null || longitude == null) {
      return;
    }
    emplacementsSansStand += 1;
    marqueurs.push(marqueur({ id, nom: nom || id, latitude, longitude }, []));
  });
  marqueurs.sort(parSeverite);
  nonSitues.sort(parSeverite);

  return {
    minutes,
    heure,
    marqueurs,
    nonSitues,
    compteurs: {
      standsOuverts: all.filter((stand) => stand.etat !== 'ferme').length,
      standsTotal: all.length,
      sieges: all.reduce((total, stand) => total + stand.sieges, 0),
      pourvus: all.reduce((total, stand) => total + stand.pourvus, 0),
      decouverts: all.filter((stand) => stand.etat === 'decouvert').length,
      partiels: all.filter((stand) => stand.etat === 'partiel').length,
      nonSitues: nonSitues.length,
      emplacementsSansStand,
    },
  };
}

/**
 * Why a stand is absent from the map, appended to its own summary: "tied to no
 * emplacement" and "tied to one nobody geolocated" call for two different fixes.
 */
function resumeNonSitue(instant: StandInstant): string {
  const stand = instant.resume;
  const emplacement = instant.emplacementNom;
  return emplacement
    ? $localize`:@@carteJour.nonSitue.sansCoordonnees:${stand}:stand: — rattaché à « ${emplacement}:emplacement: », qui n'a pas de coordonnées`
    : $localize`:@@carteJour.nonSitue.sansEmplacement:${stand}:stand: — rattaché à aucun emplacement`;
}

/** Worst state first, then alphabetically — the order of the list next to the map. */
function parSeverite(
  left: { etat: EtatEmplacement; nom: string },
  right: { etat: EtatEmplacement; nom: string },
): number {
  return SEVERITE[left.etat] - SEVERITE[right.etat] || left.nom.localeCompare(right.nom);
}

/** An emplacement that has the coordinates a marker needs. */
interface PointEmplacement {
  id: string;
  nom: string;
  latitude: number;
  longitude: number;
}

/**
 * The point a stand is drawn at, or null when it has none: no emplacement at
 * all, or one whose coordinates were never filled in. Both are ordinary states
 * of the referential, and both put the stand in the list next to the map.
 */
function pointDe(stand: StandJour, referentiel: Map<string, Emplacement>): PointEmplacement | null {
  const porte = stand.emplacement;
  if (!porte) {
    return null;
  }
  // The referential wins when it is there — it is the editable truth — and the
  // copy the plan carries takes over when it is not, so a failed
  // `/api/emplacements` degrades the names, never the map.
  const emplacement = referentiel.get(porte.id) ?? porte;
  const { latitude, longitude } = emplacement;
  if (latitude == null || longitude == null) {
    return null;
  }
  return { id: emplacement.id, nom: emplacement.nom || emplacement.id, latitude, longitude };
}

function marqueur(point: PointEmplacement, stands: StandInstant[]): MarqueurJour {
  const ordonnes = [...stands].sort(parSeverite);
  const ouverts = ordonnes.filter((stand) => stand.etat !== 'ferme').length;
  const sieges = ordonnes.reduce((total, stand) => total + stand.sieges, 0);
  const pourvus = ordonnes.reduce((total, stand) => total + stand.pourvus, 0);
  const etat: EtatEmplacement = ordonnes.length === 0 ? 'sansStand' : etatAgrege(ordonnes);
  const total = ordonnes.length;
  let resume = $localize`:@@carteJour.marqueur.ouvert:${point.nom}:emplacement: : ${ouverts}:ouverts: stand(s) ouvert(s) sur ${total}:total:, ${pourvus}:pourvus: place(s) pourvue(s) sur ${sieges}:sieges:`;
  if (etat === 'sansStand') {
    resume = $localize`:@@carteJour.marqueur.sansStand:${point.nom}:emplacement: : aucun stand rattaché ce jour-là`;
  } else if (ouverts === 0) {
    resume = $localize`:@@carteJour.marqueur.ferme:${point.nom}:emplacement: : aucun stand ouvert à cette heure-là, sur ${total}:total: rattaché(s)`;
  }
  return {
    emplacementId: point.id,
    nom: point.nom,
    latitude: point.latitude,
    longitude: point.longitude,
    etat,
    stands: ordonnes,
    ouverts,
    sieges,
    pourvus,
    resume,
  };
}

/**
 * The `t` query param — the minute of the day under the cursor — read with no
 * trust: an absent, empty, hand-edited or obsolete value falls back to the
 * day's opening rather than failing the page. The day's own bounds finish the
 * job. Here and not on the view: the page reads it before the view exists,
 * and a static reference to the view would drag Leaflet into the page's chunk.
 */
export function readInstant(raw: string | null): number | null {
  const minute = raw === null || raw.trim() === '' ? Number.NaN : Number(raw);
  return Number.isFinite(minute) && minute >= 0 ? minute : null;
}
