// Keeps the keyboard focus somewhere sensible after a repeatable row is
// removed.
//
// Deleting a row destroys the button that was focused. The browser then falls
// back to <body>: a keyboard user is thrown to the top of the document, and a
// screen reader goes silent. Every form here that repeats a row (stand
// horaires and their windows, dated exceptions, ad hoc constraints) has the
// problem, so the recovery lives in one place.

import { Injector, afterNextRender } from '@angular/core';

/**
 * Focuses the first element matching `selecteur` inside `hote`, once the DOM
 * has been re-rendered without the removed row.
 *
 * @param hote      the component's own element, so two open dialogs never
 *                  steal each other's focus
 * @param selecteur what to focus instead — typically the "add a row" button of
 *                  the same section, which is the natural next action
 */
export function focusApresSuppression(hote: HTMLElement, selecteur: string, injector: Injector): void {
  afterNextRender(
    () => {
      const target = hote.querySelector<HTMLElement>(selecteur);
      target?.focus();
    },
    { injector }
  );
}
