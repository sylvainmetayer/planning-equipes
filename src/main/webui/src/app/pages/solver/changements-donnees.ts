import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { intlLocale } from '../../core/locale';
import { EntreeHistorique } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { surQuoi } from '../historique/historique';
import { resumeLisible } from './changements';
import { StatusMessage } from '../../shared/status-message';

/**
 * What moved since the solve whose result the screen shows — the summary under
 * « des données de référence ont été modifiées depuis cette résolution ».
 *
 * <p>Read from the history (`GET /api/historique/changements`), which is the
 * only place that knows <em>what</em> happened: the staleness hint itself is a
 * single instant, and an instant cannot say whether somebody added a stand or
 * deleted twenty créneaux. Only the actions that change what a solve would be
 * given are counted, so a mail sent since does not show up here.</p>
 *
 * <p>Shown by « Corriger après un changement » before the perimeter is chosen
 * (issue #719), and loaded only then: the component is created by the dialog.
 * Nothing changed is said too — the correction then only fills the holes.</p>
 */
@Component({
  selector: 'app-changements-donnees',
  imports: [StatusMessage, RouterLink],
  template: `
    <app-status-message [text]="erreur()" tone="error" />
    @if (vue(); as bilan) {
      @if (bilan.total > 0) {
        <p class="calendar-meta data-stale-detail">
          <span i18n="@@solver.changements.resume">Depuis : {{ resume() }}.</span>
          <a routerLink="/historique" i18n="@@solver.changements.lien">Voir l'historique</a>
        </p>
        <ul class="data-stale-liste">
          @for (entree of bilan.dernieres; track entree.id) {
            <li>
              <span class="data-stale-heure">{{ heure(entree.survenuLe) }}</span>
              <span>{{ entree.libelle }}</span>
              @if (cible(entree); as quoi) {
                <span class="data-stale-cible">{{ quoi }}</span>
              }
            </li>
          }
          @if (bilan.total > bilan.dernieres.length) {
            <li class="data-stale-reste" i18n="@@solver.changements.reste">
              … et {{ bilan.total - bilan.dernieres.length }} autre(s) changement(s).
            </li>
          }
        </ul>
      } @else {
        <p class="calendar-meta" i18n="@@solver.changements.aucun">
          Aucune donnée modifiée depuis ce plan : seuls les postes vides seront recalculés.
        </p>
      }
    }
  `,
  styles: `
    .data-stale-detail a {
      margin-left: 0.5rem;
    }

    .data-stale-liste {
      margin: 0.25rem 0 0;
      padding-left: 1.25rem;
      font-size: 0.9em;
      color: var(--mat-sys-on-surface-variant);
    }

    .data-stale-liste li {
      list-style: none;
    }

    .data-stale-heure {
      display: inline-block;
      min-width: 3.5rem;
      font-variant-numeric: tabular-nums;
    }

    .data-stale-cible {
      margin-left: 0.5rem;
      font-style: italic;
    }

    .data-stale-reste {
      font-style: italic;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChangementsDonneesPanel {
  private readonly analysesApi = inject(AnalysesApi);

  /** When the displayed plan was solved; everything after it is what changed. */
  readonly depuis = input.required<string>();

  private readonly changements = resource({
    params: () => ({ depuis: this.depuis() }),
    loader: ({ params }) => this.analysesApi.changesSince(params.depuis),
  });
  /** `value()` throws once the load has failed, so the summary is read through the helper. */
  protected readonly vue = retainedValue(this.changements);
  /**
   * Said rather than swallowed: the warning above stands on its own, but a
   * detail that silently never arrives looks like « rien n'a changé ».
   */
  protected readonly erreur = errorText(this.changements);

  /** « 3 animateurs, 1 stand, 2 créneaux » — the verbs are on the lines below. */
  protected readonly resume = computed(() => resumeLisible(this.vue()?.parEntite ?? []));

  /** « 14:32 » — the day is rarely another one, and the history screen has the full story. */
  protected heure(iso: string): string {
    return new Date(iso).toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' });
  }

  protected cible(entree: EntreeHistorique): string {
    return surQuoi(entree);
  }
}
