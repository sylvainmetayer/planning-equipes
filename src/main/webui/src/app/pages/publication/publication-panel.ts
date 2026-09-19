import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
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
import { PlanningStateService } from '../../core/planning-state.service';
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
  readTri,
  sortRecipients,
  TriDestinataires,
} from './publication-diff';

/**
 * What leaves the application once the planning is good enough: the documents
 * to print or archive, and the publication (issue #245) that mails every
 * animateur whose schedule changed. Both read the planning persisted for the
 * edition on screen, so they wait on the per-edition lock and nothing else.
 *
 * <p>The panel owns the publication preview — who would be reached, and what
 * changes for them — and re-reads it after every publication. The page asks
 * for a re-read after a solve ({@link reloadPreview}) and is told what to put
 * in its output panel ({@link reported}).</p>
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
export class PublicationPanel {
  private readonly planningApi = inject(PlanningApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly confirm = inject(ConfirmService);
  private readonly params = currentViewParams();

  /** The line the page shows in its output panel: a sentence, a summary, or an error. */
  readonly reported = output<string>();
  /** Raised while an export is being built — the page names it as the reason its actions are locked. */
  readonly exportBusyChange = output<boolean>();

  /**
   * The narrower, per-edition lock — what the diffusion actions wait on. They
   * read the planning persisted for the edition on screen, so only a solve
   * writing to THAT edition can hand out a half-rewritten planning; a run on
   * another edition leaves this one exactly as it was saved. Same rule as the
   * data-entry screens (see `docs/decisions/0001-cloisonnement-par-edition.md`, §5).
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly exportBusy = signal(false);

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
  protected readonly tri = signal<TriDestinataires>(readTri(this.params.get('tri')));
  protected readonly masquerMineurs = signal(this.params.get('mineurs') === 'masques');

  /**
   * Who the admin took out of this send. Not view state and deliberately not
   * in the URL: it is a decision about to be carried out, not a way of looking
   * at the list, and a shared link that silently carried somebody's exclusion
   * would be the worst possible thing to paste into a chat.
   */
  private readonly exclus = signal<ReadonlySet<string>>(new Set());

  protected readonly lignes = computed(() =>
    sortRecipients(
      filterRecipients(this.preview()?.destinataires ?? [], this.masquerMineurs()),
      this.tri(),
    ),
  );

  /** How many rows the filter is currently folding away — said, so nothing hides silently. */
  protected readonly mineursCaches = computed(
    () => (this.preview()?.destinataires ?? []).filter((each) => each.mineur).length,
  );

  protected readonly nombrePrevenus = computed(
    () => (this.preview()?.nombreConcernes ?? 0) - this.exclus().size,
  );
  protected readonly publishLabel = computed(() => libellePublier(this.preview()));
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
    return !!preview && this.nombrePrevenus() > 0 && !preview.solveEnCours && !preview.planVide;
  });

  constructor() {
    keepViewInQueryParams(() => ({
      tri: optionalParam(this.tri() === 'nom' ? null : this.tri()),
      mineurs: this.masquerMineurs() ? 'masques' : null,
    }));
    void this.reloadPreview();
  }

  protected estExclu(animateurId: string): boolean {
    return this.exclus().has(animateurId);
  }

  protected basculerExclusion(animateurId: string, prevenir: boolean): void {
    const exclus = new Set(this.exclus());
    if (prevenir) {
      exclus.delete(animateurId);
    } else {
      exclus.add(animateurId);
    }
    this.exclus.set(exclus);
  }

  protected resumeChangements(destinataire: DestinatairePublication): string {
    return changeSummary(destinataire);
  }

  protected resumeConfirmation(destinataire: DestinatairePublication): string {
    return confirmationLabel(destinataire, intlLocale());
  }

  protected choisirTri(tri: TriDestinataires): void {
    this.tri.set(tri);
  }

  protected basculerMineurs(masquer: boolean): void {
    this.masquerMineurs.set(masquer);
  }

  /** The same table as a file, for the reading that happens away from the screen. */
  protected async exportDiff(): Promise<void> {
    this.setExportBusy(true);
    try {
      this.reported.emit(await this.planningApi.exportPublicationDiff());
    } catch (error) {
      this.reported.emit(errorPrefix(error));
    } finally {
      this.setExportBusy(false);
    }
  }

  /**
   * Every per-animateur document in one archive, built from the planning the
   * browser holds — what is exported is what is shown.
   */
  protected async exportBundle(): Promise<void> {
    this.setExportBusy(true);
    this.reported.emit($localize`:@@solver.exportBuilding:Construction de l'archive d'export...`);
    try {
      const planning = await this.planningState.require();
      this.reported.emit(await this.planningApi.exportBundle(planning));
    } catch (error) {
      this.reported.emit(errorPrefix(error));
    } finally {
      this.setExportBusy(false);
    }
  }

  /**
   * The organiser's own copy: one PDF holding every assignment, laid out by
   * day and by stand. A GET, unlike the per-animateur bundle above — the
   * server reads the persisted planning itself rather than having the browser
   * upload several megabytes of JSON just to get a document back.
   */
  protected async exportGlobalPdf(): Promise<void> {
    this.setExportBusy(true);
    this.reported.emit($localize`:@@solver.exportGlobalBuilding:Construction du PDF global...`);
    try {
      this.reported.emit(await this.planningApi.exportGlobalPdf());
    } catch (error) {
      this.reported.emit(errorPrefix(error));
    } finally {
      this.setExportBusy(false);
    }
  }

  private setExportBusy(busy: boolean): void {
    this.exportBusy.set(busy);
    this.exportBusyChange.emit(busy);
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
    const exclus = [...this.exclus()];
    const count = this.nombrePrevenus();
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
          exclus.length === 0
            ? $localize`:@@publication.confirmMessage:${count}:count: personne(s) recevront leur planning à jour et le détail de ce qui change pour elles. Personne d'autre ne sera sollicité.`
            : $localize`:@@publication.confirmMessageExclusions:${count}:count: personne(s) recevront leur planning à jour. ${exclus.length}:exclus: personne(s) ne recevront rien et resteront à prévenir à la prochaine publication.`,
        confirmLabel: $localize`:@@publication.confirmAction:Publier`,
      });
      if (!confirmed) {
        return;
      }
      this.reported.emit($localize`:@@publication.enCours:Publication du planning...`);
      try {
        const report = await this.planningApi.publish(exclus);
        const summary = resumePublication(report);
        this.reported.emit(
          summary.details ? `${summary.titre} — ${summary.details}` : summary.titre,
        );
        this.exclus.set(new Set());
        this.listOpen.set(false);
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
      const concernes = new Set(apercu.destinataires.map((each) => each.animateurId));
      this.exclus.set(new Set([...this.exclus()].filter((id) => concernes.has(id))));
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
