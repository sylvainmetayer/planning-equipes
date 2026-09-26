// Specs of the referential screens: an empty one offers « Ajouter » and
// « Importer » in its empty state and nowhere else — two buttons of the same
// name on one screen leave a reader guessing which is which.

import { expect } from 'vitest';

/** Each of `labels` names exactly one button of `root`, and that button sits in the empty state. */
export function expectOnlyInEmptyState(root: Element, labels: readonly string[]): void {
  for (const label of labels) {
    const buttons = Array.from(root.querySelectorAll('button')).filter((button) =>
      button.textContent?.trim().endsWith(label),
    );
    expect(buttons, `boutons « ${label} »`).toHaveLength(1);
    expect(buttons[0].closest('.empty-state'), `« ${label} » dans l'état vide`).not.toBeNull();
  }
}
