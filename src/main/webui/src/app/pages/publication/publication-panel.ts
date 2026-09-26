import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { ApercuPublication, DestinatairePublication } from '../../core/models';
import {
  libelleDernierePublication,
  libellePublier,
  raisonIndisponible,
  resumePublication,
} from '../../core/publication';
import { SolverJobService } from '../../core/solver-job.service';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  changeSummary,
  confirmationLabel,
  filterRecipients,
  readRecipientSort,
  sortRecipients,
  RecipientSort,
} from './publication-diff';
import { GelInvitation } from '../../core/gel-invitation';
import { PublicationSelection } from './publication-selection';

/**
 * « Publier — N personnes concernées »: the publication (issue #245) that mails
 * every animateur whose schedule changed and moves what their espace shows —
 * both effects said in one sentence next to the button, not in a tooltip —
 * with the review table of what each of them will read.
 *
 * <p>The panel owns the publication preview and re-reads it after every
 * publication; it tells the page what to put in its output panel
 * ({@link reported}) and that a publication left ({@link published}), so the
 * permanent table under it reads the new states. Who is held back is shared
 * with that table through {@link PublicationSelection}.</p>
 */
@Component({
  selector: 'app-publication-panel',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './publication-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicationPanel implements OnInit {
  private readonly planningApi = inject(PlanningApi);
  private readonly gelInvitation = inject(GelInvitation);
  private readonly confirm = inject(ConfirmService);
  private readonly selection = inject(PublicationSelection);
  private readonly params = currentViewParams();

  /** The line the page shows in its output panel: a sentence, a summary, or an error. */
  readonly reported = output<string>();
  /** A publication left: the states under the panel have moved. */
  readonly published = output<void>();

  /**
   * The narrower, per-edition lock — what the diffusion actions wait on. They
   * read the planning persisted for the edition on screen, so only a solve
   * writing to THAT edition can hand out a half-rewritten planning; a run on
   * another edition leaves this one exactly as it was saved. Same rule as the
   * data-entry screens (see `docs/decisions/0001-cloisonnement-par-edition.md`, §5).
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  /**
   * Publication state, read on demand — when the panel appears, when the list
   * is expanded and after each publication. Nothing polls: the count is
   * pending work to look at, not an alarm to be pushed.
   */
  readonly preview = signal<ApercuPublication | null>(null);
  protected readonly busy = signal(false);
  /**
   * How many people the send under way concerns, frozen when it starts: the
   * preview reloads at the end and would otherwise drop to zero mid-sentence.
   * Real information rather than a fabricated percentage — the server answers
   * once, when everything has gone out.
   */
  protected readonly recipientsInFlight = signal(0);
  protected readonly listOpen = signal(false);

  /**
   * The order and the filter of the review table live in the URL (ADR 0012):
   * « regarde cette liste, triée par ampleur » is a link, and a refresh in the
   * middle of a review restores the screen it interrupted.
   */
  protected readonly sortOrder = signal<RecipientSort>(readRecipientSort(this.params.get('tri')));
  protected readonly minorHidden = signal(this.params.get('mineurs') === 'masques');

  /** Who the admin took out of this send — shared with the table under the panel. */
  private readonly excluded = this.selection.excluded;

  protected readonly rows = computed(() =>
    sortRecipients(
      filterRecipients(this.preview()?.destinataires ?? [], this.minorHidden()),
      this.sortOrder(),
    ),
  );

  /** How many rows the filter is currently folding away — said, so nothing hides silently. */
  protected readonly minorCount = computed(
    () => (this.preview()?.destinataires ?? []).filter((each) => each.mineur).length,
  );

  protected readonly notifiedCount = computed(
    () => (this.preview()?.nombreConcernes ?? 0) - this.excluded().size,
  );
  protected readonly publishLabel = computed(() => libellePublier(this.preview()));
  /** What the button does, both effects in one sentence rather than in a tooltip. */
  protected readonly publishSentence = computed(() => {
    const count = this.notifiedCount();
    if (count <= 0) {
      return '';
    }
    return $localize`:@@diffuser.publier.phrase:Envoie leur nouveau planning aux ${count}:count: personne(s) dont il a changé et met à jour leur espace. Les autres ne reçoivent rien.`;
  });
  protected readonly unavailableReason = computed(() => raisonIndisponible(this.preview()));
  protected readonly lastPublication = computed(() =>
    libelleDernierePublication(this.preview(), intlLocale()),
  );
  /**
   * How many days would go out unread. Said, never enforced: publishing a day
   * nobody reviewed is an ordinary thing to do, doing it without knowing is
   * not. Silent when everything has been read — and before the first solve,
   * when there is nothing to read.
   */
  protected readonly relectureLabel = computed(() => {
    const nonValidees = this.preview()?.journeesNonValidees ?? 0;
    if (nonValidees === 0) {
      return '';
    }
    return $localize`:@@publication.journeesNonValidees:${nonValidees}:count: journée(s) que personne n'a marquée « relue et acceptée » partiraient avec cette publication.`;
  });

  protected readonly publishable = computed(() => {
    const preview = this.preview();
    return !!preview && this.notifiedCount() > 0 && !preview.solveEnCours && !preview.planVide;
  });

  constructor() {
    keepViewInQueryParams(() => ({
      tri: optionalParam(this.sortOrder() === 'nom' ? null : this.sortOrder()),
      mineurs: this.minorHidden() ? 'masques' : null,
    }));
  }

  ngOnInit(): void {
    void this.reloadPreview();
  }

  protected isExcluded(animateurId: string): boolean {
    return this.selection.isExcluded(animateurId);
  }

  protected toggleExclusion(animateurId: string, prevenir: boolean): void {
    this.selection.setExcluded(animateurId, !prevenir);
  }

  protected changeSummaryOf(destinataire: DestinatairePublication): string {
    return changeSummary(destinataire);
  }

  protected confirmationOf(destinataire: DestinatairePublication): string {
    return confirmationLabel(destinataire, intlLocale());
  }

  protected chooseSort(sort: RecipientSort): void {
    this.sortOrder.set(sort);
  }

  protected toggleMinorFilter(masquer: boolean): void {
    this.minorHidden.set(masquer);
  }

  /**
   * Mails every animateur holding at least one poste their individual
   * planning (PDF + espace link), built server-side from the persisted
   * planning — same read-only source as the global PDF. Confirmed first: it
   * reaches everyone at once.
   */
  protected async publish(): Promise<void> {
    if (this.busy() || !this.publishable()) {
      return;
    }
    const excluded = [...this.excluded()];
    const count = this.notifiedCount();
    if (count <= 0) {
      return;
    }
    // Raised BEFORE the confirmation, not after it: the button drives it, and
    // leaving it live while the dialog is open lets a second click open a
    // second dialog — two confirmations, two POSTs, two waves of mail. On this
    // action the double click is the failure mode, so the guard has to cover
    // the whole gesture and not only the request.
    this.busy.set(true);
    this.recipientsInFlight.set(count);
    try {
      const confirmed = await this.confirm.ask({
        title: $localize`:@@publication.confirmTitre:Publier le planning ?`,
        message:
          excluded.length === 0
            ? $localize`:@@publication.confirmMessage:${count}:count: personne(s) recevront leur planning à jour et le détail de ce qui change pour elles. Personne d'autre ne sera sollicité.`
            : $localize`:@@publication.confirmMessageExclusions:${count}:count: personne(s) recevront leur planning à jour. ${excluded.length}:exclus: personne(s) ne recevront rien et resteront à prévenir à la prochaine publication.`,
        confirmLabel: $localize`:@@publication.confirmAction:Publier`,
      });
      if (!confirmed) {
        return;
      }
      this.reported.emit($localize`:@@publication.enCours:Publication du planning...`);
      try {
        const report = await this.planningApi.publish(excluded);
        const summary = resumePublication(report);
        this.reported.emit(
          summary.details ? `${summary.titre} — ${summary.details}` : summary.titre,
        );
        this.selection.clear();
        this.listOpen.set(false);
        this.published.emit();
        // The second milestone at which freezing is offered (ADR 0052).
        void this.gelInvitation.offer('publication');
      } catch (error) {
        this.reported.emit(errorPrefix(error));
      } finally {
        await this.reloadPreview();
      }
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Reads who is concerned. Called when the panel appears, when the list is
   * expanded, after a publication and after a solve — never on a timer: a
   * count that refreshes behind the user's back is a count they stop reading.
   */
  async reloadPreview(): Promise<void> {
    try {
      const apercu = await this.planningApi.publicationPreview();
      // An exclusion only means something about somebody the list still names:
      // a person whose change was undone between two reads must not stay
      // silently ticked off for the next publication.
      this.selection.retain(new Set(apercu.destinataires.map((each) => each.animateurId)));
      this.preview.set(apercu);
    } catch {
      // The block stays silent rather than announcing a count it did not read.
      this.preview.set(null);
    }
  }

  protected async toggleList(): Promise<void> {
    const open = !this.listOpen();
    this.listOpen.set(open);
    if (open) {
      await this.reloadPreview();
    }
  }
}
