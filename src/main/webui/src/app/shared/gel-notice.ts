import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { familyLabel, frozenOn } from '../core/gel-referentiel-labels';
import { injectGelReferentiel } from '../core/gel-referentiel.store';
import { intlLocale } from '../core/locale';
import { FreezeFamily } from '../core/models';
import { GelReferentielActions } from './gel-referentiel-actions';

/**
 * The padlock a form or a page shows over the fields a freeze of the
 * referential covers (ADR 0052): which family, since when, and the way to
 * lift it. Nothing while the family is open. The fields themselves are made
 * read-only by the host, which reads `GelReferentielStore.isFrozen` —
 * this only says why, once, where the reader looks.
 */
@Component({
  selector: 'app-gel-notice',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (frozen(); as gel) {
      <p class="gel-notice" role="note" [attr.data-famille]="family()">
        <mat-icon aria-hidden="true">lock</mat-icon>
        <span class="gel-notice-texte" i18n="@@gel.notice">« {{ label() }} » figé le {{ date() }} par l'organisation</span>
        <button matButton type="button" class="gel-notice-lever" [disabled]="store.busy()" (click)="lift()"
                i18n="@@gel.notice.lever">Lever le gel</button>
      </p>
    }
  `,
  styles: `
    .gel-notice {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem 0.5rem;
      margin: 0 0 0.75rem;
      padding: 0.25rem 0.5rem;
      border-radius: var(--mat-sys-corner-small);
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-medium);
    }
    .gel-notice mat-icon {
      flex-shrink: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GelNotice {
  readonly family = input.required<FreezeFamily>();

  protected readonly store = injectGelReferentiel();
  private readonly actions = inject(GelReferentielActions);

  protected readonly frozen = computed(() => this.store.frozen(this.family()));
  protected readonly label = computed(() => familyLabel(this.family()));
  protected readonly date = computed(() => frozenOn(this.frozen()?.figeLe ?? null, intlLocale()));

  protected lift(): void {
    void this.actions.lift(this.family());
  }
}
