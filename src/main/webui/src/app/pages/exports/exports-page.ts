import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Exports page: individual PDF plannings and ICS calendars, both generated
 * server-side from the planning currently available (solved this session or
 * persisted in database).
 */
@Component({
  selector: 'app-exports-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, OutputPanel],
  templateUrl: './exports-page.html'
})
export class ExportsPage {
  protected readonly output = signal('');
  protected readonly busy = signal(false);

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);

  protected async onExportPdf(): Promise<void> {
    await this.exportPlanning('/api/planning/export/pdf/all', 'planning-pdf.zip');
  }

  protected async onExportIcs(): Promise<void> {
    await this.exportPlanning('/api/planning/export/ics/all', 'planning-ics.zip');
  }

  private async exportPlanning(url: string, filename: string): Promise<void> {
    this.busy.set(true);
    this.output.set('Building the archive...');
    try {
      const planning = await this.planningState.require();
      this.output.set(await this.api.downloadPost(url, filename, planning, 'application/zip'));
    } catch (error) {
      this.output.set(`Error: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.busy.set(false);
    }
  }
}
