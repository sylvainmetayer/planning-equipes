// Keyboard navigation of the reference-data tables: which row the table hands
// the focus to, and what the arrows, Enter and Space do once it has it.
//
// Generalises the roving tabindex written twice by hand — the heatmap grid and
// the month calendar. Both hold the current cell in a signal, clamp it against
// what is actually displayed, put `tabindex="0"` on that one cell and `-1` on
// every other, and move the focus themselves on an arrow key. That is the
// whole mechanism, and it is the same one a list of rows needs; only the
// geometry differed (two axes there, one here), so what is shared is extracted
// rather than copied a third time.
//
// Three rules this class exists to keep:
//
//   - **A roving tabindex, not a focus trap.** Exactly one row is reachable by
//     Tab; Tab from there leaves the table, into the row's own action buttons
//     and then out of the page. Nothing is captured.
//   - **The table only reads keys aimed at the row itself.** A `keydown`
//     bubbles, so the delete button and the selection checkbox of a row both
//     send their Enter and their Space up to it; consuming those would break
//     the very controls the navigation is supposed to reach.
//   - **Nothing is consumed that the table does not use.** `/`, `?`, `g` and
//     Ctrl+K keep travelling to `keyboard-shortcuts.service.ts`, the one
//     global listener, which stops at a `defaultPrevented` event. Enter and
//     Space are no exception: they are consumed only once a row has really
//     been opened or ticked, never on the promise that someone might.
//   - **The selection is spoken, not written on the row.** `aria-selected` is
//     only a supported property of `role=row` inside a `grid`; these tables
//     are plain `role="table"`, so the row's own checkbox carries the state
//     and a live region announces the change Space makes.

import { Signal, linkedSignal, signal } from '@angular/core';
import { SelectionId, TableSelection } from './table-selection';

/**
 * Attribute the focused row is found by. An attribute rather than a position
 * in `children`: `mat-table` renders its own rows, and a sticky column or a
 * footer added later must not shift what "row number three" means.
 */
export const ROW_INDEX_ATTRIBUTE = 'data-row-index';

/**
 * `trackBy` of a navigable table: a row is its id, not its object. The store
 * replaces every object on a reload — coming back to the list from a fiche
 * triggers one — and a table tracking objects then rebuilds every row,
 * dropping the focus the keyboard had put on one of them.
 */
export function trackRowById(_index: number, row: { id: SelectionId }): SelectionId {
  return row.id;
}

/**
 * Index the given key moves the focus to, or `null` when the table has no
 * meaning for that key and must let it through.
 *
 * <p>Pure on purpose: the movement is the part worth testing, and it is
 * testable without a DOM, a component or a signal.</p>
 *
 * <p>Home and End are here because both existing grids have them — first and
 * last of the axis being walked. Nothing else is invented: no page up, no type
 * ahead.</p>
 */
export function nextRowIndex(key: string, current: number, total: number): number | null {
  if (total === 0) {
    return null;
  }
  const last = total - 1;
  switch (key) {
    case 'ArrowDown':
      return Math.min(current + 1, last);
    case 'ArrowUp':
      return Math.max(current - 1, 0);
    case 'Home':
      return 0;
    case 'End':
      return last;
    default:
      return null;
  }
}

/**
 * What the navigation needs of the CDK `LiveAnnouncer`, and nothing more: a
 * row of a `role="table"` cannot carry `aria-selected` — that property is only
 * supported on a row inside a `grid` or a `treegrid` — so the state change
 * Space produces is spoken instead of being written on the row.
 */
export interface RowAnnouncer {
  announce(message: string): unknown;
}

export interface TableNavigationOptions<TRow, TId extends SelectionId> {
  /** Rows currently displayed, in display order — the signal the selection is keyed on. */
  readonly rows: Signal<readonly TRow[]>;
  /** Natural key of a row: what the focus is anchored to across a sort or a filter. */
  readonly id: (row: TRow) => TId;
  /** The page's own element, so two pages never steal each other's focus. */
  readonly host: () => HTMLElement;
  /** Space: the selection the focused row is toggled in. */
  readonly selection?: TableSelection<TId>;
  /**
   * Enter: opens the focused row — its detail dialog on the pages that have
   * one. Returns whether something was actually opened: a page that refuses
   * the row (the créneaux, while a solve locks the edition) returns `false`,
   * and the key is then left unconsumed rather than dying in silence.
   */
  readonly open?: (row: TRow) => boolean;
  /** Speaks the new selection state of a row, since the row cannot carry it. */
  readonly announcer?: RowAnnouncer;
}

/**
 * Where the focus sits: the row it was put on, and the rank that row held at
 * that moment — the fallback of last resort, used only when the row vanishes
 * before the table has been drawn once since the focus reached it.
 */
interface FocusAnchor<TId> {
  readonly id: TId;
  readonly index: number;
}

/** The two inputs the current row is derived from, read as one. */
interface RowsAndAnchor<TRow, TId> {
  readonly rows: readonly TRow[];
  readonly anchor: FocusAnchor<TId> | null;
}

export class TableNavigation<TRow, TId extends SelectionId> {
  /**
   * The user's intent, which is a *row* and not a rank: anchoring on the id is
   * what makes a re-sort keep the focus on the same line instead of on
   * whatever slid into its place.
   */
  private readonly anchor = signal<FocusAnchor<TId> | null>(null);

  /**
   * Row carrying `tabindex="0"`, or `-1` when the table is empty.
   *
   * <p>Derived rather than corrected by an effect, for the reason the heatmap
   * learned the hard way: filtering out the anchored row used to leave no row
   * with `tabindex="0"` at all, so the table dropped out of the tab order
   * until the filter was cleared — the one way in, gone. Clamping on read
   * cannot go stale behind the table.</p>
   *
   * <p>Anchored row still displayed → its current rank. Gone (filtered out,
   * deleted) → the rank it held <em>last time it was displayed</em>, clamped,
   * so the focus lands on the row that took its place rather than jumping back
   * to the top. That last rank is this signal's own previous value, which is
   * why it is a `linkedSignal` and not a `computed`: a sort moves the anchored
   * row, the table redraws, and the fallback follows. Keeping only the rank
   * recorded when the focus arrived would send the focus back to a position
   * the row left long ago.</p>
   */
  readonly index: Signal<number>;

  constructor(private readonly options: TableNavigationOptions<TRow, TId>) {
    this.index = linkedSignal<RowsAndAnchor<TRow, TId>, number>({
      source: () => ({ rows: this.options.rows(), anchor: this.anchor() }),
      computation: ({ rows, anchor }, previous) => {
        if (rows.length === 0) {
          return -1;
        }
        if (!anchor) {
          return 0;
        }
        const rank = rows.findIndex((row) => this.options.id(row) === anchor.id);
        if (rank >= 0) {
          return rank;
        }
        // The anchored row is no longer displayed. The previous value is its
        // rank only if it was produced for *this* anchor — a fresh anchor
        // object is created on every focus, so identity is the test — and the
        // rank recorded at focus time is what remains otherwise.
        const lastDisplayed = previous?.source.anchor === anchor ? previous.value : anchor.index;
        return Math.min(Math.max(lastDisplayed, 0), rows.length - 1);
      },
    });
  }

  /** Whether the row at `index` is the one the table offers to Tab. */
  isCurrent(index: number): boolean {
    return this.index() === index;
  }

  /**
   * Moves the focus onto the row the table currently offers, and says whether
   * there was one. This is how the *outside* of the table gets in — today the
   * quick filter's arrow down, which is the only entry a user finds without
   * counting tab stops. A roving tabindex is invisible: the row that carries
   * `tabindex="0"` sits behind the "tout sélectionner" checkbox and one stop
   * per sortable column, so the number of Tab presses to reach it changes
   * every time a header gains a sort.
   */
  focusCurrent(): boolean {
    const index = this.index();
    if (index < 0) {
      return false;
    }
    this.moveTo(index);
    return true;
  }

  /**
   * Records the row the focus just reached — from a Tab, a click, or our own
   * move. Keeps the anchor and the browser in step whichever of the two led.
   */
  onFocus(index: number): void {
    this.anchorOn(index);
  }

  /**
   * Arrows, Home/End, Enter and Space on a focused row.
   *
   * <p>Returns early on anything else, without `preventDefault()`, so the
   * event keeps travelling: a `/` typed from a row still jumps to the page
   * filter, and Ctrl+K still opens the palette.</p>
   */
  onKeydown(event: KeyboardEvent, index: number): void {
    // The row itself, never one of its controls: a `keydown` bubbles, and
    // Space on the selection checkbox or Enter on "Supprimer" must reach them.
    if (event.target !== event.currentTarget) {
      return;
    }
    // A modifier turns these into someone else's shortcut (Ctrl+K, and every
    // browser binding on Ctrl+Home) — never into a move.
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    const row = this.options.rows()[index];
    if (row === undefined) {
      return;
    }
    switch (event.key) {
      case 'Enter':
        this.anchorOn(index);
        // Consumed only once something has actually been opened. A table wired
        // without `open`, or a page that refuses the row while a solve locks
        // the edition, must leave the key alone: swallowing it would make the
        // shortcut announced in the help silently do nothing.
        if (this.options.open?.(row) === true) {
          event.preventDefault();
        }
        return;
      case ' ': {
        const selection = this.options.selection;
        // Space scrolls the page when nobody claims it, so a table without a
        // selection keeps it: it would lose the scrolling and gain nothing.
        if (!selection) {
          return;
        }
        event.preventDefault();
        this.anchorOn(index);
        const id = this.options.id(row);
        selection.toggle(id);
        this.options.announcer?.announce(this.selectionMessage(selection.isSelected(id)));
        return;
      }
      default:
        break;
    }
    const target = nextRowIndex(event.key, index, this.options.rows().length);
    if (target === null) {
      return;
    }
    event.preventDefault();
    this.moveTo(target);
  }

  /** Moves the browser focus, which then calls {@link onFocus} back. */
  private moveTo(index: number): void {
    this.anchorOn(index);
    this.options.host().querySelector<HTMLElement>(`[${ROW_INDEX_ATTRIBUTE}="${index}"]`)?.focus();
  }

  /**
   * What Space just did to the row, for the live region. Built in a method and
   * never at module scope: `$localize` only resolves once `main.ts` has loaded
   * the translations.
   */
  private selectionMessage(selected: boolean): string {
    return selected
      ? $localize`:@@bulk.rowChecked:Ligne cochée.`
      : $localize`:@@bulk.rowUnchecked:Ligne décochée.`;
  }

  private anchorOn(index: number): void {
    const row = this.options.rows()[index];
    if (row === undefined) {
      return;
    }
    this.anchor.set({ id: this.options.id(row), index });
  }
}
