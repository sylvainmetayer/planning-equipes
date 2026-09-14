import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import {
  ImpactPublication,
  PreviousPlan,
  ReamorcageEffectue,
  RestaurationSnapshot,
} from '../../core/models';
import { InstantanePerimeError, PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { confirmStaleRestore } from '../../shared/stale-snapshot-confirm';

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
  changeDetection: ChangeDetectionStrategy.OnPush,
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
  /**
   * The same score with its floors taken out (issue #495): what the solve
   * could actually move. Shown only when it differs from the raw one.
   */
  readonly scoreHorsPlancher = input<string | null>(null);

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
    return $localize`:@@solver.impact.personnes:${impact.personnes}:count: personne(s) changeraient d'emploi du temps par rapport au plan publié le ${quand}:date:.`;
  });

  /**
   * Said next to the raw score, and only when a floor exists: a raw
   * « -6 675 medium » of which -5 000 no solve will ever recover reads as a
   * bad plan, where « -1 675 hors plancher » is what the run is worth.
   */
  protected readonly horsPlancherLabel = computed(() => {
    const brut = this.score();
    const net = this.scoreHorsPlancher();
    if (!brut || !net || net === brut) {
      return '';
    }
    return $localize`:@@solver.scoreHorsPlancher:Hors plancher : ${net}:score: — le reste est une constante que des données absentes expliquent (voir Contraintes).`;
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
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    this.restoring.set(true);
    try {
      const result = await this.restaurer(previous.snapshotId);
      if (!result) {
        return;
      }
      await this.resolution.reload();
      this.planningState.set(null);
      void this.problemes.reload();
      this.restored.emit(
        $localize`:@@solver.previousPlan.restored:${result.affectations}:count: affectation(s) restaurée(s) : le plan d'avant la résolution est de nouveau enregistré.`,
      );
    } catch (error) {
      this.failed.emit(errorPrefix(error));
    } finally {
      this.restoring.set(false);
    }
  }

  /**
   * The same staleness question the Instantanés screen asks (issue #170). The
   * capture this button offers is the one taken just before the solve, so any
   * referential write since — a stand added while the run was going, a fiche
   * deleted right after — makes the server refuse it. Without this, the one
   * button that exists to undo a bad solve would answer 409 and stop there,
   * with no way to say « quand même ».
   */
  private async restaurer(snapshotId: number): Promise<RestaurationSnapshot | null> {
    try {
      return await this.snapshots.restaurer(snapshotId);
    } catch (error) {
      if (!(error instanceof InstantanePerimeError)) {
        throw error;
      }
      return (await confirmStaleRestore(this.confirm, error))
        ? this.snapshots.restaurer(snapshotId, true)
        : null;
    }
  }
}
