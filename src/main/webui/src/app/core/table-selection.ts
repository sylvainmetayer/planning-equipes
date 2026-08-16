// Row selection shared by the reference-data pages: which rows are ticked, and
// the header-checkbox states derived from them.
//
// The selection is always intersected with the rows currently displayed, so a
// row that disappears — deleted, or filtered out — leaves the selection on its
// own and no page has to clean up after itself.

import { Signal, computed, signal } from '@angular/core';

/** Natural key of a reference-data row: a typed id (`Creneau.id` is a number). */
export type SelectionId = string | number;

export class TableSelection<T extends SelectionId> {
  /** Ticked ids, displayed or not; {@link selectedIds} narrows them to the visible ones. */
  private readonly ticked = signal<ReadonlySet<T>>(new Set<T>());

  /** Selected ids, in display order. */
  readonly selectedIds: Signal<T[]>;
  readonly count: Signal<number>;
  readonly hasSelection: Signal<boolean>;
  /** True when every displayed row is selected — false when nothing is displayed. */
  readonly allSelected: Signal<boolean>;
  /** Some but not all displayed rows selected: the header checkbox's indeterminate state. */
  readonly partiallySelected: Signal<boolean>;

  constructor(private readonly visibleIds: Signal<readonly T[]>) {
    this.selectedIds = computed(() => {
      const ticked = this.ticked();
      return ticked.size === 0 ? [] : this.visibleIds().filter((id) => ticked.has(id));
    });
    this.count = computed(() => this.selectedIds().length);
    this.hasSelection = computed(() => this.count() > 0);
    this.allSelected = computed(() => {
      const visible = this.visibleIds();
      return visible.length > 0 && this.count() === visible.length;
    });
    this.partiallySelected = computed(() => this.hasSelection() && !this.allSelected());
  }

  isSelected(id: T): boolean {
    return this.ticked().has(id);
  }

  toggle(id: T): void {
    this.set(id, !this.isSelected(id));
  }

  set(id: T, selected: boolean): void {
    this.ticked.update((ticked) => {
      const next = new Set(ticked);
      if (selected) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  /** Selects every displayed row, or clears the selection when they already all are. */
  toggleAll(): void {
    if (this.allSelected()) {
      this.clear();
      return;
    }
    this.ticked.set(new Set(this.visibleIds()));
  }

  clear(): void {
    this.ticked.set(new Set<T>());
  }
}
