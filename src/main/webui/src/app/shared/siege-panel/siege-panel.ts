// The Siège panel: one seat of the plan on screen, and every gesture on it,
// beside the rendering that showed it — not a full-screen dialog in front of
// it. Opened by a click on any cell of the Planning page (a name or a free
// seat of the calendar, a shift of the rail, a stand of the map), it reads the
// plan the page already loaded: nothing is fetched per cell. Its two « et si »
// gestures (ADR 0127's « simuler » and « expliquer » on the screen) are
// « Simuler un changement » — the repair suggestions, a move, the before and
// after of the one chosen, then « Appliquer » — and « Expliquer »; both read
// only, a lecteur's too. The writes that need no simulation (free, absent,
// lock) stay beside them.

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
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { PostesApi } from '../../core/api/postes-api';
import { errorPrefix } from '../../core/error-message';
import { JourJService } from '../../core/jour-j.service';
import {
  ContrainteImpact,
  PlanningEvenement,
  PosteAffectation,
  SuggestionReparation,
  SuggestionsReparation,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SessionStore } from '../../core/session.store';
import { errorText } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import {
  animateurName,
  meilleuresSuggestions,
  nomAnimateur,
  suggestionsTronquees,
} from '../affectation-explanation-rules';
import { ConfirmService } from '../confirm-dialog';
import { moveTargets, resumeDeplacement, scoreLabel } from '../deplacement';
import { openMoveDialog } from '../deplacement-dialog';
import { StatusMessage } from '../status-message';
import { lockWarning, SeatPlacement } from './seat-placement';
import {
  catalogueByName,
  levelLabel,
  lockLabel,
  movePreview,
  NextSteps,
  nextSteps,
  qualityWarning,
  ruleLabel,
  SeatGesture,
  SeatLock,
  SeatPreview,
  seatHours,
  seatLocks,
  scoreEffect,
  suggestionPreview,
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
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    StatusMessage,
  ],
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

  private readonly jourJ = inject(JourJService);
  private readonly postesApi = inject(PostesApi);
  private readonly placement = inject(SeatPlacement);
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
  /** A lecteur (ADR 0099) simulates and explains, and applies nothing: the server refuses it anyway. */
  protected readonly lectureSeule = inject(SessionStore).lectureSeule;

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
    () => this.busy() || this.editingLocked() || this.frozen() || this.lectureSeule(),
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

  /** « Expliquer » is open: closed again when another seat is opened. */
  protected readonly explaining = linkedSignal<string | null, boolean>({
    source: this.posteId,
    computation: () => false,
  });

  /**
   * « Expliquer » in sentences: the rules the holder breaks on this seat, read
   * on the persisted plan under the edition's rules — never the copy on
   * screen, which carries no disabled constraint and no weight. Asked when the
   * gesture is opened — one call per seat explained, never one per cell — and
   * again whenever the page re-reads the plan after a gesture.
   */
  protected readonly explanation = resource({
    params: () => {
      const seat = this.seat();
      return seat?.animateur && this.planning() && this.explaining()
        ? { posteId: seat.id, holderId: seat.animateur.id }
        : undefined;
    },
    loader: ({ params }) => this.postesApi.explanation(params.posteId),
  });
  protected readonly explanationError = errorText(this.explanation);

  /** « Simuler un changement » is open: closed again when another seat is opened. */
  protected readonly simulating = linkedSignal<string | null, boolean>({
    source: this.posteId,
    computation: () => false,
  });
  /* The suggestions are searched on demand — one score analysis per candidate server-side. */
  protected readonly suggestions = linkedSignal<string | null, SuggestionsReparation | null>({
    source: this.posteId,
    computation: () => null,
  });
  /** A suggestion or a move asked of the server: one simulation at a time. */
  protected readonly simulationLoading = signal(false);
  /** The change simulated last, its before and after, until it is applied or dropped. */
  protected readonly preview = linkedSignal<string | null, SeatPreview | null>({
    source: this.posteId,
    computation: () => null,
  });
  /** On an empty seat, « La garder au prochain calcul » : the placement applied is then locked. */
  protected readonly keepPlacement = linkedSignal<string | null, boolean>({
    source: this.posteId,
    computation: () => true,
  });
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

  protected simulateLabel(suggestion: SuggestionReparation): string {
    const nom = this.name(suggestion.animateurId);
    return $localize`:@@siege.simuler.suggestion.label:Simuler : ${nom}:nom: sur ce siège`;
  }

  protected effectText(preview: SeatPreview): string {
    return scoreEffect(preview.delta);
  }

  protected scores(preview: SeatPreview): string {
    const avant = scoreLabel(preview.avant);
    const apres = scoreLabel(preview.apres);
    return $localize`:@@siege.simuler.scores:Score avant : ${avant}:avant: · après : ${apres}:apres:`;
  }

  /* ----------------------------- gestures ----------------------------- */

  /** « Expliquer » : opened, the explanation is read; closed, it is dropped. */
  protected toggleExplanation(): void {
    this.explaining.update((open) => !open);
  }

  /**
   * « Simuler un changement » : opened, the repair suggestions are searched —
   * on the persisted plan prepared server-side, as the write that follows
   * reads it: the constraints switched off, the weights and the quotas of the
   * edition, not a bare copy of the plan on screen.
   */
  protected async toggleSimulation(): Promise<void> {
    const open = !this.simulating();
    this.simulating.set(open);
    if (!open) {
      this.preview.set(null);
      return;
    }
    const seat = this.seat();
    if (!seat || this.suggestions()) {
      return;
    }
    await this.simulate(async () => {
      this.suggestions.set(await this.postesApi.suggestions(seat.id));
    });
  }

  /** A suggestion, before and after: already scored by the search, nothing more to ask. */
  protected simulateSuggestion(suggestion: SuggestionReparation): void {
    const found = this.suggestions();
    if (!found) {
      return;
    }
    this.preview.set(
      suggestionPreview(found, suggestion, this.name(suggestion.animateurId), this.catalogue()),
    );
  }

  /**
   * « Vers un autre siège… » : the move dialog of the day views chooses the
   * seat, the server scores the move — nothing written until « Appliquer ».
   */
  protected async simulateMove(): Promise<void> {
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
            this.verrous.isStandVerrouille(poste.stand?.id, poste.creneau?.date) ||
            this.verrous.isAnimateurCreneauVerrouille(poste.animateur?.id, poste.creneau?.id) ||
            this.verrous.estJourVerrouille(poste.creneau?.date),
        ),
        targetLabel: $localize`:@@calendarDay.deplacer.cible:Vers quel siège ?`,
      }).afterClosed(),
    );
    if (!choice) {
      return;
    }
    await this.simulate(async () => {
      const simulation = await this.postesApi.simulate(seat.id, { posteId: choice.target });
      this.preview.set(movePreview(simulation, (id) => this.name(id)));
    });
  }

  /** One read of « Simuler un changement » : its refusal worded in the panel. */
  private async simulate(run: () => Promise<void>): Promise<void> {
    this.simulationLoading.set(true);
    this.gestureError.set('');
    try {
      await run();
    } catch (error) {
      this.gestureError.set(errorPrefix(error));
    } finally {
      this.simulationLoading.set(false);
    }
  }

  protected dropPreview(): void {
    this.preview.set(null);
  }

  /**
   * « Appliquer » : the change simulated, written — through the very routes a
   * move, a hand-over and a placement always took, so the server holds it to
   * the same guards (a solve under way, a lock, the frozen past, the holder
   * shown as its precondition) whatever the simulation said.
   */
  protected async applyPreview(): Promise<void> {
    const seat = this.seat();
    const preview = this.preview();
    if (!seat?.creneau || !preview) {
      return;
    }
    const holderId = seat.animateur?.id ?? null;
    const done = (): void => {
      this.preview.set(null);
      this.suggestions.set(null);
      this.simulating.set(false);
    };
    const target = preview.posteCibleId;
    if (target && holderId) {
      await this.write('move', async () => {
        const simulation = await this.postesApi.move(seat.id, { posteId: target }, holderId);
        done();
        return {
          message: resumeDeplacement(simulation, (id) => this.name(id)).title,
          warning: qualityWarning(simulation.delta),
          // Moved onto a free seat: the one it left is empty now.
          holeOpened: simulation.posteCibleId !== null && simulation.animateurCibleId === null,
        };
      });
      return;
    }
    const animateurId = preview.animateurId;
    if (!animateurId) {
      return;
    }
    const nom = this.name(animateurId);
    if (holderId) {
      await this.write('replace', async () => {
        // The person shown: somebody else holding the seat by now is a 409.
        await this.postesApi.applyRepair(seat.id, animateurId, holderId);
        done();
        return {
          message: $localize`:@@siege.remplacer.fait:${nom}:nom: prend ce siège.`,
          warning: qualityWarning(preview.delta),
        };
      });
      return;
    }
    const creneauId = seat.creneau.id;
    const keep = this.keepPlacement();
    await this.write('place', async () => {
      const placed = await this.placement.place(seat.id, creneauId, { animateurId, keep }, nom);
      done();
      // The placement is written: say the lock is not, rather than undo it.
      if (placed.lockError) {
        this.gestureError.set(placed.lockError);
      }
      return placed;
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
      await this.postesApi.applyRepair(seat.id, null, holderId);
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

  /**
   * « Marquer absent » : the gesture of Aujourd'hui, from the seat — the same
   * service, on this timeslot alone. The holder is declared unavailable on it
   * and the seat freed; a timeslot under way is split at « now », the part
   * already held staying theirs (ADR 0066).
   */
  protected async markAbsent(): Promise<void> {
    const seat = this.seat();
    if (!seat?.animateur || !seat.creneau) {
      return;
    }
    const nom = this.holder();
    const confirmed = await this.confirm.ask({
      title: $localize`:@@siege.absent.titre:Marquer ${nom}:nom: absent ?`,
      message: $localize`:@@siege.absent.message:Une indisponibilité est posée sur ce créneau et le siège est libéré ; s'il est commencé, ce qui a été tenu reste à son nom.`,
      confirmLabel: $localize`:@@siege.absent:Marquer absent`,
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    const animateurId = seat.animateur.id;
    const { date, id: creneauId } = seat.creneau;
    await this.write('absent', async () => {
      const marquee = await this.jourJ.marquerAbsent(
        animateurId,
        '',
        date ?? undefined,
        undefined,
        creneauId,
      );
      return {
        message: $localize`:@@siege.absent.fait:${marquee.nomAffiche}:nom: est marqué absent sur ce créneau.`,
        warning: null,
        holeOpened: marquee.postesLiberes.length > 0,
      };
    });
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
   * « Voir tout le banc » of « Simuler un changement » : the bench of this
   * seat, in a dialog — everybody, the reasons of who cannot. On an empty seat
   * its « Placer » seats the person chosen and, the box left ticked, locks
   * them there so the next solve keeps the placement; on a held one, or to
   * whoever may not write, it only reads who could take the seat.
   */
  protected async whoCanHold(): Promise<void> {
    const seat = this.seat();
    const planning = this.planning();
    if (!seat?.creneau || !planning) {
      return;
    }
    const choice = await this.placement.choose({
      posteId: seat.id,
      creneauId: seat.creneau.id,
      standId: seat.stand?.id ?? null,
      title: `${this.standName()} · ${this.when()}`,
      animateurs: planning.animateurs ?? [],
      catalogue: this.catalogue(),
      offerPlacement: !seat.animateur && !this.writesDisabled(),
    });
    if (!choice || seat.animateur) {
      return;
    }
    const creneauId = seat.creneau.id;
    const nom = this.name(choice.animateurId);
    await this.write('place', async () => {
      const done = await this.placement.place(seat.id, creneauId, choice, nom);
      // The placement is written: say the lock is not, rather than undo it.
      if (done.lockError) {
        this.gestureError.set(done.lockError);
      }
      return done;
    });
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
