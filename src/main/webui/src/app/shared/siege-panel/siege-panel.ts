// The Siège panel: one seat of the plan on screen, and every gesture on it,
// beside the rendering that showed it — not a full-screen dialog in front of
// it. Opened by a click on any cell of the Journée (a name or a free seat of
// the calendar, a shift of the rail, a stand of the map, a break without
// relay, a line of the changes), it reads the plan the page already loaded:
// nothing is fetched per cell, and the candidates are asked for on demand.

import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  resource,
  signal,
  untracked,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { PostesApi } from '../../core/api/postes-api';
import { errorPrefix } from '../../core/error-message';
import { JourJService } from '../../core/jour-j.service';
import {
  Avertissement,
  ContrainteAdHoc,
  ContrainteImpact,
  PlanningEvenement,
  PosteAffectation,
  SuggestionReparation,
  SuggestionsReparation,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import {
  AdHocConstraintFormData,
  AdHocConstraintFormDialog,
} from '../../pages/ad-hoc-constraints/ad-hoc-constraint-form-dialog';
import {
  animateurName,
  meilleuresSuggestions,
  nomAnimateur,
  suggestionsTronquees,
} from '../affectation-explanation-rules';
import { ConfirmService } from '../confirm-dialog';
import { moveTargets, resumeDeplacement } from '../deplacement';
import { openMoveDialog } from '../deplacement-dialog';
import { StatusMessage } from '../status-message';
import { openBenchDialog } from './bench-dialog';
import {
  catalogueByName,
  levelLabel,
  lockLabel,
  NextSteps,
  nextSteps,
  qualityWarning,
  ruleLabel,
  SeatGesture,
  SeatLock,
  seatHours,
  seatLocks,
  scoreEffect,
} from './seat';

/** What the panel says once a gesture went through, until another seat is opened. */
interface Outcome {
  message: string;
  warning: string | null;
  steps: NextSteps;
}

let nextPanelId = 0;

@Component({
  selector: 'app-siege-panel',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, RouterLink, StatusMessage],
  templateUrl: './siege-panel.html',
  styleUrl: './siege-panel.css',
  // Global by design (AGENTS.md): loaded with the route that draws the panel.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeatPanel {
  /** The plan the page loaded — the panel never reads one of its own. */
  readonly planning = input<PlanningEvenement | null>(null);
  /** The seat on show. */
  readonly posteId = input<string | null>(null);
  /** « Fermer » or Escape: the page drops the seat from its URL and gives the focus back. */
  readonly closed = output<void>();
  /** A gesture rewrote the persisted plan: the page re-reads it. */
  readonly planChanged = output<void>();

  private readonly explanations = inject(AffectationExplanationService);
  private readonly repairs = inject(JourJService);
  private readonly postesApi = inject(PostesApi);
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly verrous = inject(VerrouillageStore);
  private readonly store = inject(ReferenceDataStore);
  private readonly jobs = inject(SolverJobService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

  protected readonly headingId = `siege-panel-title-${nextPanelId++}`;
  /** A solve holding the edition refuses every write here: the gestures wait for it. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly seat = computed<PosteAffectation | null>(
    () => this.planning()?.postes?.find((poste) => poste.id === this.posteId()) ?? null,
  );
  protected readonly standName = computed(() => {
    const stand = this.seat()?.stand;
    return stand?.nom || stand?.id || '';
  });
  /** « Jour 5 — 2026-09-05 · 18:00–22:00 ». */
  protected readonly when = computed(() => {
    const seat = this.seat();
    if (!seat?.creneau) {
      return '';
    }
    const { start, end } = seatHours(seat);
    const jour = seat.creneau.jour;
    const date = seat.creneau.date;
    return date
      ? $localize`:@@siege.quand.date:Jour ${jour}:jour: — ${date}:date: · ${start}:debut:–${end}:fin:`
      : $localize`:@@siege.quand:Jour ${jour}:jour: · ${start}:debut:–${end}:fin:`;
  });
  protected readonly holder = computed(() => {
    const animateur = this.seat()?.animateur;
    return animateur ? animateurName(animateur) : '';
  });

  protected readonly locks = computed<SeatLock[]>(() => {
    const seat = this.seat();
    return seat ? seatLocks(this.verrous.verrouillages(), seat) : [];
  });
  /** The lock on this very seat, the one the panel lifts. */
  protected readonly ownLock = computed(() => this.locks().find((lock) => lock.removable) ?? null);
  /** Frozen by a lock of wider reach — lifted on the Verrouillages screen only. */
  protected readonly wideLock = computed(() => this.locks().some((lock) => !lock.removable));
  /** Any lock: the server refuses every write on the seat, so the gestures say so first. */
  protected readonly frozen = computed(() => this.locks().length > 0);

  /** A write in flight: one gesture at a time. */
  protected readonly busy = signal(false);
  protected readonly writesDisabled = computed(
    () => this.busy() || this.editingLocked() || this.frozen(),
  );

  /** The catalogue, for the rules' short labels; read once, a failure only costs the labels. */
  private readonly catalogueResource = resource({
    loader: () => this.constraintsApi.catalogue(),
  });
  protected readonly catalogue = computed(() =>
    this.catalogueResource.hasValue()
      ? catalogueByName(this.catalogueResource.value().contraintes)
      : null,
  );

  /**
   * « Pourquoi lui ? » in sentences: the rules the holder breaks on this seat,
   * read on the persisted plan under the edition's rules — never the copy on
   * screen, which carries no disabled constraint and no weight. Asked when an
   * occupied seat is opened — one call per opened seat, never one per cell —
   * and again whenever the page re-reads the plan after a gesture.
   */
  protected readonly explanation = resource({
    params: () => {
      const seat = this.seat();
      return seat?.animateur && this.planning()
        ? { posteId: seat.id, holderId: seat.animateur.id }
        : undefined;
    },
    loader: ({ params }) => this.postesApi.explanation(params.posteId),
  });
  protected readonly explanationError = errorText(this.explanation);

  /* The replacements are searched on demand — one score analysis per candidate server-side. */
  protected readonly suggestions = linkedSignal<string | null, SuggestionsReparation | null>({
    source: this.posteId,
    computation: () => null,
  });
  protected readonly suggestionsLoading = signal(false);
  protected readonly best = computed<SuggestionReparation[]>(() =>
    meilleuresSuggestions(this.suggestions()),
  );
  protected readonly truncated = computed(() => suggestionsTronquees(this.suggestions()));

  /** What the last gesture did, and what is left; reset when another seat is opened. */
  protected readonly outcome = linkedSignal<string | null, Outcome | null>({
    source: this.posteId,
    computation: () => null,
  });
  protected readonly gestureError = linkedSignal<string | null, string>({
    source: this.posteId,
    computation: () => '',
  });
  /** « Corriger le reste » was sent: the solver page takes it from there. */
  protected readonly repairSent = linkedSignal<string | null, boolean>({
    source: this.posteId,
    computation: () => false,
  });

  protected readonly closeLabel = $localize`:@@siege.fermer:Fermer le panneau du siège`;

  constructor() {
    // The padlocks are read from the store the calendars already fill: a
    // refusal only costs the lock lines, never the panel.
    void this.verrous.reload().catch(() => undefined);
    // Focus moves to the panel's title when a seat is opened, so a keyboard
    // or screen-reader user lands where the answer is.
    effect(() => {
      if (this.posteId()) {
        untracked(() =>
          afterNextRender(() => this.heading()?.nativeElement.focus(), {
            injector: this.injector,
          }),
        );
      }
    });
  }

  protected close(): void {
    this.closed.emit();
  }

  /** Escape closes the panel — on the panel itself, never a `document` listener. */
  protected onEscape(event: Event): void {
    event.stopPropagation();
    this.close();
  }

  /* ------------------------------ words ------------------------------ */

  protected lockText(lock: SeatLock): string {
    return lockLabel(lock);
  }

  protected ruleText(impact: ContrainteImpact): string {
    return ruleLabel(impact.name, this.catalogue(), impact.description);
  }

  protected levelText(impact: ContrainteImpact): string {
    return levelLabel(impact.niveau);
  }

  protected name(animateurId: string): string {
    const planning = this.planning();
    return planning ? nomAnimateur(planning, animateurId) : animateurId;
  }

  protected scoreText(suggestion: SuggestionReparation): string {
    return scoreEffect(suggestion.delta);
  }

  protected applyLabel(suggestion: SuggestionReparation): string {
    const nom = this.name(suggestion.animateurId);
    return $localize`:@@siege.remplacer.appliquer.label:Mettre ${nom}:nom: sur ce siège`;
  }

  /* ----------------------------- gestures ----------------------------- */

  /**
   * « Remplacer » : the viable replacements, best impact first — searched on
   * the persisted plan prepared server-side, as the write that follows reads
   * it: the constraints switched off, the weights and the quotas of the
   * edition, not a bare copy of the plan on screen.
   */
  protected async searchReplacements(): Promise<void> {
    const seat = this.seat();
    if (!seat) {
      return;
    }
    this.suggestionsLoading.set(true);
    this.gestureError.set('');
    try {
      this.suggestions.set(await this.repairs.suggestions(seat.id));
    } catch (error) {
      this.suggestions.set(null);
      this.gestureError.set(errorPrefix(error));
    } finally {
      this.suggestionsLoading.set(false);
    }
  }

  protected async applyReplacement(suggestion: SuggestionReparation): Promise<void> {
    const seat = this.seat();
    if (!seat) {
      return;
    }
    const nom = this.name(suggestion.animateurId);
    const holderId = seat.animateur?.id ?? null;
    await this.write('replace', async () => {
      // The person shown: somebody else holding the seat by now is a 409.
      await this.explanations.applyRepair(seat.id, suggestion.animateurId, holderId);
      this.suggestions.set(null);
      return {
        message: $localize`:@@siege.remplacer.fait:${nom}:nom: prend ce siège.`,
        warning: qualityWarning(suggestion.delta),
      };
    });
  }

  /**
   * « Déplacer vers » : the move dialog of the day views, offered whether or
   * not the instance switched the drag and drop on — that flag governs the
   * pointer gesture only.
   */
  protected async move(): Promise<void> {
    const seat = this.seat();
    const planning = this.planning();
    if (!seat?.animateur || !planning) {
      return;
    }
    const nom = this.holder();
    const choice = await firstValueFrom(
      openMoveDialog(this.dialog, {
        title: $localize`:@@calendarDay.deplacer.titre:Déplacer ${nom}:nom:`,
        targets: moveTargets(
          planning.postes ?? [],
          seat,
          (poste) =>
            this.verrous.estStandVerrouille(poste.stand?.id) ||
            this.verrous.estCreneauVerrouille(poste.creneau?.id) ||
            this.verrous.estJourVerrouille(poste.creneau?.date),
        ),
        targetLabel: $localize`:@@calendarDay.deplacer.cible:Vers quel siège ?`,
      }).afterClosed(),
    );
    if (!choice) {
      return;
    }
    const holderId = seat.animateur.id;
    await this.write('move', async () => {
      const simulation = await this.explanations.deplacer(
        seat.id,
        { posteId: choice.target },
        holderId,
      );
      const summary = resumeDeplacement(simulation, (id) => this.name(id));
      return {
        message: summary.title,
        warning: qualityWarning(simulation.delta),
        // Moved onto a free seat: the one it left is empty now.
        holeOpened: simulation.posteCibleId !== null && simulation.animateurCibleId === null,
      };
    });
  }

  /**
   * « Libérer » : the seat emptied — never refused for a rule, asked first all
   * the same. Left ticked, the person is also kept off this timeslot by an
   * `ANIMATEUR_CRENEAU` lock, the one an accepted échange lays on whoever it
   * frees: without it, « Corriger le reste » fills the hole with the very
   * person just taken out of it.
   */
  protected async free(): Promise<void> {
    const seat = this.seat();
    if (!seat?.animateur || !seat.creneau) {
      return;
    }
    const nom = this.holder();
    const holderId = seat.animateur.id;
    const creneauId = seat.creneau.id;
    const answer = await this.confirm.askWithOption({
      title: $localize`:@@siege.liberer.titre:Libérer ce siège ?`,
      message: $localize`:@@siege.liberer.message:${nom}:nom: n'y sera plus affecté ; le siège reste vide jusqu'à ce que quelqu'un le prenne.`,
      confirmLabel: $localize`:@@siege.liberer:Libérer`,
      danger: true,
      option: {
        label: $localize`:@@siege.liberer.ecart:La tenir à l'écart de ce créneau au prochain calcul`,
        checked: true,
      },
    });
    if (!answer) {
      return;
    }
    await this.write('free', async () => {
      await this.explanations.applyRepair(seat.id, null, holderId);
      let message = $localize`:@@siege.liberer.fait:Siège libéré.`;
      let warning: string | null = null;
      if (answer.checked) {
        const kept = await this.keepLock(holderId, creneauId);
        if (kept) {
          message = $localize`:@@siege.liberer.faitEcart:Siège libéré : ${nom}:nom: restera à l'écart de ce créneau au prochain calcul.`;
          warning = kept.warning;
        }
      }
      return { message, warning, holeOpened: true };
    });
  }

  /**
   * Lays the `ANIMATEUR_CRENEAU` lock a gesture asked for, after the seat was
   * written: a refusal is said in the panel without undoing the seat, and the
   * points the server wants read about the lock come back as the warning.
   * Null when the lock could not be laid.
   */
  private async keepLock(
    animateurId: string,
    creneauId: number,
  ): Promise<{ warning: string | null } | null> {
    try {
      const avertissements = await this.verrous.create({
        type: 'ANIMATEUR_CRENEAU',
        animateurId,
        creneauId,
      });
      return { warning: lockWarning(avertissements) };
    } catch (error) {
      const cause = errorPrefix(error);
      this.gestureError.set(
        $localize`:@@siege.verrou.echec:Siège écrit, mais le verrou n'a pas pu être posé : ${cause}:erreur:`,
      );
      return null;
    }
  }

  /** « Verrouiller » : this person on this timeslot, kept by the next solve. */
  protected async lock(): Promise<void> {
    const seat = this.seat();
    if (!seat?.animateur || !seat.creneau) {
      return;
    }
    const animateurId = seat.animateur.id;
    const creneauId = seat.creneau.id;
    await this.write('lock', async () => {
      const avertissements = await this.verrous.create({
        type: 'ANIMATEUR_CRENEAU',
        animateurId,
        creneauId,
      });
      return {
        message: $localize`:@@siege.verrouiller.fait:Verrouillé : ce siège ne bougera plus au prochain calcul.`,
        warning: lockWarning(avertissements),
      };
    });
  }

  protected async unlock(): Promise<void> {
    const own = this.ownLock();
    if (!own) {
      return;
    }
    await this.write('unlock', async () => {
      await this.verrous.remove(own.lock.id);
      return {
        message: $localize`:@@siege.deverrouiller.fait:Déverrouillé : le prochain calcul peut à nouveau changer ce siège.`,
        warning: null,
      };
    });
  }

  /**
   * « Qui peut tenir ce siège ? » : the bench of this seat, in a dialog. On an
   * empty seat its « Placer » seats the person chosen and, the box left
   * ticked, locks them there so the next solve keeps the placement; on a held
   * one it only reads who could replace the holder.
   */
  protected async whoCanHold(): Promise<void> {
    const seat = this.seat();
    const planning = this.planning();
    if (!seat?.creneau || !planning) {
      return;
    }
    const choice = await firstValueFrom(
      openBenchDialog(this.dialog, {
        posteId: seat.id,
        creneauId: seat.creneau.id,
        standId: seat.stand?.id ?? null,
        title: `${this.standName()} · ${this.when()}`,
        animateurs: planning.animateurs ?? [],
        catalogue: this.catalogue(),
        offerPlacement: !seat.animateur,
      }).afterClosed(),
    );
    if (!choice || seat.animateur) {
      return;
    }
    const creneauId = seat.creneau.id;
    const nom = this.name(choice.animateurId);
    await this.write('place', async () => {
      const placement = await this.postesApi.place(seat.id, choice.animateurId);
      let message = $localize`:@@deplacement.place:${nom}:cible: est placé(e) sur ce siège.`;
      const warnings = [qualityWarning(placement.delta)];
      if (choice.keep) {
        const kept = await this.keepLock(choice.animateurId, creneauId);
        if (kept) {
          message = $localize`:@@siege.placer.garde:${nom}:nom: est placé(e) sur ce siège, et y restera au prochain calcul.`;
          warnings.push(kept.warning);
        }
      }
      return { message, warning: warnings.filter(Boolean).join(' ') || null };
    });
  }

  /**
   * « Poser un ajustement » : the adjustment form, pre-filled with this stand
   * and this timeslot as a forced assignment — the person is the reader's to
   * choose. The form, not the network view of the adjustments screen.
   */
  protected async addAdjustment(): Promise<void> {
    const seat = this.seat();
    if (!seat?.creneau || !seat.stand) {
      return;
    }
    this.gestureError.set('');
    try {
      await this.store.reload(['creneaux', 'stands', 'animateurs']);
    } catch (error) {
      this.gestureError.set(errorPrefix(error));
      return;
    }
    const contrainte: ContrainteAdHoc = {
      id: '',
      type: 'AFFECTATION_FORCEE',
      animateursConcernes: [],
      creneau: { id: seat.creneau.id },
      stand: { id: seat.stand.id },
      raison: '',
    };
    this.dialog.open<AdHocConstraintFormDialog, AdHocConstraintFormData, boolean>(
      AdHocConstraintFormDialog,
      { data: { contrainte }, width: '40rem', maxWidth: '95vw', autoFocus: 'first-tabbable' },
    );
  }

  /** « Corriger le reste » : an incremental solve, which refills the holes and keeps the rest. */
  protected async repairRest(): Promise<void> {
    this.gestureError.set('');
    try {
      await this.jobs.submitSolveIncremental(
        { animateurIds: [], jours: [], standIds: [] },
        undefined,
        this.jobs.solverBusy(),
      );
      this.repairSent.set(true);
    } catch (error) {
      this.gestureError.set(errorPrefix(error));
    }
  }

  /**
   * One gesture: busy while it runs, its refusal worded in the panel, and on
   * success what it did and what is left. A write to the plan asks the page
   * to re-read it; a lock does not — the padlocks come from the lock store.
   */
  private async write(
    gesture: SeatGesture,
    run: () => Promise<{ message: string; warning: string | null; holeOpened?: boolean }>,
  ): Promise<void> {
    this.busy.set(true);
    this.gestureError.set('');
    this.repairSent.set(false);
    try {
      const done = await run();
      const steps = nextSteps(gesture, done.holeOpened ?? false);
      this.outcome.set({ message: done.message, warning: done.warning, steps });
      if (steps.notify) {
        this.planChanged.emit();
      }
    } catch (error) {
      this.gestureError.set(errorPrefix(error));
      // The refusal may be « this seat changed under you »: re-read the plan.
      if (gesture !== 'lock' && gesture !== 'unlock') {
        this.planChanged.emit();
      }
    } finally {
      this.busy.set(false);
    }
  }
}

/**
 * What the server wants read about a lock just laid — seats it freezes that
 * already break a hard rule — as one sentence of the panel, which the
 * notification journal never sees. Null when it said nothing.
 */
function lockWarning(avertissements: readonly Avertissement[]): string | null {
  return avertissements.length > 0
    ? avertissements.map((avertissement) => avertissement.message).join(' ')
    : null;
}
