import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { errorPrefix } from '../../core/error-message';
import { JobView } from '../../core/models';
import { SolverJobService } from '../../core/solver-job.service';

/**
 * Solves planned behind the running one. Server-side and shared: one planned
 * from another browser shows up here too, and can be removed from here.
 * Renders nothing while the queue is empty, so the launch card it sits in
 * keeps its shape.
 */
@Component({
  selector: 'app-solver-queue',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './solver-queue.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SolverQueue {
  private readonly jobs = inject(SolverJobService);

  /** Why a removal was refused, for the page's output panel. */
  readonly failed = output<string>();

  protected readonly queue = computed(() => this.jobs.file());

  /** What a planned job will do, and to which edition — the two things worth reading in the queue. */
  protected typeLabel(job: JobView): string {
    if (job.type === 'SOLVE_INCREMENTAL') {
      return $localize`:@@job.type.solveIncremental:Replanification incrémentale`;
    }
    return job.reamorcage === 'AUCUN'
      ? $localize`:@@job.type.solveAFroid:Calcul du planning (de zéro)`
      : $localize`:@@job.type.solve:Calcul du planning`;
  }

  protected editionLabel(job: JobView): string {
    return job.editionNom ?? job.editionId ?? '?';
  }

  /**
   * Removes a solve from the queue before it starts. Nothing ran, so there is
   * nothing to stop — unlike stopping the running job, which keeps its partial
   * result.
   */
  protected async remove(job: JobView): Promise<void> {
    try {
      await this.jobs.retirerDeLaFile(job.id);
    } catch (error) {
      this.failed.emit(errorPrefix(error));
    }
  }
}
