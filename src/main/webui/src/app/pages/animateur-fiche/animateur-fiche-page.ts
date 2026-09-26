import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  ViewEncapsulation,
  afterNextRender,
  computed,
  effect,
  inject,
  linkedSignal,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom, map } from 'rxjs';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { PlanningApi } from '../../core/api/planning-api';
import { PostesApi } from '../../core/api/postes-api';
import { ApiError } from '../../core/api.service';
import { statutDemandeLabel } from '../../core/demande-echange-labels';
import { errorMessage, errorPrefix } from '../../core/error-message';
import {
  AnimateurProfile,
  ConfirmationView,
  ConstraintView,
  ContrainteAdHoc,
  DayOff,
  DaySeat,
  NiveauCompetence,
  PlanningEvenement,
  SeveriteFragilite,
  VerrouillagePlanning,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { animateurName } from '../../core/reference-labels';
import { errorText, retainedValue } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { typologieLabels } from '../../core/typologie-colors';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { openBenchDialog } from '../../shared/siege-panel/bench-dialog';
import { catalogueByName } from '../../shared/siege-panel/seat';
import { StatusMessage } from '../../shared/status-message';
import {
  AdHocConstraintFormData,
  AdHocConstraintFormDialog,
} from '../ad-hoc-constraints/ad-hoc-constraint-form-dialog';
import { AnimateurFormData, AnimateurFormDialog } from '../animateurs/animateur-form-dialog';
import {
  firstDay,
  needsConfirmations,
  needsSeats,
  neighbours,
  nomAffiche,
  readRosterView,
  rosterLinkParams,
  rosterOrder,
  seatCounts,
} from '../animateurs/animateur-roster';
import { resumeRelance } from '../animateurs/relance-resume';
import { formatColonne, indicateursFiche, libelleColonne } from '../equite/equite';
import {
  CompetenceDraft,
  CompetenceSource,
  FicheSection,
  adjustmentScope,
  adjustmentTypeLabel,
  applyCompetenceDrafts,
  availabilityStrip,
  carriedDrafts,
  competenceDrafts,
  competenceRows,
  competencesChanged,
  confirmationLabel,
  daysOffOutsideEvent,
  initialSections,
  niveauLabel,
  readSection,
  regimeChanges,
  regimeLabel,
  seatLabel,
  seatsOnDay,
  shortTime,
  upcomingCount,
} from './animateur-fiche';
import { AnimateurTimeline, exportFilename } from './animateur-timeline';
import { EquiteRadar } from './equite-radar';
import { OPTIONAL_AXES, readOptionalAxes } from './radar';

/** The levels the inline editor offers, « aucune » included. */
const NIVEAUX: readonly (NiveauCompetence | null)[] = [null, 'DEBUTANT', 'AUTONOME', 'REFERENT'];

/** A seat an off day freed, and who was placed on it from here since. */
interface FreedSeatLine {
  seat: DaySeat;
  placedName: string | null;
}

/**
 * « Fiche animateur » (`/animateurs/:id`): one person on one page, and every
 * gesture on them from it — the hub of a person rather than a read-out.
 *
 * The head carries the actions (the espace link, the planning mailed, the
 * reminder, the PDF and the calendar, the whole planning locked, an
 * adjustment, the fiche edited) and « précédent / suivant » through the list
 * of the Animateurs page as the reader filtered it — its view travels in this
 * page's URL. Below, seven foldable sections, the first three open: identity,
 * availability — a strip whose day, clicked, is made unavailable at once and
 * frees the seats of that day for somebody else —, the planning read on time,
 * then load and fairness with the radar, the competences edited in place, the
 * fragility and the follow-up.
 *
 * One read, `GET /api/animateurs/{id}/fiche`, assembled server-side from the
 * services the specialised screens read, so the figures here are theirs.
 */
@Component({
  selector: 'app-animateur-fiche-page',
  imports: [
    DatePipe,
    DecimalPipe,
    PercentPipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
    StatusMessage,
    AnimateurTimeline,
    EquiteRadar,
  ],
  templateUrl: './animateur-fiche-page.html',
  styleUrls: ['../../../styles/animateur-form.css', './animateur-fiche-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnimateurFichePage {
  private readonly animateursApi = inject(AnimateursApi);
  private readonly planningApi = inject(PlanningApi);
  private readonly postesApi = inject(PostesApi);
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly store = inject(ReferenceDataStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly jobs = inject(SolverJobService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly planningState = inject(PlanningStateService);
  private readonly verrous = inject(VerrouillageStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The id in the address; a change of person reloads the fiche. */
  protected readonly animateurId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: this.route.snapshot.paramMap.get('id') ?? '' },
  );

  private readonly profileData = resource({
    params: () => ({ id: this.animateurId() }),
    loader: ({ params }) => this.animateursApi.profile(params.id),
  });
  protected readonly profile = retainedValue(this.profileData, this.animateurId);
  protected readonly loading = this.profileData.isLoading;
  /** An unknown id gets a sentence of its own, not the generic failure. */
  protected readonly notFound = computed(() => {
    const error = this.profileData.error();
    return error instanceof ApiError && error.status === 404;
  });
  protected readonly error = errorText(this.profileData, (error) =>
    error instanceof ApiError && error.status === 404 ? '' : errorPrefix(error),
  );

  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly fullName = computed(() => {
    const animateur = this.profile()?.animateur;
    if (!animateur) {
      return '';
    }
    return nomAffiche(animateur) || animateur.id;
  });

  /* ------------------------ the list it came from ------------------------ */

  /** The Animateurs page's view, carried in this page's URL: what « précédent / suivant » walks. */
  private readonly rosterView = readRosterView(this.route.snapshot.queryParamMap);
  protected readonly rosterParams = rosterLinkParams(this.rosterView);
  /** The list was narrowed or sorted: « 3 / 42 » then says which list is walked. */
  protected readonly rosterNarrowed = Object.keys(this.rosterParams).length > 0;
  private readonly rosterConfirmations = signal<ReadonlyMap<string, ConfirmationView>>(new Map());
  private readonly lastPublishedAt = signal<string | null>(null);
  private readonly rosterSeats = signal<ReadonlyMap<string, number> | null>(null);
  private readonly roster = computed(() =>
    rosterOrder(this.store.animateurs(), this.rosterView, {
      confirmations: this.rosterConfirmations(),
      lastPublishedAt: this.lastPublishedAt(),
      typologies: typologieLabels(this.store.typologies()),
      now: new Date(),
      premierJour: firstDay(this.store.creneaux().map((creneau) => creneau.date)),
      postes: this.rosterSeats(),
    }),
  );
  protected readonly neighbours = computed(() => neighbours(this.roster(), this.animateurId()));
  protected readonly rosterSize = computed(() => this.roster().length);

  /* ------------------------------ sections ------------------------------ */

  private readonly namedSection = readSection(
    this.route.snapshot.queryParamMap.get('section') ?? this.route.snapshot.fragment,
  );
  /** The sections unfolded; the costly ones only draw once open. */
  protected readonly openSections = signal<ReadonlySet<FicheSection>>(
    initialSections(this.namedSection),
  );

  protected isOpen(section: FicheSection): boolean {
    return this.openSections().has(section);
  }

  protected onToggle(section: FicheSection, event: Event): void {
    const open = (event.target as HTMLDetailsElement).open;
    this.openSections.update((current) => {
      if (current.has(section) === open) {
        return current;
      }
      const next = new Set(current);
      if (open) {
        next.add(section);
      } else {
        next.delete(section);
      }
      return next;
    });
  }

  /* ------------------------------ identity ------------------------------ */

  protected readonly regimeChanges = computed(() => {
    const profile = this.profile();
    return profile ? regimeChanges(profile) : false;
  });
  protected readonly espaceLink = computed(() => {
    const token = this.profile()?.animateur.accessToken;
    return token ? `${globalThis.location?.origin ?? ''}/animateur/${token}` : null;
  });
  /** The whole planning locked on this person, the lock « Libérer tout » lifts. */
  protected readonly wholeLock = computed<VerrouillagePlanning | null>(
    () => this.profile()?.verrous.find((verrou) => verrou.type === 'ANIMATEUR') ?? null,
  );

  /* ----------------------------- availability ----------------------------- */

  protected readonly strip = computed(() => {
    const profile = this.profile();
    return profile ? availabilityStrip(profile) : [];
  });
  protected readonly offOutsideEvent = computed(() => {
    const profile = this.profile();
    return profile ? daysOffOutsideEvent(profile) : [];
  });
  protected readonly forcedOffAdjustments = computed(
    () =>
      this.profile()?.ajustements.filter(
        (ajustement) => ajustement.type === 'INDISPONIBILITE_FORCEE',
      ) ?? [],
  );
  /** The day of the strip clicked: its panel says what marking it would free. */
  protected readonly selectedDay = linkedSignal<string, string | null>({
    source: this.animateurId,
    computation: () => null,
  });
  protected readonly selectedDayInfo = computed(() => {
    const date = this.selectedDay();
    const profile = this.profile();
    if (!date || !profile) {
      return null;
    }
    const jour = this.strip().find((day) => day.date === date);
    const seats = seatsOnDay(profile, date);
    return {
      date,
      declaredOff: jour?.declaredOff ?? false,
      forcedOff: jour?.forcedOff ?? false,
      ahead: seats.filter((seat) => !seat.passe && !seat.verrouille),
      locked: seats.filter((seat) => !seat.passe && seat.verrouille),
      started: seats.filter((seat) => seat.passe).length,
    };
  });
  /** What the last gesture on the strip did, kept while the same day is on show. */
  protected readonly dayOutcome = linkedSignal<string, DayOff | null>({
    source: this.animateurId,
    computation: () => null,
  });
  protected readonly freedLines = linkedSignal<DayOff | null, FreedSeatLine[]>({
    source: this.dayOutcome,
    computation: (outcome) =>
      (outcome?.freedSeats ?? []).map((seat) => ({ seat, placedName: null })),
  });
  protected readonly dayBusy = signal(false);
  protected readonly dayError = signal('');
  /**
   * Bumped after every gesture here that may move the persisted plan — a day
   * of the strip, a seat filled from the bench: the open « Planning » section
   * reads the plan again rather than draw the seats it just lost.
   */
  protected readonly planVersion = signal(0);

  /* ------------------------------ equity ------------------------------ */

  protected readonly equityLine = computed(() => this.profile()?.equite.lignes[0] ?? null);
  protected readonly indicators = computed(() => {
    const profile = this.profile();
    return profile ? indicateursFiche(profile.equite, this.equityLine()) : [];
  });
  protected readonly optionalAxes = OPTIONAL_AXES;
  /**
   * The radar's optional axes, in canonical order — `?axes=`. Read from the
   * address on arrival, and back to none on « précédent / suivant »: the
   * neighbours' links do not carry them, and a view chosen for one person
   * is not the next one's.
   */
  protected readonly radarAxes = linkedSignal<string, string[]>({
    source: this.animateurId,
    computation: (_id, previous) =>
      previous ? [] : readOptionalAxes(this.route.snapshot.queryParamMap.get('axes')),
  });
  /** The second person drawn on the radar — `?comparer=`; dropped on a change of person, as the axes are. */
  protected readonly comparedId = linkedSignal<string, string>({
    source: this.animateurId,
    computation: (_id, previous) =>
      previous ? '' : (this.route.snapshot.queryParamMap.get('comparer') ?? ''),
  });
  /** The whole Équité report, read once the section is open: the other lines « Comparer avec… » offers. */
  private readonly fullEquity = resource({
    params: () =>
      this.isOpen('equite') && this.profile()?.planCalcule ? { id: this.animateurId() } : undefined,
    loader: () => this.planningApi.equityReport(),
  });
  protected readonly comparables = computed(() =>
    this.fullEquity.hasValue()
      ? this.fullEquity
          .value()
          .lignes.filter((ligne) => ligne.animateurId !== this.animateurId())
          .sort((left, right) => left.nom.localeCompare(right.nom))
      : [],
  );
  protected readonly comparedRow = computed(
    () => this.comparables().find((ligne) => ligne.animateurId === this.comparedId()) ?? null,
  );

  /* ---------------------------- competences ---------------------------- */

  protected readonly competences = computed(() => {
    const profile = this.profile();
    return profile ? competenceRows(profile.animateur, this.store.typologies()) : [];
  });
  /**
   * The inline editor's lines. A new read of the fiche resets them only when
   * they were left untouched, were just saved, or belong to another person:
   * the gestures of the other sections reload the fiche, and must not wipe
   * edits nobody saved yet.
   */
  protected readonly competenceLines = linkedSignal<CompetenceSource, CompetenceDraft[]>({
    source: () => ({
      animateurId: this.animateurId(),
      animateur: this.profile()?.animateur ?? null,
      drafts: competenceDrafts(this.competences()),
    }),
    computation: (source, previous) => carriedDrafts(source, previous),
  });
  protected readonly competencesModified = computed(() => {
    const animateur = this.profile()?.animateur;
    return animateur ? competencesChanged(animateur, this.competenceLines()) : false;
  });
  /** The categories not on a line yet, for « Ajouter une typologie ». */
  protected readonly addableTypologies = computed(() => {
    const present = new Set(this.competenceLines().map((line) => line.typologieId));
    return this.store.typologies().filter((typologie) => !present.has(typologie.id));
  });
  protected readonly niveaux = NIVEAUX;
  protected readonly competencesBusy = signal(false);

  protected readonly upcoming = computed(() => {
    const profile = this.profile();
    return profile ? upcomingCount(profile) : 0;
  });

  /** One gesture of the head at a time; its name, while it runs. */
  protected readonly busy = signal<string | null>(null);

  protected readonly regimeLabel = regimeLabel;
  protected readonly niveauLabel = niveauLabel;
  protected readonly formatColonne = formatColonne;
  protected readonly libelleColonne = libelleColonne;
  protected readonly adjustmentTypeLabel = adjustmentTypeLabel;
  protected readonly adjustmentScope = adjustmentScope;
  protected readonly confirmationLabel = confirmationLabel;
  protected readonly statutDemandeLabel = statutDemandeLabel;
  protected readonly shortTime = shortTime;
  protected readonly seatLabel = seatLabel;

  constructor() {
    // The game categories name the appreciations, the roster orders
    // « précédent / suivant » — the minors on the edition's first day, read
    // from its timeslots: read once if no page has yet.
    if (
      this.store.typologies().length === 0 ||
      this.store.animateurs().length === 0 ||
      this.store.creneaux().length === 0
    ) {
      void this.store.reload(['typologies', 'animateurs', 'creneaux']).catch(() => undefined);
    }
    if (needsConfirmations(this.rosterView)) {
      void this.loadRosterConfirmations();
    }
    if (needsSeats(this.rosterView)) {
      void this.loadRosterSeats();
    }
    // The radar's view is this page's to write; the list's view and the
    // section named on arrival are left as they came.
    keepViewInQueryParams(() => ({
      axes: optionalParam(this.radarAxes().join(',')),
      comparer: optionalParam(this.comparedId()),
    }));
    // A section named by the address is brought into view once the fiche is drawn.
    const named = this.namedSection;
    if (named) {
      const watch = effect(() => {
        if (this.profile()) {
          untracked(() =>
            afterNextRender(
              () =>
                this.host.nativeElement
                  .querySelector(`#fiche-section-${named}`)
                  ?.scrollIntoView?.({ block: 'start' }),
              { injector: this.injector },
            ),
          );
          watch.destroy();
        }
      });
    }
  }

  private async loadRosterConfirmations(): Promise<void> {
    try {
      const [confirmations, synthese] = await Promise.all([
        this.animateursApi.confirmations(),
        this.animateursApi.syntheseConfirmations(),
      ]);
      this.rosterConfirmations.set(
        new Map(confirmations.map((confirmation) => [confirmation.animateurId, confirmation])),
      );
      this.lastPublishedAt.set(synthese.dernierePublicationLe ?? null);
    } catch {
      // Without the answers the acknowledgement filters keep nobody: the
      // neighbours are then simply absent, never wrong.
    }
  }

  /** The seats of the persisted plan, for a list sorted on its « Postes » column. */
  private async loadRosterSeats(): Promise<void> {
    try {
      this.rosterSeats.set(seatCounts(await this.planningState.loadForDisplay()));
    } catch {
      // Unreadable, the column sorts as if nobody were seated: the neighbours
      // then follow the ids, as the list itself would.
    }
  }

  protected reload(): void {
    this.profileData.reload();
  }

  /* ------------------------------ the head ------------------------------ */

  /** The Animateurs page's own form. */
  protected edit(): void {
    const animateur = this.profile()?.animateur;
    if (!animateur) {
      return;
    }
    this.dialog
      .open<AnimateurFormDialog, AnimateurFormData, boolean>(AnimateurFormDialog, {
        data: { animateur },
        width: '44rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.reload();
        }
      });
  }

  /** The espace link, what the PDF prints, to paste in a message. */
  protected async copyEspaceLink(): Promise<void> {
    const lien = this.espaceLink();
    if (!lien) {
      return;
    }
    try {
      await navigator.clipboard.writeText(lien);
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopie:Lien de l'espace animateur copié.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch {
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopieEchec:Impossible de copier le lien`,
        message: lien,
        variant: 'warning',
      });
    }
  }

  /**
   * « Envoyer son planning » : the individual publication mail — the same
   * send as the MCP tool `envoyer_planning_animateur`, built server-side from
   * the persisted plan. Real mail to a real person: asked first.
   */
  protected async sendPlanning(): Promise<void> {
    const profile = this.profile();
    if (!profile) {
      return;
    }
    const nom = this.fullName();
    const confirmed = await this.confirm.ask({
      title: $localize`:@@fiche.envoyer.titre:Envoyer son planning à ${nom}:nom: ?`,
      message: $localize`:@@fiche.envoyer.message:Un e-mail part maintenant avec son planning en PDF et le lien de son espace.`,
      confirmLabel: $localize`:@@fiche.envoyer.confirm:Envoyer`,
    });
    if (!confirmed) {
      return;
    }
    await this.run('envoyer', async () => {
      const id = profile.animateur.id;
      await this.planningApi.sendToAnimateur(id);
      // The name is shown, never logged: the journal of notifications
      // outlives the session in this browser (docs/rgpd.md §7).
      this.notifications.notify({
        title: $localize`:@@fiche.envoi.succes:Planning envoyé`,
        message: nom,
        messageJournal: id,
        variant: 'success',
      });
      this.reload();
    });
  }

  /** « Relancer » : the confirmation reminder the night would send, for this one person. */
  protected async remind(): Promise<void> {
    const profile = this.profile();
    if (!profile) {
      return;
    }
    const nom = this.fullName();
    const confirmed = await this.confirm.ask({
      title: $localize`:@@fiche.relancer.titre:Relancer ${nom}:nom: maintenant ?`,
      message: $localize`:@@animateurs.relancer.message:Chacun recevra un e-mail lui demandant de confirmer son planning. Personne n'est relancé deux fois pour une même publication.`,
      confirmLabel: $localize`:@@animateurs.relancer.confirm:Envoyer`,
    });
    if (!confirmed) {
      return;
    }
    await this.run('relancer', async () => {
      const rapport = await this.animateursApi.remind([profile.animateur.id]);
      const resume = resumeRelance(rapport, () => nom);
      this.notifications.notify({
        title: resume.titre,
        message: resume.details ?? '',
        messageJournal: resume.detailsJournal ?? '',
        variant: resume.variant,
      });
      this.reload();
    });
  }

  protected exportPdf(): Promise<void> {
    return this.export('pdf', 'application/pdf');
  }

  protected exportIcs(): Promise<void> {
    return this.export('ics', 'text/calendar');
  }

  /** The individual PDF or calendar, built server-side from the plan on display. */
  private async export(format: 'pdf' | 'ics', contentType: string): Promise<void> {
    const id = this.animateurId();
    await this.run(format, async () => {
      const planning = await this.planningState.require();
      this.notifications.notify({
        title: await this.planningApi.exportForAnimateur(
          format,
          id,
          exportFilename(this.fullName() || id, format),
          planning,
          contentType,
        ),
        variant: 'success',
      });
    });
  }

  /** « Verrouiller tout son planning » : the next solve leaves every seat of this person as it is. */
  protected async lockAll(): Promise<void> {
    const animateurId = this.animateurId();
    await this.run('verrouiller', async () => {
      await this.verrous.create({ type: 'ANIMATEUR', animateurId });
      this.notifications.notify({
        title: $localize`:@@fiche.verrouiller.fait:Planning verrouillé : le prochain calcul n'y touchera pas.`,
        variant: 'success',
        timeout: 4000,
      });
      this.reload();
    });
  }

  /** « Libérer tout son planning » : the whole-planning lock lifted, the other locks kept. */
  protected async unlockAll(): Promise<void> {
    const lock = this.wholeLock();
    if (!lock) {
      return;
    }
    await this.run('verrouiller', async () => {
      await this.verrous.remove(lock.id);
      this.notifications.notify({
        title: $localize`:@@fiche.deverrouiller.fait:Planning déverrouillé : le prochain calcul peut à nouveau le changer.`,
        variant: 'success',
        timeout: 4000,
      });
      this.reload();
    });
  }

  /**
   * « Poser un ajustement » : the adjustment form, this person already named —
   * the form, not the network view of the adjustments screen.
   */
  protected async addAdjustment(): Promise<void> {
    const animateurId = this.animateurId();
    await this.run('ajustement', async () => {
      await this.store.reload(['creneaux', 'stands', 'animateurs']);
      const contrainte: ContrainteAdHoc = {
        id: '',
        type: 'INDISPONIBILITE_FORCEE',
        animateursConcernes: [{ id: animateurId }],
        creneau: null,
        stand: null,
        raison: '',
      };
      const saved = await firstValueFrom(
        this.dialog
          .open<AdHocConstraintFormDialog, AdHocConstraintFormData, boolean>(
            AdHocConstraintFormDialog,
            { data: { contrainte }, width: '40rem', maxWidth: '95vw', autoFocus: 'first-tabbable' },
          )
          .afterClosed(),
      );
      if (saved) {
        this.reload();
      }
    });
  }

  /** One gesture of the head: its button busy, its failure in a snack bar. */
  private async run(gesture: string, action: () => Promise<void>): Promise<void> {
    this.busy.set(gesture);
    try {
      await action();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    } finally {
      this.busy.set(null);
    }
  }

  /* --------------------------- the strip's day --------------------------- */

  protected selectDay(date: string): void {
    this.dayError.set('');
    if (this.selectedDay() === date) {
      this.selectedDay.set(null);
      return;
    }
    this.selectedDay.set(date);
    if (this.dayOutcome()?.date !== date) {
      this.dayOutcome.set(null);
    }
  }

  /**
   * « Indisponible ce jour » : the day written, and the seats of that day still
   * ahead freed in the same call — each then offered to a replacement below.
   */
  protected async markDayOff(date: string): Promise<void> {
    const id = this.animateurId();
    await this.dayGesture(async () => {
      const outcome = await this.animateursApi.markDayOff(id, date);
      // « Suivant » pressed during the call: the answer is the previous person's.
      if (this.animateurId() === id) {
        this.dayOutcome.set(outcome);
      }
    });
  }

  /** « Annuler l'absence » : the day available again; nobody is unseated for it. */
  protected async cancelDayOff(date: string): Promise<void> {
    const id = this.animateurId();
    await this.dayGesture(async () => {
      await this.animateursApi.cancelDayOff(id, date);
      if (this.animateurId() === id) {
        this.dayOutcome.set(null);
      }
    });
  }

  private async dayGesture(write: () => Promise<void>): Promise<void> {
    this.dayBusy.set(true);
    this.dayError.set('');
    try {
      await write();
      // The persisted plan moved: the next read of it must not be a copy.
      this.planningState.set(null);
      this.planVersion.update((version) => version + 1);
      this.reload();
      void this.store.reload(['animateurs']).catch(() => undefined);
      // The button pressed gives way to its opposite: the focus goes to the
      // day's heading rather than falling to the page.
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('#fiche-jour-titre')?.focus(),
        { injector: this.injector },
      );
    } catch (error) {
      this.dayError.set(errorPrefix(error));
    } finally {
      this.dayBusy.set(false);
    }
  }

  /**
   * « Qui peut tenir ce siège ? » on a seat the off day freed: the bench of
   * the Siège panel, in its dialog; its « Placer » seats the person chosen
   * and, the box left ticked, locks them there.
   */
  protected async whoCanHold(line: FreedSeatLine): Promise<void> {
    this.dayError.set('');
    let planning: PlanningEvenement;
    let catalogue: ReadonlyMap<string, ConstraintView> | null;
    try {
      [planning, catalogue] = await Promise.all([
        this.planningState.loadForDisplay(),
        this.constraintsApi
          .catalogue()
          .then((view) => catalogueByName(view.contraintes))
          .catch(() => null),
      ]);
    } catch (error) {
      this.dayError.set(errorPrefix(error));
      return;
    }
    const seat = line.seat;
    const choice = await firstValueFrom(
      openBenchDialog(this.dialog, {
        posteId: seat.posteId,
        creneauId: seat.creneauId,
        standId: seat.standId,
        title: `${seatLabel(seat)} · ${seat.date}`,
        animateurs: planning.animateurs ?? [],
        catalogue,
        // The seat was just freed: the bench fills it.
        offerPlacement: true,
      }).afterClosed(),
    );
    if (!choice) {
      return;
    }
    const chosen = (planning.animateurs ?? []).find((each) => each.id === choice.animateurId);
    const nom = chosen ? animateurName(chosen) : choice.animateurId;
    this.dayBusy.set(true);
    try {
      await this.postesApi.place(seat.posteId, choice.animateurId);
      this.planningState.set(null);
      this.planVersion.update((version) => version + 1);
      this.freedLines.update((lines) =>
        lines.map((each) =>
          each.seat.posteId === seat.posteId ? { ...each, placedName: nom } : each,
        ),
      );
      if (choice.keep) {
        await this.keepPlacement(choice.animateurId, seat.creneauId);
      }
    } catch (error) {
      this.dayError.set(errorPrefix(error));
    } finally {
      this.dayBusy.set(false);
    }
  }

  /** The placement is written: a failed lock is said, never undone into an unplacement. */
  private async keepPlacement(animateurId: string, creneauId: number): Promise<void> {
    try {
      await this.verrous.create({ type: 'ANIMATEUR_CRENEAU', animateurId, creneauId });
    } catch (error) {
      const cause = errorPrefix(error);
      this.dayError.set(
        $localize`:@@siege.placer.sansVerrou:Placé(e), mais le verrou n'a pas pu être posé : ${cause}:erreur:`,
      );
    }
  }

  /* ------------------------------- the radar ------------------------------- */

  protected toggleAxis(axis: string, checked: boolean): void {
    const current = new Set(this.radarAxes());
    if (checked) {
      current.add(axis);
    } else {
      current.delete(axis);
    }
    this.radarAxes.set(readOptionalAxes([...current].join(',')));
  }

  protected compareWith(animateurId: string | null): void {
    this.comparedId.set(animateurId ?? '');
  }

  /* ---------------------------- competences ---------------------------- */

  protected setLevel(typologieId: string, niveau: NiveauCompetence | null): void {
    this.competenceLines.update((lines) =>
      lines.map((line) => (line.typologieId === typologieId ? { ...line, niveau } : line)),
    );
  }

  /** The native select of a line: its empty option is « aucune appréciation ». */
  protected onLevelChange(typologieId: string, event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.setLevel(typologieId, value === '' ? null : (value as NiveauCompetence));
  }

  protected setWish(typologieId: string, wished: boolean): void {
    this.competenceLines.update((lines) =>
      lines.map((line) => (line.typologieId === typologieId ? { ...line, wished } : line)),
    );
  }

  protected addTypologie(typologieId: string | null): void {
    if (!typologieId) {
      return;
    }
    this.competenceLines.update((lines) => [
      ...lines,
      { typologieId, niveau: null, wished: false },
    ]);
  }

  protected typologieName(typologieId: string): string {
    return (
      this.store.typologies().find((typologie) => typologie.id === typologieId)?.label ||
      typologieId
    );
  }

  protected resetCompetences(): void {
    this.competenceLines.set(competenceDrafts(this.competences()));
  }

  /** Saves the two lists through the fiche's own write, its concurrent-edit guard included. */
  protected async saveCompetences(): Promise<void> {
    const animateur = this.profile()?.animateur;
    if (!animateur) {
      return;
    }
    this.competencesBusy.set(true);
    try {
      const saved = await this.crud.save(
        'animateurs',
        { ...animateur, ...applyCompetenceDrafts(this.competenceLines()) },
        animateur.id,
        $localize`:@@animateurs.entityLabel:Animateur`,
        { text: animateurName(animateur), personal: true },
      );
      if (saved) {
        this.reload();
      }
    } finally {
      this.competencesBusy.set(false);
    }
  }

  /* ------------------------------ fragility ------------------------------ */

  /** « Verrouiller » a fragile seat: this person kept on that timeslot by the next solve. */
  protected async lockSeat(creneauId: number): Promise<void> {
    const animateurId = this.animateurId();
    await this.run('verrouiller', async () => {
      await this.verrous.create({ type: 'ANIMATEUR_CRENEAU', animateurId, creneauId });
      this.notifications.notify({
        title: $localize`:@@siege.verrouiller.fait:Verrouillé : ce siège ne bougera plus au prochain calcul.`,
        variant: 'success',
        timeout: 4000,
      });
      this.reload();
    });
  }

  protected gapClass(gap: number | null): string {
    if (gap === null || Math.abs(gap) < 0.005) {
      return '';
    }
    return gap > 0 ? 'fiche-ecart-positif' : 'fiche-ecart-negatif';
  }

  protected severityClass(severite: SeveriteFragilite): string {
    return `fiche-severite fiche-severite-${severite.toLowerCase()}`;
  }

  protected severityLabel(severite: SeveriteFragilite): string {
    switch (severite) {
      case 'CRITIQUE':
        return $localize`:@@fragilite.severite.critique:Critique`;
      case 'ELEVEE':
        return $localize`:@@fragilite.severite.elevee:Élevée`;
      case 'MODEREE':
        return $localize`:@@fragilite.severite.moderee:Modérée`;
    }
  }

  /** The hours of the equity line, for the head's summary; nothing without a seat. */
  protected hours(profile: AnimateurProfile): number | null {
    return profile.equite.lignes[0]?.heuresTotal ?? null;
  }
}
