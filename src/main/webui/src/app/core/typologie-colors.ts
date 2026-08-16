// Stable colour coding of the game typologies, shared by the views that show
// stands (animateur timeline, load heatmap). Typologies are a CRUD referential,
// so the palette cannot be an enum-indexed list: the colour is derived from the
// typologie id itself, which keeps a given typologie the same colour across
// pages, across reloads, and whatever else the referential contains.

import { Stand, TypologieItem } from './models';

/** Number of classes defined in `styles/typologie-colors.css`. */
export const TYPOLOGIE_COLOR_COUNT = 8;

/** Class used when a stand proposes no typologie at all. */
export const TYPOLOGIE_COLOR_NONE = 'typologie-color-none';

/** FNV-1a over the id, so the bucket is stable and well spread over short ids. */
export function typologieColorIndex(typologieId: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < typologieId.length; index += 1) {
    hash ^= typologieId.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return Math.abs(hash) % TYPOLOGIE_COLOR_COUNT;
}

export function typologieColorClass(typologieId: string | null | undefined): string {
  return typologieId ? `typologie-color-${typologieColorIndex(typologieId)}` : TYPOLOGIE_COLOR_NONE;
}

/**
 * The typologie a stand is coloured after. A stand may propose several; the
 * lowest id wins so the choice never depends on set iteration order.
 */
export function typologiePrincipale(typologies: readonly string[]): string | null {
  return [...typologies].sort((left, right) => left.localeCompare(right))[0] ?? null;
}

/** Referential label of a typologie, falling back to its raw id when unknown. */
export function typologieLabels(typologies: readonly TypologieItem[]): Map<string, string> {
  return new Map(typologies.map((typologie) => [typologie.id, typologie.label || typologie.id]));
}

export function typologieLabel(labels: Map<string, string>, typologieId: string): string {
  return labels.get(typologieId) ?? typologieId;
}

/** Typologies of a stand, sorted by display label. */
export function standTypologies(stand: Stand | null | undefined): string[] {
  return [...(stand?.typologiesProposees ?? [])];
}
