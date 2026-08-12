import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ConfirmService } from './confirm-dialog';
import { NotificationService } from '../core/notification.service';
import { SolverJobService, elapsedSeconds, formatDuration } from '../core/solver-job.service';

/**
 * Toolbar indicator of the solver job the server is running, whoever started
 * it. Hidden when the solver is idle.
 */
@Component({
  selector: 'app-job-monitor',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule],
  template: `
    @if (label(); as jobLabel) {
      <span class="job-monitor" aria-live="polite">
        <mat-icon>hourglass_top</mat-icon>
        <span class="job-monitor-label">{{ jobLabel }}</span>
        <mat-progress-bar class="job-monitor-bar" mode="indeterminate" />
        <button
          matIconButton
          type="button"
          [disabled]="stopping()"
          [attr.aria-label]="stopLabel"
          [title]="stopLabel"
          (click)="stop()"
        >
          <mat-icon>stop_circle</mat-icon>
        </button>
      </span>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class JobMonitor {
  private readonly jobs = inject(SolverJobService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly stopLabel = $localize`:@@jobMonitor.stop:Arrêter le solveur`;
  protected readonly stopping = signal(false);

  protected readonly label = computed(() => {
    const job = this.jobs.activeJob();
    if (!job) {
      return '';
    }
    const duration = formatDuration(elapsedSeconds(job, this.jobs.now()));
    return job.mine
      ? $localize`:@@jobMonitor.mine:${job.label}:jobLabel: en cours… ${duration}:duration:`
      : $localize`:@@jobMonitor.other:${job.label}:jobLabel: en cours… ${duration}:duration: (autre session)`;
  });

  /** Started by mistake or not, anyone should be able to stop it. */
  protected async stop(): Promise<void> {
    const job = this.jobs.activeJob();
    if (!job) {
      return;
    }
    const confirmed = await this.confirm.ask({
      title: this.stopLabel,
      message: $localize`:@@jobMonitor.stopConfirm:Arrêter ${job.label}:jobLabel: en cours ? Le résultat partiel sera tout de même analysé et enregistré.`,
      confirmLabel: this.stopLabel,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.stopping.set(true);
    try {
      await this.jobs.cancel(job.id);
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@jobMonitor.stopFailedTitle:Arrêt impossible`,
        message: error instanceof Error ? error.message : String(error),
        variant: 'error'
      });
    } finally {
      this.stopping.set(false);
    }
  }
}

