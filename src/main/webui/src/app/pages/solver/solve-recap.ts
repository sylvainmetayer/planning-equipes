import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { ImpactPublication, PreviousPlan, ReamorcageEffectue } from '../../core/models';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';

/**
 * What the last finished solve did, under the launch buttons: when it ran,
 * where it started from (issue #174), whom a publication would now disturb,
 * and what it replaced (issue #274) — with the way back when it made things
 * worse. The page owns the facts, read from the job result; this component
 * puts them in words and carries the one action, the restore.
 */
@Component({
  selector: 'app-solve-recap',
  imports: [MatCardModule, MatButtonModule],
  templateUrl: './solve-recap.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SolveRecap {
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly planningState = inject(PlanningStateService);
  private readonly problemes = inject(ProblemesStore);
  private readonly confirm = inject(ConfirmService);

  /** Completion time of the most recent finished solve job, if any has ever run. */
  readonly lastRunAt = input<string | null>(null);
  /** Where the last finished full solve started from. */
  readonly reamorcage = input<ReamorcageEffectue | null>(null);
  /** Whom the last finished solve would disturb, against the published plan. */
  readonly impact = input<ImpactPublication | null>(null);
  /** What the last solve replaced; null when there is nothing to compare against. */
  readonly previousPlan = input<PreviousPlan | null>(null);
  /** Full score of the last solve, the other half of the comparison. */
  readonly score = input<string | null>(null);

  /** The previous plan is persisted again: says how many seats came back. */
  readonly restored = output<string>();
  /** The restore was refused server-side. */
  readonly failed = output<string>();

  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly restoring = signal(false);

  protected readonly formattedLastRun = computed(() => {
    const lastRunAt = this.lastRunAt();
    return lastRunAt ? new Date(lastRunAt).toLocaleString(intlLocale()) : '';
  });

  protected readonly reamorcageLabel = computed(() => {
    const reamorcage = this.reamorcage();
    if (!reamorcage) {
      return '';
    }
    if (reamorcage.mode === 'AUCUN') {
      return $localize`:@@solver.reamorcage.aFroid:Point de départ : aucun, calcul de zéro.`;
    }
    return reamorcage.postesLiberes > 0
      ? $localize`:@@solver.reamorcage.planAvecLiberes:Point de départ : le plan enregistré, ${reamorcage.postes}:count: postes repris et ${reamorcage.postesLiberes}:liberes: laissés libres (animateur disparu ou devenu indisponible).`
      : $localize`:@@solver.reamorcage.plan:Point de départ : le plan enregistré, ${reamorcage.postes}:count: postes repris.`;
  });

  protected readonly impactLabel = computed(() => {
    const impact = this.impact();
    if (!impact) {
      return '';
    }
    const quand = new Date(impact.publieLe).toLocaleString(intlLocale());
    if (impact.personnes === 0) {
      return $localize`:@@solver.impact.aucun:Personne ne change d'emploi du temps par rapport au plan publié le ${quand}:date:.`;
    }
    return $localize`:@@solver.impact.personnes:${impact.personnes}:count: personne(s) changeraient d'emploi du temps par rapport au plan publié le ${quand}:date: — c'est ce que la publication leur dirait.`;
  });

  /** The comparison is only worth showing when both scores are known. */
  protected readonly comparison = computed(() => {
    const previous = this.previousPlan();
    const after = this.score();
    return previous?.score && after ? { avant: previous.score, apres: after } : null;
  });

  /**
   * Puts back the plan the last solve replaced (issue #274). Offered only when
   * the solve made things worse — the same restore the snapshots screen does,
   * brought to where the user learns they lost something rather than leaving
   * them to find it.
   */
  protected async restore(): Promise<void> {
    const previous = this.previousPlan();
    if (!previous || this.restoring()) {
      return;
    }
    const confirmed = await this.confirm.ask({
      title: $localize`:@@solver.previousPlan.restore.title:Revenir au plan d'avant ?`,
      message: $localize`:@@solver.previousPlan.restore.message:Le résultat de cette résolution est remplacé par le plan qui était enregistré avant elle.`,
      confirmLabel: $localize`:@@solver.previousPlan.restore.confirm:Revenir`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.restoring.set(true);
    try {
      const result = await this.snapshots.restaurer(previous.snapshotId);
      await this.resolution.reload();
      this.planningState.set(null);
      void this.problemes.reload();
      this.restored.emit(
        $localize`:@@solver.previousPlan.restored:${result.affectations}:count: affectation(s) restaurée(s) : le plan d'avant la résolution est de nouveau enregistré.`
      );
    } catch (error) {
      this.failed.emit(errorPrefix(error));
    } finally {
      this.restoring.set(false);
    }
  }
}
