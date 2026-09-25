import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { familyLabel } from '../core/gel-referentiel-labels';
import { injectGelReferentiel } from '../core/gel-referentiel.store';
import { intlLocale } from '../core/locale';

/**
 * What a freeze of the referential (ADR 0052) does to an operation that
 * rewrites the whole edition rather than one family: emptying it, or importing
 * a scenario over it. The server refuses both while any family is frozen;
 * this names the frozen families before the click, with the way to the
 * switches that lift them. Nothing while every family is open.
 *
 * - `reset`: the host disables its button — the notice says why.
 * - `scenario`: a warning only, the host's button stays enabled — the file
 *   may name another edition, which the freeze of this one does not cover.
 */
@Component({
  selector: 'app-gel-edition-notice',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    @if (store.anyFrozen()) {
      <p class="gel-edition-notice" role="note" [attr.data-operation]="operation()">
        <mat-icon aria-hidden="true">lock</mat-icon>
        <span class="gel-edition-notice-texte">{{ message() }}</span>
        <a matButton class="gel-edition-notice-lever" routerLink="/parametres"
           [queryParams]="{ onglet: 'edition' }" fragment="gel-referentiel"
           i18n="@@gel.notice.lever">Lever le gel</a>
      </p>
    }
  `,
  styles: `
    .gel-edition-notice {
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
    .gel-edition-notice mat-icon {
      flex-shrink: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GelEditionNotice {
  readonly operation = input.required<'reset' | 'scenario'>();

  protected readonly store = injectGelReferentiel();

  /** « Stands » et « Créneaux », in the reader's language. */
  private readonly families = computed(() =>
    new Intl.ListFormat(intlLocale(), { type: 'conjunction' }).format(
      this.store.frozenFamilies().map((family) => {
        const label = familyLabel(family);
        return $localize`:@@gel.edition.famille:« ${label}:famille: »`;
      }),
    ),
  );

  protected readonly message = computed(() => {
    const names = this.families();
    return this.operation() === 'reset'
      ? $localize`:@@gel.edition.reset:Référentiel figé dans cette édition (${names}:familles:) : vider la base est impossible jusqu'à la levée du gel.`
      : $localize`:@@gel.edition.scenario:Référentiel figé dans cette édition (${names}:familles:) : un scénario importé dans celle-ci sera refusé jusqu'à la levée du gel. Un fichier qui vise une autre édition reste importable.`;
  });
}
