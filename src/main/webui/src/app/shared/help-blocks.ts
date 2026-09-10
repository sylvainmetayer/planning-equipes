// The one block model of the two user guides, and the one renderer.
//
// The organisers' guide (`pages/aide`) and the animateur's (`pages/
// espace-animateur`) say different things to different readers, and stay two
// contents on purpose. What they share is the shape of a paragraph, a list, a
// definition list — and until #392 each declared its own union of blocks and
// rendered it with its own `@switch`, so a block kind added on one side did
// not exist on the other. One union, one template, one stylesheet: a guide
// only chooses its sections.

import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** A term (a screen, a setting, a score component) and what it means. */
export interface HelpDefinition {
  term: string;
  text: string;
}

export type HelpBlock =
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; items: string[] }
  /** Numbered sequence: the reader is meant to follow it in order. */
  | { kind: 'steps'; items: string[] }
  | { kind: 'definitions'; items: HelpDefinition[] }
  /** A boxed aside the eye lands on: a distinction the prose around it keeps blurring. */
  | { kind: 'callout'; title: string; text: string };

/** Every searchable string of a block, for the guides' filter boxes and tests. */
export function helpBlockText(block: HelpBlock): string[] {
  switch (block.kind) {
    case 'paragraph':
      return [block.text];
    case 'list':
    case 'steps':
      return block.items;
    case 'definitions':
      return block.items.flatMap((item) => [item.term, item.text]);
    case 'callout':
      return [block.title, block.text];
  }
}

/**
 * Renders the blocks of one section, in order. Its stylesheet is scoped
 * (emulated encapsulation, like `map-picker`): the prose rules travel with the
 * component into both guides rather than being declared twice, once per page
 * sheet.
 */
@Component({
  selector: 'app-help-blocks',
  templateUrl: './help-blocks.html',
  styleUrl: './help-blocks.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HelpBlocks {
  readonly blocks = input.required<HelpBlock[]>();
}
