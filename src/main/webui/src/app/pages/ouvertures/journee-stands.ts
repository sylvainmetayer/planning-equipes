// « Journée » on the openings page: one day of the edition, one line per
// stand, the hours on the x axis, the créneaux as bands and each open stretch
// as a block carrying its headcount. Pure functions over the report the two
// other views already read — the same numbers, laid on time.

import { formatHourTick } from '../../core/time-of-day';
import {
  AnomalieOuverture,
  CelluleJourOuverture,
  EtatOuverture,
  JourAmplitude,
  RapportOuvertures,
} from '../../core/models';

/** A positioned span of the track, in percentages of the day's scale. */
export interface SpanJournee {
  heureDebut: string;
  heureFin: string;
  offsetPercent: number;
  widthPercent: number;
  label: string;
}

/** One open stretch of a stand, with what it asks for. */
export interface BlocStand extends SpanJournee {
  effectif: number;
  /** Inside a meal-relay créneau: the seats generated are half the headcount, rounded up. */
  couverturePause: boolean;
}

/** One créneau of the day, drawn as a band behind every line. */
export interface BandeCreneau extends SpanJournee {
  id: number;
  couverturePause: boolean;
}

export interface LigneJournee {
  standId: string;
  nom: string;
  etat: EtatOuverture;
  postes: number;
  blocs: BlocStand[];
  /** Windows the stand declares at an hour no créneau covers: drawn hatched, they produce nothing. */
  horsGrille: SpanJournee[];
  /** What a screen reader gets for the whole line. */
  resume: string;
}

export interface HeureJournee {
  minutes: number;
  label: string;
  offsetPercent: number;
}

export interface JourneeStandsVue {
  date: string;
  jour: number;
  debutMinutes: number;
  finMinutes: number;
  heures: HeureJournee[];
  bandes: BandeCreneau[];
  lignes: LigneJournee[];
}

/** How a window running « jusqu'à la fermeture » reads: no end was typed, so none is shown. */
const OUVERTE = '…';

/** `HH:mm[:ss]` → minutes from midnight. */
export function minutesDe(heure: string): number {
  const [h, m] = heure.split(':').map(Number);
  return h * 60 + (m || 0);
}

/** A stretch as `[debut, fin]` minutes; an end at or before the start crosses midnight. */
function intervalle(heureDebut: string, heureFin: string): [number, number] {
  const debut = minutesDe(heureDebut);
  let fin = minutesDe(heureFin);
  if (fin <= debut) {
    fin += 24 * 60;
  }
  return [debut, fin];
}

/**
 * The day laid on time, or `null` when the report has no such day. The scale
 * runs from the day's first créneau to its last, rounded outwards to whole
 * hours, and stretched to show a window declared outside the grid — hiding
 * the one thing this view exists to reveal would defeat it.
 */
export function buildJourneeStands(
  rapport: RapportOuvertures,
  date: string,
  standIds: ReadonlySet<string> | null = null,
): JourneeStandsVue | null {
  const jour = rapport.jours.find((candidat) => candidat.date === date);
  if (!jour) {
    return null;
  }
  const horsGrille = fenetresHorsGrille(rapport.anomalies, date);
  let [debut, fin] = intervalle(jour.heureDebut, jour.heureFin);
  for (const spans of horsGrille.values()) {
    for (const [d, f] of spans) {
      debut = Math.min(debut, d);
      fin = Math.max(fin, f);
    }
  }
  const debutMinutes = Math.floor(debut / 60) * 60;
  const finMinutes = Math.max(Math.ceil(fin / 60) * 60, debutMinutes + 60);
  const amplitude = finMinutes - debutMinutes;
  const percent = (minutes: number) => ((minutes - debutMinutes) / amplitude) * 100;
  const span = (heureDebut: string, heureFin: string, label: string): SpanJournee => {
    const [d, f] = intervalle(heureDebut, heureFin);
    return {
      heureDebut: court(heureDebut),
      heureFin: court(heureFin),
      offsetPercent: percent(d),
      widthPercent: ((f - d) / amplitude) * 100,
      label,
    };
  };

  const heures: HeureJournee[] = [];
  for (let minutes = debutMinutes; minutes <= finMinutes; minutes += 60) {
    heures.push({ minutes, label: formatHourTick(minutes / 60), offsetPercent: percent(minutes) });
  }
  const bandes: BandeCreneau[] = jour.creneaux.map((colonne) => ({
    ...span(
      colonne.heureDebut,
      colonne.heureFin,
      `${court(colonne.heureDebut)}–${court(colonne.heureFin)}`,
    ),
    id: colonne.id,
    couverturePause: colonne.couverturePause,
  }));
  const relais = relaisParCreneau(jour);

  const lignes: LigneJournee[] = rapport.stands
    .filter((ligne) => standIds === null || standIds.has(ligne.standId))
    .map((ligne) => {
      const cellule = ligne.jours.find((candidat) => candidat.date === date);
      const nom = ligne.nom || ligne.standId;
      const blocs = cellule ? blocsDe(cellule, relais, span) : [];
      // Built without `span`: the window is placed from the bounds computed
      // when it was read, and an open-ended one has no end to format.
      const dehors = (horsGrille.get(ligne.standId) ?? []).map(([d, f, heureDebut, heureFin]) => {
        const fin = heureFin === null ? OUVERTE : court(heureFin);
        return {
          heureDebut: court(heureDebut),
          heureFin: fin,
          offsetPercent: percent(d),
          widthPercent: ((f - d) / amplitude) * 100,
          label: `${nom} · ${court(heureDebut)}–${fin}`,
        };
      });
      return {
        standId: ligne.standId,
        nom,
        etat: cellule?.etat ?? 'FERME',
        postes: cellule?.postes ?? 0,
        blocs,
        horsGrille: dehors,
        resume: resume(nom, blocs, dehors),
      };
    });

  return { date, jour: jour.jour, debutMinutes, finMinutes, heures, bandes, lignes };
}

/** Width of one hour as a `background-size`, so the gridlines are one repeating background. */
export function pasHoraire(vue: JourneeStandsVue): string {
  return `${100 / ((vue.finMinutes - vue.debutMinutes) / 60)}% 100%`;
}

function court(heure: string): string {
  return heure.length > 5 ? heure.slice(0, 5) : heure;
}

/** `creneauId@tranche` → is the column a meal relay. */
function relaisParCreneau(jour: JourAmplitude): Map<string, boolean> {
  const index = new Map<string, boolean>();
  for (const colonne of jour.creneaux) {
    index.set(cleColonne(colonne.id, colonne.tranche), colonne.couverturePause);
  }
  return index;
}

function cleColonne(id: number, tranche: number): string {
  return `${id}@${tranche}`;
}

function blocsDe(
  cellule: CelluleJourOuverture,
  relais: Map<string, boolean>,
  span: (heureDebut: string, heureFin: string, label: string) => SpanJournee,
): BlocStand[] {
  const blocs: BlocStand[] = [];
  for (const creneau of cellule.creneaux) {
    const couverturePause = relais.get(cleColonne(creneau.creneauId, creneau.tranche)) ?? false;
    for (const segment of creneau.segments) {
      blocs.push({
        ...span(
          segment.heureDebut,
          segment.heureFin,
          `${court(segment.heureDebut)}–${court(segment.heureFin)} · ${segment.effectif}`,
        ),
        effectif: segment.effectif,
        couverturePause,
      });
    }
  }
  return blocs.sort((gauche, droite) => gauche.offsetPercent - droite.offsetPercent);
}

/**
 * The windows without effect of `date`, by stand, as `[debut, fin, heureDebut,
 * heureFin]` — an open-ended one runs to midnight, which is where the grid
 * would have had to reach for it to matter.
 */
function fenetresHorsGrille(
  anomalies: readonly AnomalieOuverture[],
  date: string,
): Map<string, [number, number, string, string | null][]> {
  const parStand = new Map<string, [number, number, string, string | null][]>();
  for (const anomalie of anomalies) {
    if (anomalie.type !== 'FENETRE_SANS_EFFET' || anomalie.date !== date || !anomalie.heureDebut) {
      continue;
    }
    const debut = minutesDe(anomalie.heureDebut);
    const fin = anomalie.heureFin ? intervalle(anomalie.heureDebut, anomalie.heureFin)[1] : 24 * 60;
    const liste = parStand.get(anomalie.standId) ?? [];
    // The end is kept null on an open-ended window: `fin` already carries its
    // numeric bound, and the label says « … » rather than a midnight nobody typed.
    liste.push([debut, fin, anomalie.heureDebut, anomalie.heureFin ?? null]);
    parStand.set(anomalie.standId, liste);
  }
  return parStand;
}

function resume(nom: string, blocs: BlocStand[], horsGrille: SpanJournee[]): string {
  if (blocs.length === 0 && horsGrille.length === 0) {
    return $localize`:@@ouvertures.journee.resume.ferme:${nom}:stand: : fermé ce jour`;
  }
  const ouvert = blocs
    .map((bloc) => `${bloc.heureDebut}–${bloc.heureFin} (${bloc.effectif})`)
    .join(', ');
  const dehors = horsGrille
    .map((fenetre) => `${fenetre.heureDebut}–${fenetre.heureFin}`)
    .join(', ');
  return dehors
    ? $localize`:@@ouvertures.journee.resume.avecHorsGrille:${nom}:stand: : ouvert ${ouvert}:ouvert: ; hors de toute vacation ${dehors}:dehors:`
    : $localize`:@@ouvertures.journee.resume.ouvert:${nom}:stand: : ouvert ${ouvert}:ouvert:`;
}
