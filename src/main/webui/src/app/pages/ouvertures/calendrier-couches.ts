// « Calendrier combiné » on the openings page: a week of the event, one line
// per stand, and in every cell the three layers that decide the seats a solve
// will receive — the stand's own hours, the grid's timeslots, the consigne of
// the day — over the result. Pure functions, tested without a DOM: the layers
// come from `GET /api/ouvertures-stands/couches`, which reads them off the two
// resolvers, and the result is `buildJourneeStands` over the very report the
// Journée view draws, so the seats shown here are those seats by construction.

import {
  LayerCell,
  ConsigneLayer,
  OpeningLayers,
  LayerWindow,
  RapportOuvertures,
  LayerTimeslot,
} from '../../core/models';
import { BlocStand, buildJourneeStands, minutesDe } from './journee-stands';

/** The four layers a cell can show, each behind its own checkbox. */
export type Couche = 'stand' | 'creneaux' | 'consigne' | 'resultat';

export const COUCHES: readonly Couche[] = ['stand', 'creneaux', 'consigne', 'resultat'];

/** What `?couches=` holds when every box is unticked: an empty value would read as « absent », i.e. all. */
const AUCUNE = 'aucune';

/** How many event days a page of the calendar shows: a month of sixty stands would be 1 800 cells. */
export const DAYS_PER_PAGE = 7;

const MINUTES_PER_DAY = 24 * 60;

/** `?couches=stand,resultat` → the layers shown; absent = all four, unknown names ignored. */
export function readCouchesParam(param: string | null): Couche[] {
  if (param === null || param === '') {
    return [...COUCHES];
  }
  if (param === AUCUNE) {
    return [];
  }
  const demandees = new Set(param.split(','));
  return COUCHES.filter((couche) => demandees.has(couche));
}

/** The param for a set of layers: none when all four are shown, the default. */
export function writeCouchesParam(couches: readonly Couche[]): string | null {
  const affichees = COUCHES.filter((couche) => couches.includes(couche));
  if (affichees.length === COUCHES.length) {
    return null;
  }
  return affichees.length === 0 ? AUCUNE : affichees.join(',');
}

/** One layer ticked or unticked, the others kept, in their canonical order. */
export function toggleCouche(
  couches: readonly Couche[],
  couche: Couche,
  visible: boolean,
): Couche[] {
  return COUCHES.filter((each) => (each === couche ? visible : couches.includes(each)));
}

/**
 * The event days of the page starting at `du`: the first day on or after it,
 * and the `DAYS_PER_PAGE - 1` days that follow; from the first day when `du`
 * is absent or past the last one.
 */
export function pageDays(dates: readonly string[], du: string | null): string[] {
  let debut = du === null ? 0 : dates.findIndex((date) => date >= du);
  if (debut < 0) {
    debut = 0;
  }
  return dates.slice(debut, debut + DAYS_PER_PAGE);
}

/** The first day of the page before or after this one, `null` at either end. */
export function neighbourPage(
  dates: readonly string[],
  page: readonly string[],
  sens: -1 | 1,
): string | null {
  if (page.length === 0) {
    return null;
  }
  const debut = dates.indexOf(page[0]);
  const suivant = debut + sens * DAYS_PER_PAGE;
  if (sens > 0) {
    return suivant < dates.length ? dates[suivant] : null;
  }
  return debut <= 0 ? null : dates[Math.max(0, suivant)];
}

/** A positioned stretch of a cell, in percentages of its day's scale. */
export interface SpanCouche {
  offsetPercent: number;
  widthPercent: number;
  label: string;
}

export interface SpanNominal extends SpanCouche {
  /** Decided by a dated exception rather than a recurring rule: drawn differently. */
  exception: boolean;
}

export interface SpanVacation extends SpanCouche {
  id: number | null;
  couverturePause: boolean;
  ajouteeParConsigne: boolean;
}

export interface CelluleCalendrier {
  date: string;
  /** Open by default: the stand declares no hours at all. */
  defaut: boolean;
  nominales: SpanNominal[];
  /** A consigne governs the day. */
  sousConsigne: boolean;
  /** The consigne's band, `null` on an ordinary day. */
  bande: SpanCouche | null;
  reouvertures: SpanCouche[];
  vacations: SpanVacation[];
  /** The seats' blocks, exactly as the Journée view draws them. */
  resultat: BlocStand[];
  /** The cell in one sentence, for its tooltip and for a screen reader. */
  explication: string;
}

export interface JourCalendrier {
  date: string;
  jour: number;
  ferie: string | null;
  consigne: ConsigneLayer | null;
}

export interface LigneCalendrier {
  standId: string;
  nom: string;
  cellules: CelluleCalendrier[];
}

export interface CalendrierCouches {
  jours: JourCalendrier[];
  lignes: LigneCalendrier[];
}

/**
 * The calendar as the template binds it. `standIds` narrows the lines to the
 * stands the page's filter keeps; `null` keeps them all. Each day is measured
 * on the scale the Journée view gives it, so a block of the result sits at
 * the same place on both screens.
 */
export function buildCalendrierCouches(
  couches: OpeningLayers,
  rapport: RapportOuvertures,
  standIds: ReadonlySet<string> | null = null,
): CalendrierCouches {
  const jours: JourCalendrier[] = couches.jours.map((jour) => ({
    date: jour.date,
    jour: jour.jour,
    ferie: jour.ferie,
    consigne: jour.consigne,
  }));
  const echelles = new Map(
    couches.jours.map((jour) => {
      const journee = buildJourneeStands(rapport, jour.date, standIds);
      const debut =
        journee?.debutMinutes ?? Math.min(...jour.vacations.map((each) => each.debutMinutes));
      const fin = journee?.finMinutes ?? Math.max(...jour.vacations.map((each) => each.finMinutes));
      const blocs = new Map((journee?.lignes ?? []).map((ligne) => [ligne.standId, ligne.blocs]));
      return [jour.date, { debut, fin: Math.max(fin, debut + 60), blocs, jour }] as const;
    }),
  );

  const lignes: LigneCalendrier[] = couches.stands
    .filter((ligne) => standIds === null || standIds.has(ligne.standId))
    .map((ligne) => ({
      standId: ligne.standId,
      nom: ligne.nom || ligne.standId,
      cellules: ligne.jours.flatMap((cellule) => {
        const echelle = echelles.get(cellule.date);
        if (!echelle) {
          return [];
        }
        const span = (debut: number, fin: number, label: string) =>
          place(debut, fin, echelle.debut, echelle.fin, label);
        const consigne = echelle.jour.consigne;
        return [
          {
            date: cellule.date,
            defaut: cellule.source === 'DEFAUT',
            nominales: cellule.nominal.flatMap((fenetre) => {
              const placee = span(
                fenetre.debutMinutes,
                fenetre.finMinutes,
                libelleFenetre(fenetre),
              );
              return placee ? [{ ...placee, exception: cellule.source === 'EXCEPTION' }] : [];
            }),
            sousConsigne: consigne !== null,
            bande: consigne
              ? span(
                  consigne.debutMinutes,
                  consigne.finMinutes,
                  libelleIntervalle(consigne.debutMinutes, consigne.finMinutes),
                )
              : null,
            reouvertures: cellule.reopenings.flatMap((fenetre) => {
              const placee = span(
                fenetre.debutMinutes,
                fenetre.finMinutes,
                libelleFenetre(fenetre),
              );
              return placee ? [placee] : [];
            }),
            vacations: echelle.jour.vacations.flatMap((vacation) => {
              const placee = span(
                vacation.debutMinutes,
                vacation.finMinutes,
                libelleIntervalle(vacation.debutMinutes, vacation.finMinutes),
              );
              return placee ? [vacationSpan(placee, vacation)] : [];
            }),
            resultat: echelle.blocs.get(ligne.standId) ?? [],
            explication: explainCell(cellule, consigne),
          },
        ];
      }),
    }));
  return { jours, lignes };
}

function vacationSpan(span: SpanCouche, vacation: LayerTimeslot): SpanVacation {
  return {
    ...span,
    id: vacation.id,
    couverturePause: vacation.couverturePause,
    ajouteeParConsigne: vacation.addedByConsigne,
  };
}

/** A stretch on the day's scale, clipped to it; `null` when it falls outside. */
function place(
  debut: number,
  fin: number,
  echelleDebut: number,
  echelleFin: number,
  label: string,
): SpanCouche | null {
  const d = Math.max(debut, echelleDebut);
  const f = Math.min(fin, echelleFin);
  if (d >= f) {
    return null;
  }
  const amplitude = echelleFin - echelleDebut;
  return {
    offsetPercent: ((d - echelleDebut) / amplitude) * 100,
    widthPercent: ((f - d) / amplitude) * 100,
    label,
  };
}

/** Minutes from the day's midnight → `HH:mm`; past midnight wraps. */
export function timeOf(minutes: number): string {
  const withinDay = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
  const heures = Math.floor(withinDay / 60);
  const reste = withinDay % 60;
  return `${String(heures).padStart(2, '0')}:${String(reste).padStart(2, '0')}`;
}

/** `14:00–20:00`; a stretch running to midnight ends at `24:00`, not at the `00:00` it started from. */
function libelleIntervalle(debut: number, fin: number): string {
  return `${timeOf(debut)}–${fin === MINUTES_PER_DAY ? '24:00' : timeOf(fin)}`;
}

function libelleFenetre(fenetre: LayerWindow): string {
  const intervalle = libelleIntervalle(fenetre.debutMinutes, fenetre.finMinutes);
  return fenetre.effectif === null ? intervalle : `${intervalle} · ${fenetre.effectif}`;
}

function listeFenetres(fenetres: readonly LayerWindow[]): string {
  return fenetres
    .map((fenetre) => libelleIntervalle(fenetre.debutMinutes, fenetre.finMinutes))
    .join(', ');
}

/**
 * The cell in one sentence: what is open, then which layer said so — « Ouvert
 * 14:00–18:00 : règle récurrente 14:00–20:00, amputée par la consigne « Plan
 * canicule » 18:00–20:00 ». Worded from what the server resolved; no rule is
 * re-read here.
 */
export function explainCell(cellule: LayerCell, consigne: ConsigneLayer | null): string {
  const etat =
    cellule.effective.length === 0
      ? $localize`:@@ouvertures.couches.explication.ferme:Fermé`
      : $localize`:@@ouvertures.couches.explication.ouvert:Ouvert ${listeFenetres(cellule.effective)}:fenetres:`;
  const nominal = explainNominal(cellule);
  if (!consigne) {
    return `${etat} : ${nominal}`;
  }
  const bande = libelleIntervalle(consigne.debutMinutes, consigne.finMinutes);
  const amputee = $localize`:@@ouvertures.couches.explication.consigne:amputé par la consigne « ${consigne.prereglage || consigne.motif}:consigne: » ${bande}:bande:`;
  const rouverte =
    cellule.reopenings.length === 0
      ? ''
      : ', ' +
        $localize`:@@ouvertures.couches.explication.reouverture:rouvert ${listeFenetres(cellule.reopenings)}:fenetres:`;
  return `${etat} : ${nominal}, ${amputee}${rouverte}`;
}

function explainNominal(cellule: LayerCell): string {
  const motif = cellule.motif ? ` (${cellule.motif})` : '';
  switch (cellule.source) {
    case 'DEFAUT':
      return $localize`:@@ouvertures.couches.explication.defaut:aucun horaire déclaré, ouvert par défaut`;
    case 'EXCEPTION':
      return cellule.nominal.length === 0
        ? $localize`:@@ouvertures.couches.explication.exceptionFermee:fermé par une exception datée${motif}:motif:`
        : $localize`:@@ouvertures.couches.explication.exception:exception datée ${listeFenetres(cellule.nominal)}:fenetres:${motif}:motif:`;
    case 'REGLE':
      return cellule.nominal.length === 0
        ? $localize`:@@ouvertures.couches.explication.regleFermee:les règles du stand ne l'ouvrent pas ce jour-là`
        : $localize`:@@ouvertures.couches.explication.regle:règle récurrente ${listeFenetres(cellule.nominal)}:fenetres:${motif}:motif:`;
  }
}

/** `HH:mm[:ss]` of a consigne's band, for the day header. */
export function bandLabel(consigne: ConsigneLayer): string {
  const debut = minutesDe(consigne.fermetureDebut);
  const fin = consigne.fermetureFin === null ? MINUTES_PER_DAY : minutesDe(consigne.fermetureFin);
  return libelleIntervalle(debut, fin === 0 ? MINUTES_PER_DAY : fin);
}
