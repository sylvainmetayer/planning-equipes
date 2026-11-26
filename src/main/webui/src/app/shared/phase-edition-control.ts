import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { EditionStore } from '../core/edition.store';
import { intlLocale } from '../core/locale';
import { EditionPhase } from '../core/models';
import { EDITION_PHASES, phaseEffect, phaseLabel, phaseSince } from '../core/phase-edition';
import { SessionStore } from '../core/session.store';
import { PhaseEditionActions } from './phase-edition-actions';

/**
 * The phase of the edition (ADR 0115) and the gestures that move it: the four
 * phases in the order of the cycle, the current one marked, what it freezes
 * said under them, and — in `PUBLIEE` — « Débloquer la structure » and
 * « Refiger ». The home screen's checklist and the Éditions page unfold the
 * same component, so the two cannot disagree. A lecteur sees the phase and
 * has no button (the server refuses the gestures anyway).
 *
 * Every gesture asks first, saying what the new phase freezes or opens
 * (see {@link PhaseEditionActions}).
 */
@Component({
  selector: 'app-phase-edition-control',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (phase(); as courante) {
      <section class="phase-edition" id="phase-edition" [attr.data-phase]="courante">
        <ol class="phase-edition-etapes" aria-label="Phases de l'édition" i18n-aria-label="@@phase.aria">
          @for (etape of phases; track etape) {
            <li class="phase-edition-etape" [attr.data-phase]="etape"
                [class.phase-edition-courante]="etape === courante"
                [attr.aria-current]="etape === courante ? 'step' : null">
              <span class="phase-edition-nom">{{ label(etape) }}</span>
              @if (etape === courante) {
                <span class="phase-edition-depuis">{{ depuis() }}</span>
              } @else if (!lectureSeule()) {
                <button matButton type="button" [disabled]="actions.busy()" (click)="change(etape)">
                  <ng-container i18n="@@phase.passer">Passer en {{ label(etape) }}</ng-container>
                </button>
              }
            </li>
          }
        </ol>
        <p class="phase-edition-effet">{{ effet() }}</p>
        @if (courante === 'PUBLIEE') {
          @if (debloquee()) {
            <p class="phase-edition-debloquee" role="status">
              <mat-icon aria-hidden="true">lock_open</mat-icon>
              <ng-container i18n="@@phase.debloquee">Structure débloquée : stands, créneaux, typologies et emplacements se modifient jusqu'à la prochaine publication.</ng-container>
            </p>
          }
          @if (!lectureSeule()) {
            <div class="phase-edition-gestes">
              @if (debloquee()) {
                <button matButton="outlined" type="button" [disabled]="actions.busy()" (click)="refreeze()"
                        i18n="@@phase.refiger.geste">Refiger</button>
              } @else {
                <button matButton="outlined" type="button" [disabled]="actions.busy()" (click)="unblock()"
                        i18n="@@phase.debloquer.geste">Débloquer la structure</button>
              }
            </div>
          }
        }
      </section>
    }
  `,
  styles: `
    .phase-edition-etapes {
      list-style: none;
      margin: 0 0 0.5rem;
      padding: 0;
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .phase-edition-etape {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.125rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: var(--mat-sys-corner-medium);
    }
    .phase-edition-courante {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      border-color: transparent;
      font-weight: 500;
    }
    .phase-edition-depuis,
    .phase-edition-effet {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }
    .phase-edition-courante .phase-edition-depuis {
      color: inherit;
    }
    .phase-edition-effet {
      margin: 0 0 0.5rem;
      font: var(--mat-sys-body-medium);
    }
    .phase-edition-debloquee {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0 0 0.5rem;
      color: var(--mat-sys-on-tertiary-container);
      background: var(--mat-sys-tertiary-container);
      padding: 0.25rem 0.5rem;
      border-radius: var(--mat-sys-corner-small);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhaseEditionControl {
  protected readonly phases = EDITION_PHASES;
  protected readonly actions = inject(PhaseEditionActions);
  private readonly editions = inject(EditionStore);

  protected readonly phase = this.editions.phase;
  protected readonly debloquee = this.editions.structureDebloquee;
  protected readonly lectureSeule = inject(SessionStore).lectureSeule;

  protected readonly effet = computed(() => {
    const phase = this.phase();
    return phase ? phaseEffect(phase) : '';
  });

  protected readonly depuis = computed(() => {
    const date = phaseSince(this.editions.courant()?.phaseDepuis, intlLocale());
    return date ? $localize`:@@phase.depuis:depuis le ${date}:date:` : '';
  });

  protected label(phase: EditionPhase): string {
    return phaseLabel(phase);
  }

  protected change(phase: EditionPhase): void {
    void this.actions.changePhase(phase);
  }

  protected unblock(): void {
    void this.actions.unblockStructure();
  }

  protected refreeze(): void {
    void this.actions.refreezeStructure();
  }
}
