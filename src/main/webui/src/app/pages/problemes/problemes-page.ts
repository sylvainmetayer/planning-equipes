import {
  computed,
  inject,
  input,
  signal,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { Router, RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { PlanningStateService } from '../../core/planning-state.service';
import { ActionProbleme, NiveauProbleme, niveauProblemeLabel } from '../../core/problemes';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { currentViewParams, keepViewInQueryParams } from '../../core/view-query-params';
import { ScoreReadingPanel } from '../../shared/lecture-score';
import { LegalText } from '../../shared/legal-text';
import { catalogueByName, resolveSeat, seatHours } from '../../shared/siege-panel/seat';
import { SeatPlacement } from '../../shared/siege-panel/seat-placement';
import { StatusMessage } from '../../shared/status-message';
import { EcartsPivotCard } from './ecarts-pivot-card';

/**
 * Every known problem of the current dataset, most blocking first: the
 * solver-free feasibility causes (available before any solve) merged with the
 * violations of the last analysed solve.
 *
 * Each card says where the problem bites (« Où », the Journée on that stand
 * and that day, its Siège panel open), on whom (« Qui », each fiche), and the
 * gestures that fix it in the order the catalogue gives them, the lowering of
 * a rule's importance last. One of them is made here rather than elsewhere:
 * « Qui peut tenir ce siège ? » opens the bench of a free seat in place, and
 * its « Placer » fills the seat without leaving the Diagnostic. The others
 * open the screen that makes the gesture, positioned on the problem.
 *
 * Under the cards, « Où se concentrent les écarts » — the pivot that lived,
 * folded, on the constraints screen — open, and narrowed to the rule chosen
 * (`?regle=`).
 */
@Component({
  selector: 'app-problemes-page',
  imports: [
    StatusMessage,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressBarModule,
    MatTooltipModule,
    LegalText,
    ScoreReadingPanel,
    EcartsPivotCard,
  ],
  templateUrl: './problemes-page.html',
  styleUrl: './problemes-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProblemesPage {
  /**
   * False when the Diagnostic page hosts this screen as one of its tabs: the
   * page then carries the title, and a second heading would only repeat it.
   */
  readonly entete = input(true);

  protected readonly store = inject(ProblemesStore);
  protected readonly jobs = inject(SolverJobService);
  private readonly referentiel = inject(ReferenceDataStore);
  private readonly planningState = inject(PlanningStateService);
  private readonly placement = inject(SeatPlacement);
  private readonly router = inject(Router);

  /** The rule the pivot is narrowed to (`?regle=`); empty for every rule in default. */
  protected readonly regle = signal(currentViewParams().get('regle') ?? '');

  /** Pre-labelled rows, so the template never calls a function per row. */
  protected readonly problemes = computed(() =>
    this.store.problemes().map((probleme) => ({
      ...probleme,
      niveauLabel: niveauProblemeLabel(probleme.niveau),
      badgeClass: this.badgeClass(probleme.niveau),
    })),
  );

  protected readonly comptage = computed(() => this.store.comptage());

  /** Causes hidden by the server-side cap of `FeasibilityReport.causes`. */
  protected readonly causesRestantes = computed(() => {
    const report = this.store.report();
    return report ? Math.max(0, report.totalCauses - report.causes.length) : 0;
  });

  protected readonly resumeFaisabilite = computed(() => {
    const report = this.store.report();
    if (!report) {
      return $localize`:@@problemes.feasibility.unavailable:Le diagnostic de faisabilité n'a pas pu être chargé.`;
    }
    return report.feasible
      ? $localize`:@@problemes.feasibility.ok:Aucun problème de capacité détecté sur les données actuelles.`
      : report.message;
  });

  /**
   * When the plan was last read. Its score in figures goes to a tooltip: the
   * sentences of « Lecture du score » say what it means, and `0hard/-120medium`
   * in the running text only asked the reader to decode it.
   */
  protected readonly resumeAnalyse = computed(() => {
    const constraints = this.store.constraints();
    if (!constraints?.analysedAt) {
      return $localize`:@@problemes.analysis.none:Aucune analyse de résolution pour le moment : lancez une résolution depuis la page Solveur pour voir les règles en défaut.`;
    }
    const analysedAt = new Date(constraints.analysedAt).toLocaleString(intlLocale());
    return $localize`:@@problemes.analysis.at:Dernière analyse ${analysedAt}:date:.`;
  });

  /** The score in figures, for the tooltip of the line above; empty when nothing was analysed. */
  protected readonly scoreTooltip = computed(() => {
    const score = this.store.constraints()?.scoreGlobal;
    return score ? $localize`:@@problemes.analysis.score:Score ${score}:score:` : '';
  });

  /** A « Placer » in flight: one at a time. */
  protected readonly busy = signal(false);
  /** What the last in-place gesture did, or why it could not. */
  protected readonly outcome = signal('');
  protected readonly outcomeError = signal('');

  constructor() {
    void this.store.reload();
    // Cards name stands, people and timeslots by id: the referential gives
    // them their names and hours. A failure only leaves the ids on screen.
    void this.referentiel.reload(['stands', 'animateurs', 'creneaux']).catch(() => undefined);
    keepViewInQueryParams(() => ({ regle: this.regle() || null }));
    // A solve started from anywhere (this browser or another) rewrites both
    // sources: refresh once it lands. Unregistered on destroy, like every
    // other lazy-loaded page's handler.
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(this.jobs.onResult('SOLVE', () => void this.store.reload()));
  }

  protected refresh(): void {
    void this.store.reload();
  }

  /** « Où se concentrent ces écarts » on a card: the pivot narrows to its rule. */
  protected showPivot(regle: string): void {
    this.regle.set(regle);
    document.getElementById('ecarts-pivot')?.scrollIntoView({ block: 'start' });
  }

  /**
   * « Qui peut tenir ce siège ? » in place: a free seat of the timeslot — of
   * its stand when the problem names one — asked about in the bench dialog,
   * and filled by its « Placer ». A timeslot with no free seat left is not a
   * question the dialog can answer: the Journée opens on it instead, its
   * Siège panel offering what a held seat allows.
   */
  protected async whoCanHold(action: ActionProbleme): Promise<void> {
    const target = action.seat;
    if (!target || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.outcome.set('');
    this.outcomeError.set('');
    try {
      const planning = await this.planningState.loadForDisplay();
      const seat = resolveSeat(planning.postes ?? [], target);
      if (!seat?.creneau || seat.animateur) {
        await this.router.navigate([action.route], { queryParams: action.queryParams });
        return;
      }
      const { start, end } = seatHours(seat);
      const stand = seat.stand?.nom || seat.stand?.id || '';
      const choice = await this.placement.choose({
        posteId: seat.id,
        creneauId: seat.creneau.id,
        standId: seat.stand?.id ?? null,
        title: `${stand} · ${seat.creneau.date ?? ''} · ${start}–${end}`,
        animateurs: planning.animateurs ?? [],
        catalogue: catalogueByName(this.store.constraints()?.contraintes ?? []),
        offerPlacement: true,
      });
      if (!choice) {
        return;
      }
      const animateur = planning.animateurs?.find((person) => person.id === choice.animateurId);
      const nom = animateur
        ? `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id
        : choice.animateurId;
      const done = await this.placement.place(seat.id, seat.creneau.id, choice, nom);
      this.outcome.set(done.warning ? `${done.message} ${done.warning}` : done.message);
      this.outcomeError.set(done.lockError);
      // The plan moved: the session's copy is stale, and so is the diagnostic.
      this.planningState.set(null);
      void this.store.reload();
    } catch (error) {
      this.outcomeError.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }

  private badgeClass(niveau: NiveauProbleme): string {
    return `probleme-badge probleme-badge-${niveau.toLowerCase()}`;
  }
}
