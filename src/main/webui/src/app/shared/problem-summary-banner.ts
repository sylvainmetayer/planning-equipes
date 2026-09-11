import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { compterProblemes, Probleme } from '../core/problemes';

/**
 * Compact aggregate of everything wrong with the current dataset and the last
 * analysed solve — how many problems, split by severity — plus a way in to the
 * Diagnostic page, whose first tab lists them all.
 *
 * Silent unless at least one problem is blocking, like `app-feasibility-banner`:
 * warnings and minor issues alone are not worth interrupting the nominal path
 * for, and are still visible on the Diagnostic page.
 */
@Component({
  selector: 'app-problem-summary-banner',
  imports: [MatCardModule, MatIconModule, MatButtonModule, RouterLink],
  template: `
    @if (texte(); as texte) {
      <mat-card appearance="outlined" class="probleme-summary probleme-summary-bloquant">
        <mat-card-content>
          <mat-icon>error</mat-icon>
          <p>{{ texte }}</p>
          <a matButton="tonal" routerLink="/diagnostic" i18n="@@problemes.summary.link"
            >Voir les problèmes</a
          >
        </mat-card-content>
      </mat-card>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProblemSummaryBanner {
  readonly problemes = input<Probleme[]>([]);

  protected readonly texte = computed(() => {
    const comptage = compterProblemes(this.problemes());
    if (comptage.bloquants === 0) {
      return null;
    }
    const total = comptage.total;
    const bloquants = comptage.bloquants;
    const avertissements = comptage.avertissements;
    const mineurs = comptage.mineurs;
    return $localize`:@@problemes.summary.counts:${total}:total: problème(s) : ${bloquants}:bloquants: bloquant(s), ${avertissements}:avertissements: avertissement(s), ${mineurs}:mineurs: mineur(s).`;
  });
}
