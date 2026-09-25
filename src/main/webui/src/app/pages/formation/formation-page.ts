import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { errorText, retainedValue } from '../../core/resource-state';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';
import { libelleJour, libelleNiveau, resumeDeficit } from './formation';

/**
 * « À former » : who to train, typologie by typologie — the fifth tab of the
 * Diagnostic.
 *
 * The shortage shown is the Besoin tab's and the Fragilité tab's, read from
 * `GET /api/formation`, which reuses both reports rather than defining a
 * third; the candidates are the people one level from mastering the
 * typologie, ranked by the server in the order the screen spells out. Nothing
 * here solves or simulates, and nothing is written: moving somebody up a level
 * stays a gesture of the fiche or of the Compétences grid.
 */
@Component({
  selector: 'app-formation-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    OutputPanel,
    StatusMessage,
  ],
  templateUrl: './formation-page.html',
  styleUrl: './formation-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the other tabs.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormationPage {
  /** False when the Diagnostic page hosts this screen as one of its tabs. */
  readonly entete = input(true);

  private readonly analysesApi = inject(AnalysesApi);

  private readonly plan = resource({ loader: () => this.analysesApi.trainingPlan() });
  protected readonly rapport = retainedValue(this.plan);
  protected readonly chargement = this.plan.isLoading;
  protected readonly erreur = errorText(this.plan);

  protected readonly exportBusy = signal(false);
  protected readonly output = signal('');

  protected readonly libelleNiveau = libelleNiveau;
  protected readonly libelleJour = libelleJour;
  protected readonly resumeDeficit = resumeDeficit;

  protected recharger(): void {
    this.plan.reload();
  }

  protected async exporter(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@formation.exporting:Construction de l'export CSV...`);
    try {
      this.output.set(await this.analysesApi.exportTrainingPlan());
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}
