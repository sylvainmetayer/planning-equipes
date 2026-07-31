import { Component, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { SolverJobService, elapsedSeconds, formatDuration } from '../core/solver-job.service';

/**
 * Toolbar indicator of the solver job the server is running, whoever started
 * it. Hidden when the solver is idle.
 */
@Component({
  selector: 'app-job-monitor',
  imports: [MatIconModule, MatProgressBarModule],
  template: `
    @if (label()) {
      <span class="job-monitor" aria-live="polite">
        <mat-icon>hourglass_top</mat-icon>
        <span class="job-monitor-label">{{ label() }}</span>
        <mat-progress-bar class="job-monitor-bar" mode="indeterminate" />
      </span>
    }
  `
})
export class JobMonitor {
  private readonly jobs = inject(SolverJobService);

  protected readonly label = computed(() => {
    const job = this.jobs.activeJob();
    if (!job) {
      return '';
    }
    const origin = job.mine ? '' : ' (autre session)';
    return `${job.label} en cours… ${formatDuration(elapsedSeconds(job, this.jobs.now()))}${origin}`;
  });
}
