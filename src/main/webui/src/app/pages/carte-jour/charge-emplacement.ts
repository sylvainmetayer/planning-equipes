// How many people each emplacement holds, and when — the load reading of the
// day map.
//
// The map says *how* a place is doing (every seat filled, some, none); this
// says *how many* are there. Same data, same rule: a person is present on a
// place at an instant when they hold a seat of one of its stands whose window
// covers that instant, half-open like the stand states of `carte-jour.ts`. A
// seat is the unit, so a relayed meal break keeps its holder counted on the
// stand — a break is not an absence from the place — and a seat nobody holds
// (an absence marked on the day, a gap the solve left) counts nobody.
//
// Pure functions, next to the map's own: "how many at 16:00 on the Place du
// Drapeau" is tested without a map or a table.

import { Emplacement } from '../../core/models';
import { JourneeCarte, StandJour, etatStandInstant, formatMinutes } from './carte-jour';

/** What the load grid spans: the day on screen, or the whole event one column per day. */
export type PorteeCharge = 'jour' | 'evenement';

/**
 * Reads the `charge` query param; anything unknown is the day, the grid's
 * default. Here rather than on the grid so the Journée page reads it without
 * importing a component.
 */
export function readPorteeCharge(value: string | null): PorteeCharge {
  return value === 'evenement' ? 'evenement' : 'jour';
}

/** One stand's share of a place's load at one instant. */
export interface ChargeStand {
  standId: string;
  nom: string;
  /** Filled seats covering the instant: the people there. */
  presents: number;
  /** Seats covering the instant, filled or not. */
  sieges: number;
}

/** One place at one instant. `emplacementId` is null for the stands tied to none. */
export interface ChargeEmplacement {
  emplacementId: string | null;
  nom: string;
  /** False when the place has no coordinates, or is no place at all: it is then off the map. */
  situe: boolean;
  presents: number;
  sieges: number;
  /** Every stand of the place that day, open or not, alphabetically. */
  parStand: ChargeStand[];
}

/** One column of the load grid: a span of the day over which nobody arrives nor leaves. */
export interface TrancheCharge {
  debutMinutes: number;
  /** Exclusive, like a seat's window. */
  finMinutes: number;
  libelle: string;
}

export interface CelluleCharge {
  presents: number;
  sieges: number;
}

export interface LigneCharge {
  emplacementId: string | null;
  nom: string;
  situe: boolean;
  cellules: CelluleCharge[];
}

/** Places × spans of one day, plus the total on site. */
export interface GrilleCharge {
  tranches: TrancheCharge[];
  lignes: LigneCharge[];
  total: CelluleCharge[];
  /** The fullest cell of the grid, what the density scale and the marker size are relative to. */
  presentsMax: number;
}

/** One cell of the event-wide reading: a place's peak on one day. */
export interface CellulePic extends CelluleCharge {
  /** The minute the peak starts at; null on a day the place holds nobody. */
  minutes: number | null;
}

/** Places × days: each cell the peak of that place on that day. */
export interface GrilleChargeEvenement {
  jours: { jour: number; title: string }[];
  lignes: { emplacementId: string | null; nom: string; situe: boolean; cellules: CellulePic[] }[];
  /** Peak of the whole site each day — not the sum of the places' peaks, which need not coincide. */
  total: CellulePic[];
  presentsMax: number;
}

/** Key of the row gathering the stands tied to no emplacement. */
const NO_EMPLACEMENT_KEY = '';

interface Lieu {
  id: string | null;
  nom: string;
  situe: boolean;
}

/**
 * The place a stand counts on. The referential wins over the copy the plan
 * carries, as on the map, so a name edited since the solve reads the same in
 * the grid and in the tooltip.
 */
function placeOf(stand: StandJour, referentiel: Map<string, Emplacement>): Lieu {
  const porte = stand.emplacement;
  if (!porte) {
    return {
      id: null,
      nom: $localize`:@@carteJour.charge.sansEmplacement:Sans emplacement`,
      situe: false,
    };
  }
  const emplacement = referentiel.get(porte.id) ?? porte;
  return {
    id: emplacement.id,
    nom: emplacement.nom || emplacement.id,
    situe: emplacement.latitude != null && emplacement.longitude != null,
  };
}

/**
 * Every place holding a stand that day, with the people on it at `minutes`.
 * Places come alphabetically, the stands with no emplacement last in a row of
 * their own — counted, never dropped.
 */
export function chargeParEmplacement(
  journee: JourneeCarte | null,
  minutes: number,
  emplacements: readonly Emplacement[] = [],
): ChargeEmplacement[] {
  if (!journee) {
    return [];
  }
  const referentiel = new Map(emplacements.map((emplacement) => [emplacement.id, emplacement]));
  const lieux = new Map<string, ChargeEmplacement>();
  journee.stands.forEach((stand) => {
    const lieu = placeOf(stand, referentiel);
    const key = lieu.id ?? NO_EMPLACEMENT_KEY;
    let charge = lieux.get(key);
    if (!charge) {
      charge = {
        emplacementId: lieu.id,
        nom: lieu.nom,
        situe: lieu.situe,
        presents: 0,
        sieges: 0,
        parStand: [],
      };
      lieux.set(key, charge);
    }
    const instant = etatStandInstant(stand, minutes);
    charge.presents += instant.pourvus;
    charge.sieges += instant.sieges;
    charge.parStand.push({
      standId: stand.standId,
      nom: stand.nom,
      presents: instant.pourvus,
      sieges: instant.sieges,
    });
  });
  const charges = Array.from(lieux.values());
  charges.forEach((charge) =>
    charge.parStand.sort((left, right) => left.nom.localeCompare(right.nom)),
  );
  return charges.sort(
    (left, right) =>
      Number(left.emplacementId === null) - Number(right.emplacementId === null) ||
      left.nom.localeCompare(right.nom),
  );
}

/**
 * The spans the grid's columns stand for. Cut at every instant a seat starts
 * or ends — the timeslots of the day, and the narrower windows a partial
 * closure gives some seats — so the load is constant over each span and a
 * cell says exactly what the map says anywhere inside it. Spans holding no
 * seat at all are left out, and two neighbours reading the same on every row
 * are merged: the grid then has as many columns as the day has changes.
 */
function spansOfDay(journee: JourneeCarte): number[][] {
  const bornes = new Set<number>();
  journee.stands.forEach((stand) =>
    stand.postes.forEach((poste) => {
      bornes.add(poste.debutMinutes);
      bornes.add(poste.finMinutes);
    }),
  );
  const triees = Array.from(bornes).sort((left, right) => left - right);
  const paires: number[][] = [];
  for (let index = 0; index + 1 < triees.length; index++) {
    paires.push([triees[index], triees[index + 1]]);
  }
  return paires;
}

/** The whole day as places × spans. */
export function grilleCharge(
  journee: JourneeCarte | null,
  emplacements: readonly Emplacement[] = [],
): GrilleCharge {
  if (!journee) {
    return { tranches: [], lignes: [], total: [], presentsMax: 0 };
  }
  const colonnes: { debut: number; fin: number; charges: ChargeEmplacement[] }[] = [];
  spansOfDay(journee).forEach(([debut, fin]) => {
    const charges = chargeParEmplacement(journee, debut, emplacements);
    if (charges.every((charge) => charge.sieges === 0)) {
      return;
    }
    const precedente = colonnes[colonnes.length - 1];
    if (precedente && precedente.fin === debut && memesCharges(precedente.charges, charges)) {
      precedente.fin = fin;
      return;
    }
    colonnes.push({ debut, fin, charges });
  });

  // Every column lists the same places in the same order: they come from the
  // day's stands, not from the instant.
  const lieux = chargeParEmplacement(journee, journee.debutMinutes, emplacements);
  const lignes: LigneCharge[] = lieux.map((lieu, index) => ({
    emplacementId: lieu.emplacementId,
    nom: lieu.nom,
    situe: lieu.situe,
    cellules: colonnes.map((colonne) => ({
      presents: colonne.charges[index].presents,
      sieges: colonne.charges[index].sieges,
    })),
  }));
  const total = colonnes.map((colonne) => ({
    presents: colonne.charges.reduce((somme, charge) => somme + charge.presents, 0),
    sieges: colonne.charges.reduce((somme, charge) => somme + charge.sieges, 0),
  }));
  return {
    tranches: colonnes.map((colonne) => ({
      debutMinutes: colonne.debut,
      finMinutes: colonne.fin,
      libelle: `${formatMinutes(colonne.debut)} – ${formatMinutes(colonne.fin)}`,
    })),
    lignes,
    total,
    presentsMax: Math.max(0, ...lignes.flatMap((ligne) => ligne.cellules.map((c) => c.presents))),
  };
}

function memesCharges(left: ChargeEmplacement[], right: ChargeEmplacement[]): boolean {
  return left.every(
    (charge, index) =>
      charge.presents === right[index].presents && charge.sieges === right[index].sieges,
  );
}

/**
 * The event as places × days, each cell the busiest span of that place on that
 * day — to spot the days a place is under pressure before opening one of them.
 */
export function grilleChargeEvenement(
  journees: readonly JourneeCarte[],
  emplacements: readonly Emplacement[] = [],
): GrilleChargeEvenement {
  const grilles = journees.map((journee) => grilleCharge(journee, emplacements));
  const lieux = new Map<string, { emplacementId: string | null; nom: string; situe: boolean }>();
  grilles.forEach((grille) =>
    grille.lignes.forEach((ligne) => {
      const key = ligne.emplacementId ?? NO_EMPLACEMENT_KEY;
      if (!lieux.has(key)) {
        lieux.set(key, { emplacementId: ligne.emplacementId, nom: ligne.nom, situe: ligne.situe });
      }
    }),
  );
  const ordonnes = Array.from(lieux.values()).sort(
    (left, right) =>
      Number(left.emplacementId === null) - Number(right.emplacementId === null) ||
      left.nom.localeCompare(right.nom),
  );
  const lignes = ordonnes.map((lieu) => ({
    ...lieu,
    cellules: grilles.map((grille) => {
      const ligne = grille.lignes.find((each) => each.emplacementId === lieu.emplacementId);
      return pic(grille.tranches, ligne?.cellules ?? []);
    }),
  }));
  return {
    jours: journees.map((journee) => ({ jour: journee.jour, title: journee.title })),
    lignes,
    total: grilles.map((grille) => pic(grille.tranches, grille.total)),
    presentsMax: Math.max(0, ...lignes.flatMap((ligne) => ligne.cellules.map((c) => c.presents))),
  };
}

/** The first span holding the most people; a place nobody holds that day peaks nowhere. */
function pic(tranches: TrancheCharge[], cellules: CelluleCharge[]): CellulePic {
  let meilleur: CellulePic = { presents: 0, sieges: 0, minutes: null };
  cellules.forEach((cellule, index) => {
    if (cellule.presents > meilleur.presents) {
      meilleur = { ...cellule, minutes: tranches[index].debutMinutes };
    }
  });
  return meilleur;
}

/** Density step of a cell, 0 (nobody) to 4 (the fullest of the grid): colour only, the number is printed. */
export function niveauDensite(presents: number, presentsMax: number): number {
  if (presents <= 0 || presentsMax <= 0) {
    return 0;
  }
  return Math.min(4, Math.max(1, Math.ceil((presents / presentsMax) * 4)));
}

/** Smallest and largest diameter of a map marker, in pixels. */
export const TAILLE_PASTILLE_MIN = 28;
export const TAILLE_PASTILLE_MAX = 56;

/**
 * Diameter of a marker holding `presents` people when the fullest place of the
 * day holds `presentsMax`. The *area* is what grows with the headcount — a
 * diameter proportional to it would make a place of 20 look four times one of
 * 10 — and the scale is the day's rather than the instant's, so a marker does
 * not swell just because its neighbours emptied.
 */
export function taillePastille(presents: number, presentsMax: number): number {
  if (presents <= 0 || presentsMax <= 0) {
    return TAILLE_PASTILLE_MIN;
  }
  const ratio = Math.sqrt(Math.min(presents, presentsMax) / presentsMax);
  return Math.round(TAILLE_PASTILLE_MIN + ratio * (TAILLE_PASTILLE_MAX - TAILLE_PASTILLE_MIN));
}
