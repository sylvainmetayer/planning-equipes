import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  resource,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { RealiseApi } from '../../core/api/realise-api';
import { heures, pourcentage } from '../realise/mesure';

/**
 * « Réalisé de l'édition précédente » on the Versions page: the measure the
 * nightly job froze at the end of the previous event, read from
 * `GET /api/planning/realise/precedente` — the edition whose event ended last
 * before this one's first day, even when it has been deleted since. Nothing
 * at all while there is none: an edition without a predecessor has nothing to
 * read here.
 */
@Component({
  selector: 'app-edition-precedente',
  imports: [RouterLink],
  template: `
    @if (precedente(); as precedente) {
      <section class="edition-precedente" aria-labelledby="edition-precedente-titre">
        <h2 id="edition-precedente-titre" class="edition-precedente-titre"
            i18n="@@realise.precedente.titre">Réalisé de l'édition précédente</h2>
        <p class="edition-precedente-ligne">
          <strong>{{ precedente.editionNom ?? precedente.editionId }}</strong>
          ({{ precedente.firstDay }} → {{ precedente.lastDay }}) :
          @if (precedente.event; as evenement) {
            <ng-container i18n="@@realise.precedente.resume">{{ evenement.publishedSeats }} sièges publiés, {{ pourcentage(evenement.absenceRate) }} d'absence, {{ pourcentage(evenement.replacementRate) }} de remplacement, {{ heures(evenement.lostMinutes) }} perdues.</ng-container>
          }
          <a routerLink="/diagnostic" [queryParams]="{ onglet: 'besoin' }"
             i18n="@@realise.precedente.versBesoin">Par typologie, sur le Besoin</a>
        </p>
      </section>
    }
  `,
  styleUrl: './edition-precedente.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EditionPrecedente {
  private readonly api = inject(RealiseApi);

  private readonly mesure = resource({ loader: () => this.api.previousEdition() });
  /** The measure when there is one; a failed read shows nothing rather than an error under the page. */
  protected readonly precedente = computed(() => {
    const mesure = this.mesure.status() === 'error' ? null : this.mesure.value();
    return mesure?.available ? mesure : null;
  });

  protected readonly heures = heures;
  protected readonly pourcentage = pourcentage;
}
