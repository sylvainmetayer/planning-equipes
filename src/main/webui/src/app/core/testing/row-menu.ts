// Specs of the referential tables: a row's actions live in its « ⋯ » menu
// (`shared/row-menu.ts`), rendered in the overlay only once opened. These open
// it and hand back the item a test asked for.

import { ComponentFixture } from '@angular/core/testing';
import { expect } from 'vitest';

/** Opens the « ⋯ » of `row` and returns its item whose text contains `label`. */
export async function rowMenuItem(
  fixture: ComponentFixture<unknown>,
  row: Element,
  label: string,
): Promise<HTMLButtonElement> {
  const trigger = row.querySelector<HTMLButtonElement>('.row-menu-trigger');
  expect(trigger, 'menu ⋯ de la ligne').not.toBeNull();
  trigger!.click();
  await fixture.whenStable();
  // The last panel: a menu opened earlier in the same test may still be there.
  const panel = Array.from(document.querySelectorAll('.mat-mdc-menu-panel')).at(-1);
  const item = Array.from(
    panel?.querySelectorAll<HTMLButtonElement>('.mat-mdc-menu-item') ?? [],
  ).find((each) => each.textContent?.includes(label));
  expect(item, `entrée « ${label} » du menu`).toBeDefined();
  return item!;
}
