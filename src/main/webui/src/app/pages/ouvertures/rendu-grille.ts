// How one cell of the « Horaires des stands » grid is drawn: the field holds
// the headcount, and behind it thin bars say what the three layers make of the
// timeslot — the stand's own hours, the consigne of the day, and the opening
// the seats are cut from. Pure functions, tested without a DOM: the layers
// come from `GET /api/ouvertures-stands/couches` (`calendrier-couches.ts`
// words them), the result from the report the grid is read from, so nothing
// here re-reads a rule.
//
// The bars are one `background-image` per cell rather than elements: four
// thousand cells of three spans each would be twelve thousand nodes on a grid
// whose whole design is that it follows the keystroke.

import { LayerWindow, SegmentCellule, SourceHoraire } from '../../core/models';
import { Couche } from './calendrier-couches';
import { minutesDe } from './journee-stands';

const MINUTES_PER_DAY = 24 * 60;

/** A stretch of the day in minutes from its midnight, the end past 1440 after it. */
export type Intervalle = readonly [number, number];

/** A stretch of a cell, as fractions of its width, `0` to `1`. */
export type Portion = readonly [number, number];

/** The column's own bounds; an end at or before the start crosses midnight. */
export function columnSpan(heureDebut: string, heureFin: string): Intervalle {
  const debut = minutesDe(heureDebut);
  let fin = minutesDe(heureFin);
  if (fin <= debut) {
    fin += MINUTES_PER_DAY;
  }
  return [debut, fin];
}

/**
 * Wall-clock stretches of a cell laid on its column: a stretch starting
 * before the column is the part of it past midnight.
 */
export function segmentSpans(
  segments: readonly SegmentCellule[],
  colonne: Intervalle,
): Intervalle[] {
  return segments.map((segment) => {
    let debut = minutesDe(segment.heureDebut);
    if (debut < colonne[0]) {
      debut += MINUTES_PER_DAY;
    }
    const [d, f] = columnSpan(segment.heureDebut, segment.heureFin);
    return [debut, debut + (f - d)] as const;
  });
}

/** The layer's windows as stretches. */
export function windowSpans(fenetres: readonly LayerWindow[]): Intervalle[] {
  return fenetres.map((fenetre) => [fenetre.debutMinutes, fenetre.finMinutes] as const);
}

/** What of `spans` falls inside the column, as fractions of its width, in order. */
export function portionsIn(spans: readonly Intervalle[], colonne: Intervalle): Portion[] {
  const largeur = colonne[1] - colonne[0];
  if (largeur <= 0) {
    return [];
  }
  return spans
    .map(([debut, fin]) => [Math.max(debut, colonne[0]), Math.min(fin, colonne[1])] as const)
    .filter(([debut, fin]) => fin > debut)
    .map(([debut, fin]) => [(debut - colonne[0]) / largeur, (fin - colonne[0]) / largeur] as const)
    .sort((gauche, droite) => gauche[0] - droite[0]);
}

/** What a cell says, layer by layer, before it is drawn. */
export interface CoucheCellule {
  /** The opening the seats are cut from: the server's stretches, or the typed value over the whole column. */
  resultat: readonly Portion[];
  /** Which of the stand's own layers decided the day: the colour of the result. */
  source: SourceHoraire;
  /** The stand's windows before the consigne; `null` until the layers are read. */
  nominal: readonly Portion[] | null;
  /** The band the day's consigne closes. */
  bande: readonly Portion[];
  /** The windows the consigne reopens this stand on. */
  reouvertures: readonly Portion[];
}

/** The three properties the template binds; `null` draws nothing. */
export interface RenduCellule {
  image: string | null;
  size: string | null;
  position: string | null;
}

const RIEN: RenduCellule = { image: null, size: null, position: null };

const COULEUR_SOURCE: Record<SourceHoraire, string> = {
  REGLE: 'var(--ouv-regle)',
  EXCEPTION: 'var(--ouv-exception)',
  DEFAUT: 'var(--ouv-defaut)',
};

/** One lane: a horizontal gradient painting `portions` in `couleur`, the rest transparent. */
function lane(portions: readonly Portion[], couleur: string): string {
  const stops = ['transparent 0%'];
  for (const [debut, fin] of portions) {
    const d = `${round(debut * 100)}%`;
    const f = `${round(fin * 100)}%`;
    stops.push(`transparent ${d}`, `${couleur} ${d}`, `${couleur} ${f}`, `transparent ${f}`);
  }
  stops.push('transparent 100%');
  return `linear-gradient(to right, ${stops.join(', ')})`;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}

/**
 * The cell's bars for the layers ticked in `?couches=`: the result along the
 * bottom edge, the stand's own hours along the top, the consigne's band as a
 * tint over the whole height, its reopenings under the top edge. A layer with
 * nothing in the cell adds nothing, and no layer at all draws nothing.
 */
export function renderCell(cellule: CoucheCellule, couches: ReadonlySet<Couche>): RenduCellule {
  const images: string[] = [];
  const sizes: string[] = [];
  const positions: string[] = [];
  const add = (image: string, size: string, position: string) => {
    images.push(image);
    sizes.push(size);
    positions.push(position);
  };
  if (couches.has('resultat') && cellule.resultat.length > 0) {
    add(lane(cellule.resultat, COULEUR_SOURCE[cellule.source]), '100% 5px', '0 100%');
  }
  if (couches.has('stand') && cellule.nominal && cellule.nominal.length > 0) {
    add(lane(cellule.nominal, 'var(--ouv-nominal)'), '100% 3px', '0 0');
  }
  if (couches.has('consigne')) {
    if (cellule.reouvertures.length > 0) {
      add(lane(cellule.reouvertures, 'var(--ouv-reouverture)'), '100% 3px', '0 4px');
    }
    if (cellule.bande.length > 0) {
      add(lane(cellule.bande, 'var(--ouv-bande)'), '100% 100%', '0 0');
    }
  }
  if (images.length === 0) {
    return RIEN;
  }
  return { image: images.join(', '), size: sizes.join(', '), position: positions.join(', ') };
}
