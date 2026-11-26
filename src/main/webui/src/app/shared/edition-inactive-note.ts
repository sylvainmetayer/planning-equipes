import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { editionInactivePhrase } from '../core/edition-inactive';
import { EditionStore } from '../core/edition.store';
import { StatusMessage } from './status-message';

/**
 * Why the actions that write to people are greyed out on an edition that is not
 * the active one (ADR 0072), and where to activate it. Visible text rather
 * than a tooltip: a disabled button is skipped by the keyboard and a tooltip
 * is never read to a screen reader. Renders nothing on the active edition.
 */
@Component({
  selector: 'app-edition-inactive-note',
  imports: [RouterLink, StatusMessage],
  template: `
    @if (editions.inactive()) {
      <app-status-message tone="warning" [text]="phrase()" />
      <a routerLink="/editions" i18n="@@edition.inactive.lien">Ouvrir la page Éditions</a>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EditionInactiveNote {
  protected readonly editions = inject(EditionStore);
  protected readonly phrase = computed(() => editionInactivePhrase());
}
