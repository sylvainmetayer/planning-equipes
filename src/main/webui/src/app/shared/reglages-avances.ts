import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  contentChild,
  input,
  TemplateRef,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ReglagesAvances } from '../core/reglages-avances';

/**
 * The « Réglages avancés » button of a screen, and what it unfolds: the
 * `<ng-template>` given as content, created only once opened. Without one,
 * the button alone — a page then shows what the state opens itself (tabs).
 * `utile` is the page saying there is something behind the button on the tab
 * on screen: required, so a new screen has to answer, and a button that would
 * open nothing is not drawn (`scripts/check-reglages-avances.js` holds every use to it).
 */
@Component({
  selector: 'app-reglages-avances',
  imports: [MatButtonModule, MatIconModule, NgTemplateOutlet],
  template: `
    @if (utile()) {
    <button
      matButton
      type="button"
      [attr.aria-expanded]="etat().ouvert()"
      (click)="etat().basculer()"
    >
      <mat-icon aria-hidden="true">{{ etat().ouvert() ? 'expand_less' : 'tune' }}</mat-icon>
      <ng-container i18n="@@reglagesAvances.bouton">Réglages avancés</ng-container>
    </button>
    @if (etat().ouvert() && contenu(); as gabarit) {
      <div class="reglages-avances-contenu">
        <ng-container [ngTemplateOutlet]="gabarit" />
      </div>
    }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglagesAvancesToggle {
  readonly etat = input.required<ReglagesAvances>();
  readonly utile = input.required<boolean>();
  protected readonly contenu = contentChild(TemplateRef);
}
