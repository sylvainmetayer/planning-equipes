import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { summarizeVacationsByDay } from './decoupage';
import { labelCreneauxPluriel } from '../../core/entity-labels';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import { CauseInfaisabilite, Creneau } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { CreneauBulkEditData, CreneauBulkEditDialog } from './creneau-bulk-edit-dialog';
import { CreneauFormData, CreneauFormDialog } from './creneau-form-dialog';

/**
 * Timeslots CRUD: event day, date and hours of every schedulable slot,
 *
 * Slots are multi-selectable, for a bulk delete or to move a whole batch to
 * another group / realign its hours.
 */
@Component({
  selector: 'app-creneaux-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSortModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    RouterLink,
    BulkActionsBar
  ],
  templateUrl: './creneaux-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreneauxPage {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly resolution = inject(PlanningResolutionStore);

  /**
   * The « Famille » column only appears when the displayed slots actually
   * carry several stagger families: a group generated with N families holds N
   * same-looking variants of every slot (each stand is assigned exactly one),
   * which read as inexplicable duplicates without it — and as noise with it,
   * on the groups that have a single family.
   */
  protected readonly columns = computed(() =>
    this.afficherFamilles()
      ? ['select', 'jour', 'date', 'horaires', 'famille', 'probleme', 'actions']
      : ['select', 'jour', 'date', 'horaires', 'probleme', 'actions']
  );
  protected readonly afficherFamilles = computed(() =>
    this.creneauxAffiches().some((creneau) => (creneau.famille ?? 0) > 0)
  );

  /** Sorting, so the slots at fault can be grouped instead of hunted for. */
  protected readonly sort = signal<Sort>({ active: '', direction: '' });

  /**
   * `store.creneaux()` is already chronological (jour, heureDebut); the sort
   * below is stable, so an unsorted view keeps that order.
   */
  protected readonly creneauxAffiches = computed(() => {
    const creneaux = [...this.store.creneaux()];
    const { active, direction } = this.sort();
    if (!active || !direction) {
      return creneaux;
    }
    const facteur = direction === 'asc' ? 1 : -1;
    return creneaux.sort((a, b) => facteur * this.comparer(a, b, active));
  });

  /** `probleme` sorts on the shortfall, so the worst slots come first. */
  private comparer(a: Creneau, b: Creneau, colonne: string): number {
    if (colonne === 'probleme') {
      const manque = (creneau: Creneau) => this.causeParCreneau().get(creneau.id)?.manque ?? 0;
      return manque(a) - manque(b);
    }
    return a.jour - b.jour || (a.heureDebut ?? '').localeCompare(b.heureDebut ?? '');
  }

  /**
   * Keyed on the displayed slots, so "select all" only ever reaches what the
   * page is showing. It used to be justified by the groupe-de-créneaux
   * filter, which #172 removed along with the groups themselves; the keying
   * outlives it because any future narrowing of the list must behave the
   * same way.
   */
  protected readonly selection = new TableSelection<number>(
    computed(() => this.creneauxAffiches().map((creneau) => creneau.id))
  );

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * row, Espace ticks it. `core/table-navigation.ts` holds the whole mechanism,
   * shared with the other reference-data tables.
   */
  protected readonly navigation = new TableNavigation({
    rows: this.creneauxAffiches,
    id: (creneau: Creneau) => creneau.id,
    host: () => this.hote.nativeElement,
    selection: this.selection,
    open: (creneau: Creneau) => this.ouvrirLigne(creneau),
    announcer: inject(LiveAnnouncer)
  });

  /**
   * Entrée on a row. A créneau has no read-only detail view — it carries a day,
   * a date and two times, all four already in the table — so the keyboard opens
   * the form straight away, and refuses it while a solve runs exactly like the
   * row's own "Modifier" button.
   *
   * <p>Refusing returns `false`, so the navigation leaves the key alone rather
   * than eating it for nothing. The refusal stays silent on purpose: the page
   * already carries a permanent banner saying the edition is locked for as
   * long as it is, and one snack bar per keystroke would only repeat it.</p>
   */
  private ouvrirLigne(creneau: Creneau): boolean {
    if (this.editingLocked()) {
      return false;
    }
    this.edit(creneau);
    return true;
  }

  /**
   * Créneau id → the feasibility cause naming it, re-keyed on the numeric id so
   * a row is a plain map lookup. The report carries `creneauId` as a string
   * while `Creneau.id` is a number, hence the `String(...)` normalisation here
   * rather than in every template.
   */
  protected readonly causeParCreneau = computed<Map<number, CauseInfaisabilite>>(() => {
    const parId = this.problemes.causeParCreneauId();
    const index = new Map<number, CauseInfaisabilite>();
    if (parId.size === 0) {
      return index;
    }
    for (const creneau of this.store.creneaux()) {
      const cause = parId.get(String(creneau.id));
      if (cause) {
        index.set(creneau.id, cause);
      }
    }
    return index;
  });

  constructor() {
    void this.crud.reload();
    void this.problemes.reloadFeasibility();
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(creneau: Creneau): void {
    this.openDialog(creneau);
  }

  private openDialog(creneau: Creneau | null): void {
    this.dialog.open<CreneauFormDialog, CreneauFormData, boolean>(CreneauFormDialog, {
      data: { creneau },
      width: '36rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(creneau: Creneau): Promise<void> {
    await this.crud.remove('creneaux', creneau.id, $localize`:@@creneaux.entityLabel:Créneau`);
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('creneaux', this.selection.selectedIds(), labelCreneauxPluriel());
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<CreneauBulkEditDialog, CreneauBulkEditData, boolean>(CreneauBulkEditDialog, {
      data: { creneaux: this.store.creneaux().filter((creneau) => selectionnes.has(creneau.id)) },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  /* --------------------- Découpage automatique en vacations --------------------- */
  // Lives here rather than on a page of its own: the generation reads the
  // créneaux above as amplitudes and REPLACES them in place (issue #172), so
  // it belongs next to the list it rewrites. Its parameters are edited on the
  // Paramètres page.

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

  protected readonly previewVacations = signal<Creneau[] | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly genererLoading = signal(false);

  protected readonly resumeDecoupage = computed(() => {
    const vacations = this.previewVacations();
    return vacations ? summarizeVacationsByDay(vacations) : [];
  });

  protected async previsualiserDecoupage(): Promise<void> {
    this.previewLoading.set(true);
    this.previewVacations.set(null);
    try {
      this.previewVacations.set(await this.api.get<Creneau[]>('/api/decoupage/preview'));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.previewLoading.set(false);
    }
  }

  /** Confirmed first: the generation replaces the edition's créneaux and erases the persisted plan with them. */
  protected async genererDecoupage(): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@decoupage.generer.title:Générer le découpage`,
      message: $localize`:@@decoupage.generer.confirm:Les créneaux actuels de l'édition (les amplitudes) seront remplacés par les vacations générées, et le planning résolu sera effacé avec eux. Pour re-découper avec d'autres paramètres, il faudra ré-importer le scénario source.`,
      confirmLabel: $localize`:@@decoupage.generer.submitCourt:Générer les vacations`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    this.genererLoading.set(true);
    try {
      await this.api.post('/api/decoupage/generer', {});
      await Promise.all([this.crud.reload(), this.resolution.reload()]);
      this.notifications.notify({
        title: $localize`:@@decoupage.generated:Découpage généré : les vacations ont remplacé les amplitudes.`,
        variant: 'success',
        timeout: 6000
      });
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.genererLoading.set(false);
    }
  }
}
