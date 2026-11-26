import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { EditionStore } from '../core/edition.store';
import { familyLabel, frozenOn } from '../core/gel-referentiel-labels';
import { injectGelReferentiel } from '../core/gel-referentiel.store';
import { intlLocale } from '../core/locale';
import { FreezeFamily } from '../core/models';
import { phaseLabel } from '../core/phase-edition';
import { SessionStore } from '../core/session.store';
import { PhaseEditionActions } from './phase-edition-actions';

/**
 * The padlock a form or a page shows over the fields the phase of the edition
 * freezes (ADR 0052, 0115): which family, since when, in which phase, and the
 * way to reopen it — « Débloquer la structure » in a published edition, the
 * phase control otherwise. Nothing while the family is open, and no way out
 * for a lecteur. The fields themselves are made read-only by the host, which
 * reads `GelReferentielStore.isFrozen` — this only says why, once, where the
 * reader looks.
 */
@Component({
  selector: 'app-gel-notice',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (frozen(); as gel) {
      <p class="gel-notice" role="note" [attr.data-famille]="family()">
        <mat-icon aria-hidden="true">lock</mat-icon>
        <span class="gel-notice-texte" i18n="@@gel.notice">« {{ label() }} » figé le {{ date() }} : l'édition est en phase {{ phase() }}</span>
        @if (!lectureSeule()) {
          @if (unblockable()) {
            <button matButton type="button" class="gel-notice-lever" [disabled]="actions.busy()" (click)="unblock()"
                    i18n="@@phase.debloquer.geste">Débloquer la structure</button>
          } @else {
            <button matButton type="button" class="gel-notice-lever" (click)="openPhase()"
                    i18n="@@gel.notice.phase">Changer la phase</button>
          }
        }
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
  protected readonly actions = inject(PhaseEditionActions);
  private readonly editions = inject(EditionStore);
  private readonly router = inject(Router);
  protected readonly lectureSeule = inject(SessionStore).lectureSeule;

  protected readonly phase = computed(() => {
    const phase = this.editions.phase();
    return phase ? phaseLabel(phase) : '';
  });
  /** A family frozen in a published edition is a structure one: unblocking it is one click away. */
  protected readonly unblockable = computed(() => this.editions.phase() === 'PUBLIEE');

  protected readonly frozen = computed(() => this.store.frozen(this.family()));
  protected readonly label = computed(() => familyLabel(this.family()));
  protected readonly date = computed(() => frozenOn(this.frozen()?.figeLe ?? null, intlLocale()));

  protected openPhase(): void {
    void this.router.navigate(['/editions'], { fragment: 'phase-edition' });
  }

  protected unblock(): void {
    void this.actions.unblockStructure();
  }
}
