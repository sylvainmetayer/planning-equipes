import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api.service';
import { ConstraintsView } from '../../core/models';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Raw dump of the last solve/analyze diagnostic (`GET /api/constraints`):
 * global score, unfilled seats, feasibility and per-constraint score/match
 * count. Deliberately excludes animateurs/creneaux/postes — this is a debug
 * aid, kept separate from the "Constraints" page's business-friendly card
 * view. The backend keeps the last diagnostic in memory for the life of the
 * server process, so this survives a browser refresh (it is only lost if the
 * server itself restarts).
 */
@Component({
  selector: 'app-debug-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatProgressBarModule, OutputPanel],
  templateUrl: './debug-page.html'
})
export class DebugPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly output = signal('');

  private readonly api = inject(ApiService);

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const view = await this.api.get<ConstraintsView>('/api/constraints');
      this.output.set(JSON.stringify(view, null, 2));
    } catch (error) {
      this.output.set('');
      this.error.set(`Error: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.loading.set(false);
    }
  }
}
