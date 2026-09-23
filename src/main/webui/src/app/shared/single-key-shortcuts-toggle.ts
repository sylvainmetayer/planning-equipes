// The switch of the single-key shortcuts (WCAG 2.1.4), in the two places it
// is looked for: the `?` dialog, where the shortcuts are learnt, and
// Paramètres, where settings are.

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { SingleKeyShortcutsService } from '../core/single-key-shortcuts';

@Component({
  selector: 'app-single-key-shortcuts-toggle',
  imports: [MatCheckboxModule],
  template: `
    <mat-checkbox
      [checked]="!preference.enabled()"
      (change)="preference.set(!$event.checked)"
      i18n="@@shortcuts.singleKey.disable"
      >Désactiver les raccourcis à une touche (g, /, ?) sur ce navigateur</mat-checkbox
    >
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SingleKeyShortcutsToggle {
  protected readonly preference = inject(SingleKeyShortcutsService);
}
