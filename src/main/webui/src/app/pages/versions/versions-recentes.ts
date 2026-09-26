import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { EditionStore } from '../../core/edition.store';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry } from '../../core/models';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { StatusMessage } from '../../shared/status-message';
import { VersionRow, eventLabel, kpiSentence, rowKpi, versionRows } from './versions';

/** How many versions the Solveur shows: the latest few, the rest one click away. */
export const VERSIONS_RECENTES = 5;

/**
 * The last lines of « Versions du plan », on the Solveur (issue #702): what
 * the last solves gave and the snapshots taken around them, in the words of
 * the full table, which « Toutes les versions » opens. Read-only: restoring
 * and comparing stay on that page.
 */
@Component({
  selector: 'app-versions-recentes',
  imports: [MatButtonModule, MatCardModule, MatIconModule, RouterLink, StatusMessage],
  template: `
    <mat-card appearance="outlined" class="page-card">
      <mat-card-header>
        <h2 mat-card-title i18n="@@versions.recentes.title">Dernières versions du plan</h2>
      </mat-card-header>
      <mat-card-content>
        <app-status-message [text]="error()" tone="error" />
        @if (rows().length > 0) {
          <div class="table-wrapper">
            <table class="versions-recentes">
              <caption class="visually-hidden" i18n="@@versions.recentes.caption">
                Les cinq dernières versions du plan : date, événement et résultat
              </caption>
              <thead>
                <tr>
                  <th scope="col" i18n="@@versions.column.quand">Quand</th>
                  <th scope="col" i18n="@@versions.column.evenement">Événement</th>
                  <th scope="col" i18n="@@versions.column.resultat">Résultat</th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.key) {
                  <tr>
                    <td>{{ dateLabel(row) }}</td>
                    <td>{{ eventOf(row) }}</td>
                    <td>{{ sentenceOf(row) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else if (loaded()) {
          <p class="empty-hint" i18n="@@versions.recentes.empty">
            Aucune version pour l'instant : chaque résolution terminée en ajoute une.
          </p>
        }
      </mat-card-content>
      <mat-card-actions class="card-actions">
        <a matButton routerLink="/versions">
          <mat-icon>history</mat-icon>
          <ng-container i18n="@@versions.recentes.toutes">Toutes les versions</ng-container>
        </a>
      </mat-card-actions>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VersionsRecentes {
  /** Moved by the page when a solve lands: a new line to show. */
  readonly version = input(0);

  private readonly analysesApi = inject(AnalysesApi);
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly editions = inject(EditionStore);

  private readonly entries = signal<KpiHistoriqueEntry[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal('');

  protected readonly rows = computed(() =>
    versionRows(
      this.entries(),
      this.snapshots.snapshots(),
      this.editions.courant()?.id ?? null,
    ).slice(0, VERSIONS_RECENTES),
  );

  constructor() {
    effect(() => {
      this.version();
      untracked(() => void this.reload());
    });
  }

  private async reload(): Promise<void> {
    try {
      const [entries] = await Promise.all([this.analysesApi.kpiHistory(), this.snapshots.reload()]);
      this.entries.set(entries);
      this.error.set('');
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loaded.set(true);
    }
  }

  protected dateLabel(row: VersionRow): string {
    return row.date ? new Date(row.date).toLocaleString(intlLocale()) : '';
  }

  protected eventOf(row: VersionRow): string {
    return eventLabel(row);
  }

  protected sentenceOf(row: VersionRow): string {
    return kpiSentence(rowKpi(row));
  }
}
