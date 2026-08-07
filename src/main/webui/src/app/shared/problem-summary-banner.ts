import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { compterProblemes, Probleme } from '../core/problemes';

/**
 * Compact aggregate of everything wrong with the current dataset and the last
 * analysed solve — how many problems, split by severity — plus a way in to the
 * Problèmes page, which lists them all.
 *
 * Silent when there is nothing to report, like `app-feasibility-banner`: the
 * nominal path stays free of noise.
 */
@Component({
  selector: 'app-problem-summary-banner',
  imports: [MatCardModule, MatIconModule, MatButtonModule, RouterLink],
  template: `
    @if (resume(); as resume) {
      <mat-card appearance="outlined" class="probleme-summary" [class.probleme-summary-bloquant]="resume.bloquant">
        <mat-card-content>
          <mat-icon>{{ resume.bloquant ? 'error' : 'warning' }}</mat-icon>
          <p>{{ resume.texte }}</p>
          <a matButton="tonal" routerLink="/problemes" i18n="@@problemes.summary.link">Voir les problèmes</a>
        </mat-card-content>
      </mat-card>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProblemSummaryBanner {
  readonly problemes = input<Probleme[]>([]);

  protected readonly resume = computed(() => {
    const comptage = compterProblemes(this.problemes());
    if (comptage.total === 0) {
      return null;
    }
    const total = comptage.total;
    const bloquants = comptage.bloquants;
    const avertissements = comptage.avertissements;
    const mineurs = comptage.mineurs;
    return {
      bloquant: comptage.bloquants > 0,
      texte: $localize`:@@problemes.summary.counts:${total}:total: problème(s) : ${bloquants}:bloquants: bloquant(s), ${avertissements}:avertissements: avertissement(s), ${mineurs}:mineurs: mineur(s).`
    };
  });
}
