import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';

/**
 * « Documents »: the four files the plan becomes, each one saying who it is
 * for — the animateur's sheet, the archive of every individual planning, the
 * organiser's binder and the detail of the changes — instead of four buttons
 * that all began with « Exporter ». Printing one day is the Planning's.
 *
 * <p>They read the planning persisted for the edition on screen, so they wait
 * on the per-edition lock and nothing else.</p>
 */
@Component({
  selector: 'app-documents-panel',
  imports: [MatButtonModule, MatCardModule, MatIconModule, RouterLink],
  templateUrl: './documents-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentsPanel {
  private readonly planningApi = inject(PlanningApi);
  private readonly planningState = inject(PlanningStateService);

  /** The line the page shows in its output panel. */
  readonly reported = output<string>();

  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly busy = signal(false);

  /** One A4 landscape sheet per person, recto calendar and verso teams — what gets folded and handed out. */
  protected feuilles(): Promise<void> {
    return this.run(
      $localize`:@@solver.exportFeuillesBuilding:Construction des feuilles recto-verso...`,
      async () => this.planningApi.exportFeuilles(await this.planningState.require()),
    );
  }

  /** Every individual planning, PDF and calendar file, in one archive. */
  protected archive(): Promise<void> {
    return this.run(
      $localize`:@@solver.exportBuilding:Construction de l'archive d'export...`,
      async () => this.planningApi.exportBundle(await this.planningState.require()),
    );
  }

  /** Every assignment in one PDF, by day, stand and animateur — the organiser's own copy. */
  protected classeur(): Promise<void> {
    return this.run($localize`:@@solver.exportGlobalBuilding:Construction du PDF global...`, () =>
      this.planningApi.exportGlobalPdf(),
    );
  }

  /** One line per person to tell, read in a meeting before sending. */
  protected changements(): Promise<void> {
    return this.run(null, () => this.planningApi.exportPublicationDiff());
  }

  private async run(annonce: string | null, build: () => Promise<string>): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    if (annonce) {
      this.reported.emit(annonce);
    }
    try {
      this.reported.emit(await build());
    } catch (error) {
      this.reported.emit(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }
}
