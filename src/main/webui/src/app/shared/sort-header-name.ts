import { AfterViewInit, Directive, ElementRef, inject, input } from '@angular/core';

/** Fallback ids, for a title element that carries none of its own. */
let sequence = 0;

/**
 * Names a `mat-sort-header` after its column title, and after nothing else.
 *
 * Material renders the whole header cell inside a generated `role="button"`
 * that it never names: the browser computes the name from the content, and
 * that computation walks every descendant — the `aria-label` of a nested
 * control included. A help button sitting in the header therefore lends its
 * entire explanation to the sort control, and a screen reader reads those
 * lines out every single time the focus reaches the header. Material's own
 * template warns about it ("the button's aria-label will be read out as the
 * user is navigating the table's cell").
 *
 * Pointing that wrapper at the title element with `aria-labelledby` stops the
 * computation from descending: the header announces its column, the nested
 * control keeps its full name for when the focus reaches *it*. The name is
 * taken from the displayed element rather than copied, so it cannot drift from
 * what is shown, and it needs no translation of its own.
 *
 * Only needed on a header holding more than its title — a plain-text header
 * already names itself correctly. `sortActionDescription` is not an
 * alternative: it feeds `aria-describedby`, which describes the action and
 * leaves the name untouched.
 */
@Directive({ selector: '[appSortHeaderName]' })
export class SortHeaderName implements AfterViewInit {
  /** The element carrying the column title, usually the header's own label. */
  readonly label = input.required<HTMLElement>({ alias: 'appSortHeaderName' });

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  ngAfterViewInit(): void {
    // The wrapper belongs to MatSortHeader's own view, which is rendered by
    // the time the host view is initialised — the very assumption MatSortHeader
    // makes when it looks the same element up for `sortActionDescription`.
    const sortButton = this.host.nativeElement.querySelector('.mat-sort-header-container');
    if (!sortButton) {
      return;
    }
    const label = this.label();
    if (!label.id) {
      sequence += 1;
      label.id = `sort-header-label-${sequence}`;
    }
    sortButton.setAttribute('aria-labelledby', label.id);
  }
}
