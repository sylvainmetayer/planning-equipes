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
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { ApercuPublication } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import {
  libelleDernierePublication,
  libellePublier,
  raisonIndisponible,
  resumePublication,
} from '../../core/publication';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';

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
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule],
  templateUrl: './publication-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicationPanel {
  private readonly planningApi = inject(PlanningApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly confirm = inject(ConfirmService);

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
  protected readonly publishLabel = computed(() => libellePublier(this.preview()));
  protected readonly unavailableReason = computed(() => raisonIndisponible(this.preview()));
  protected readonly lastPublication = computed(() =>
    libelleDernierePublication(this.preview(), intlLocale()),
  );
  protected readonly publishable = computed(() => {
    const preview = this.preview();
    return !!preview && preview.nombreConcernes > 0 && !preview.solveEnCours && !preview.planVide;
  });

  constructor() {
    void this.reloadPreview();
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
    const preview = this.preview();
    const count = preview ? preview.nombreConcernes : 0;
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
        message: $localize`:@@publication.confirmMessage:${count}:count: personne(s) recevront leur planning à jour et le détail de ce qui change pour elles. Personne d'autre ne sera sollicité.`,
        confirmLabel: $localize`:@@publication.confirmAction:Publier`,
      });
      if (!confirmed) {
        return;
      }
      this.reported.emit($localize`:@@publication.enCours:Publication du planning...`);
      try {
        const report = await this.planningApi.publish();
        const summary = resumePublication(report);
        this.reported.emit(
          summary.details ? `${summary.titre} — ${summary.details}` : summary.titre,
        );
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
      this.preview.set(await this.planningApi.publicationPreview());
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
