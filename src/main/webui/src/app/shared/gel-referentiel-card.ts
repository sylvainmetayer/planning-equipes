import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { familyLabel, familyScope, frozenOn } from '../core/gel-referentiel-labels';
import { FREEZE_FAMILIES, GelReferentielStore } from '../core/gel-referentiel.store';
import { intlLocale } from '../core/locale';
import { FreezeFamily } from '../core/models';
import { GelReferentielActions } from './gel-referentiel-actions';

/**
 * One switch per family of the referential freeze (ADR 0052): what it covers,
 * whether it is frozen and since when. The home screen unfolds it under its
 * « Gel du référentiel » line, Paramètres shows it on the edition's tab —
 * the same component, so the two cannot disagree.
 *
 * Freezing is immediate; lifting asks first (see {@link GelReferentielActions}),
 * and a switch whose lift was cancelled goes back where it was.
 */
@Component({
  selector: 'app-gel-referentiel-card',
  imports: [MatSlideToggleModule],
  template: `
    <ul class="gel-card-liste" [attr.aria-label]="ariaLabel()">
      @for (ligne of lignes(); track ligne.family) {
        <li class="gel-card-ligne" [attr.data-famille]="ligne.family">
          <mat-slide-toggle [checked]="ligne.frozen" [disabled]="disabled() || store.busy()"
                            (change)="toggle(ligne.family, $event)">
            {{ ligne.label }}
          </mat-slide-toggle>
          <span class="gel-card-perimetre">{{ ligne.scope }}</span>
          @if (ligne.frozen) {
            <span class="gel-card-date" i18n="@@gel.card.figeLe">Figé le {{ ligne.date }}</span>
          }
        </li>
      }
    </ul>
    @if (store.error()) {
      <p class="gel-card-erreur" role="alert">{{ store.error() }}</p>
    }
  `,
  styles: `
    .gel-card-liste {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 0.75rem;
    }
    .gel-card-ligne {
      display: grid;
      gap: 0.125rem;
    }
    .gel-card-perimetre,
    .gel-card-date {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
      padding-inline-start: 3.25rem;
    }
    .gel-card-erreur {
      color: var(--mat-sys-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GelReferentielCard {
  /** Mirrors the page's own lock, if it has one; the server stays the judge. */
  readonly disabled = input(false);
  /** A family was really frozen or lifted — not on a cancelled or refused switch — for a host summarising it. */
  readonly changed = output<FreezeFamily>();

  protected readonly store = inject(GelReferentielStore);
  private readonly actions = inject(GelReferentielActions);

  protected readonly ariaLabel = computed(
    () => $localize`:@@gel.card.aria:Familles du référentiel à figer`,
  );

  protected readonly lignes = computed(() =>
    FREEZE_FAMILIES.map((family) => {
      const gel = this.store.frozen(family);
      return {
        family,
        label: familyLabel(family),
        scope: familyScope(family),
        frozen: gel !== null,
        date: frozenOn(gel?.figeLe ?? null, intlLocale()),
      };
    }),
  );

  constructor() {
    void this.store.reload();
  }

  protected async toggle(family: FreezeFamily, event: MatSlideToggleChange): Promise<void> {
    if (event.checked) {
      await this.actions.freeze(family);
    } else {
      await this.actions.lift(family);
    }
    // Cancelled or refused: the switch says what the server holds, not the click.
    event.source.checked = this.store.isFrozen(family);
    if (this.store.isFrozen(family) === event.checked) {
      this.changed.emit(family);
    }
  }
}
