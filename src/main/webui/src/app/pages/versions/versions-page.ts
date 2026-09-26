import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanningApi } from '../../core/api/planning-api';
import { EditionStore } from '../../core/edition.store';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry, PlanSnapshot, RestaurationSnapshot } from '../../core/models';
import {
  InstantanePerimeError,
  PlanSnapshotStore,
  ReferencesManquantesError,
} from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { SolverJobService } from '../../core/solver-job.service';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';
import { confirmStaleRestore } from '../../shared/stale-snapshot-confirm';
import { StatusMessage } from '../../shared/status-message';
import { ComparaisonPanel } from './comparaison-panel';
import {
  CURRENT_PLAN,
  VersionRow,
  comparisonSides,
  eventLabel,
  kpiSentence,
  lastPublishedId,
  readComparison,
  rowKpi,
  versionRows,
} from './versions';

/** The first line of the table: the plan in place, tickable like a snapshot. */
type TableRow = VersionRow | { kind: 'courant'; key: typeof CURRENT_PLAN; date: null };

/**
 * « Versions du plan » (issue #702): the finished solves and the snapshots of
 * the edition in one chronology, newest first — what the Autopsie, the
 * Instantanés and the Comparateur showed on three screens. A snapshot row
 * restores, compares with the plan in place, or is ticked; two ticked rows —
 * or one and « Plan en place » — open the comparator as a panel beside the
 * table (`?comparer=a,b`, so the pair survives a reload and can be shared).
 *
 * <p>A solve row carries its measures but no plan of its own: what a solve
 * produced is the plan in place until the next solve, whose automatic
 * snapshot then keeps it. Restoring stays off while a solve runs — it would be
 * overwritten seconds later.</p>
 */
@Component({
  selector: 'app-versions-page',
  imports: [
    ComparaisonPanel,
    MatButtonModule,
    MatCheckboxModule,
    MatChipsModule,
    MatIconModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './versions-page.html',
  styleUrl: './versions-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VersionsPage implements OnInit {
  protected readonly columns = ['choix', 'quand', 'evenement', 'resultat', 'actions'];

  protected readonly store = inject(PlanSnapshotStore);
  private readonly analysesApi = inject(AnalysesApi);
  private readonly planningApi = inject(PlanningApi);
  private readonly editions = inject(EditionStore);
  private readonly jobs = inject(SolverJobService);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  private readonly entries = signal<KpiHistoriqueEntry[]>([]);
  private readonly entriesLoading = signal(false);
  protected readonly chargement = computed(() => this.store.chargement() || this.entriesLoading());

  protected readonly error = signal('');
  protected readonly message = signal('');
  /** The row a write is under way on (its key), or `capture`. */
  protected readonly pending = signal<string | null>(null);
  protected readonly locked = computed(() => this.jobs.editingLocked());

  /**
   * `?editions=toutes`: every edition's solves and snapshots, the way the
   * Autopsie and the Comparateur listed them — a variant of an edition is
   * another edition, and comparing the two is the point. A row of another
   * edition compares; it restores and deletes only from its own edition.
   */
  protected readonly allEditions = signal(currentViewParams().get('editions') === 'toutes');
  /** Every edition's snapshots, read only while `allEditions` asks for them. */
  private readonly comparables = signal<PlanSnapshot[]>([]);
  protected readonly snapshots = computed(() =>
    this.allEditions() ? this.comparables() : this.store.snapshots(),
  );
  protected readonly currentEditionId = computed(() => this.editions.courant()?.id ?? null);

  protected readonly rows = computed<TableRow[]>(() => [
    { kind: 'courant', key: CURRENT_PLAN, date: null },
    ...versionRows(
      this.entries(),
      this.snapshots(),
      this.allEditions() ? null : this.currentEditionId(),
    ),
  ]);

  /** The ticked selectors: snapshot ids as text, or {@link CURRENT_PLAN}. At most two. */
  protected readonly ticked = signal<string[]>([]);
  /** The pair the panel compares, from `?comparer=` or a « Comparer » button. */
  protected readonly compared = signal<string[]>(
    readComparison(currentViewParams().get('comparer')),
  );
  protected readonly sides = computed(() => comparisonSides(this.compared(), this.snapshots()));

  private readonly publishedId = computed(() => lastPublishedId(this.store.snapshots()));

  constructor() {
    keepViewInQueryParams(() => ({
      comparer: optionalParam(this.compared().join(',')),
      editions: this.allEditions() ? 'toutes' : null,
    }));
  }

  ngOnInit(): void {
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.error.set('');
    this.entriesLoading.set(true);
    try {
      const [entries, comparables] = await Promise.all([
        this.analysesApi.kpiHistory(),
        this.allEditions() ? this.planningApi.comparableSnapshots() : Promise.resolve(null),
        this.store.reload(),
      ]);
      this.entries.set(entries);
      if (comparables) {
        this.comparables.set(comparables);
      }
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.entriesLoading.set(false);
    }
  }

  protected showAllEditions(all: boolean): void {
    this.allEditions.set(all);
    void this.recharger();
  }

  /** A snapshot of another edition restores and deletes from there, not from here. */
  protected isForeign(snapshot: PlanSnapshot): boolean {
    return this.currentEditionId() !== null && snapshot.editionId !== this.currentEditionId();
  }

  /** The edition a row belongs to, said only when every edition is listed. */
  protected editionOf(row: VersionRow): string {
    if (!this.allEditions()) {
      return '';
    }
    return row.kind === 'resolution'
      ? (row.entry.editionNom ?? row.entry.editionId)
      : (row.snapshot.editionNom ?? row.snapshot.editionId);
  }

  /* --------------------------------- a row --------------------------------- */

  /** What « Comparer » is told for a row; `null` for a solve, which keeps no plan. */
  protected selector(row: TableRow): string | null {
    if (row.kind === 'courant') {
      return CURRENT_PLAN;
    }
    return row.kind === 'snapshot' ? String(row.snapshot.id) : null;
  }

  protected isTicked(row: TableRow): boolean {
    const selector = this.selector(row);
    return selector !== null && this.ticked().includes(selector);
  }

  /** A third tick is refused rather than silently dropping the first: the checkbox stays off. */
  protected canTick(row: TableRow): boolean {
    return this.isTicked(row) || this.ticked().length < 2;
  }

  protected toggle(row: TableRow, checked: boolean): void {
    const selector = this.selector(row);
    if (selector === null) {
      return;
    }
    this.ticked.update((ticked) =>
      checked ? [...ticked, selector] : ticked.filter((each) => each !== selector),
    );
  }

  protected compareTicked(): void {
    if (this.ticked().length === 2) {
      this.compared.set([...this.ticked()]);
    }
  }

  protected compareWithCurrent(snapshot: PlanSnapshot): void {
    this.compared.set([String(snapshot.id), CURRENT_PLAN]);
  }

  protected closeComparison(): void {
    this.compared.set([]);
  }

  protected dateLabel(row: TableRow): string {
    return row.date ? new Date(row.date).toLocaleString(intlLocale()) : '';
  }

  protected eventOf(row: VersionRow): string {
    return eventLabel(row);
  }

  protected sentenceOf(row: VersionRow): string {
    const sentence = kpiSentence(rowKpi(row));
    if (sentence || row.kind !== 'snapshot') {
      return sentence;
    }
    // Captured before its measures were stored: it still says how many seats it holds.
    return $localize`:@@versions.phrase.affectations:${row.snapshot.nombreAffectations}:count: affectation(s).`;
  }

  protected isPublished(snapshot: PlanSnapshot): boolean {
    return snapshot.id === this.publishedId();
  }

  /**
   * Why a snapshot carries the « publié » badge. The two sentences are the
   * whole rule: one plan is on display, the ones before it are history.
   */
  protected publicationTooltip(snapshot: PlanSnapshot): string {
    const moment = snapshot.publieLe
      ? new Date(snapshot.publieLe).toLocaleString(intlLocale())
      : '';
    return this.isPublished(snapshot)
      ? $localize`:@@snapshots.published.tooltipCourant:Publié le ${moment}:moment: : c'est le plan que les animateurs ont reçu et que leur espace affiche.`
      : $localize`:@@snapshots.published.tooltipRemplace:Publié le ${moment}:moment:, puis remplacé par une publication plus récente : plus personne ne le lit.`;
  }

  /**
   * Why a snapshot is stale (issue #170): the date tells whether the change
   * was one's own a minute ago or somebody else's last week.
   */
  protected staleTooltip(snapshot: PlanSnapshot): string {
    if (!snapshot.referenceModifieLe) {
      return $localize`:@@snapshots.stale.tooltipSansDate:Le référentiel a été modifié depuis cette capture.`;
    }
    const moment = new Date(snapshot.referenceModifieLe).toLocaleString(intlLocale());
    return $localize`:@@snapshots.stale.tooltip:Référentiel modifié le ${moment}:moment:, après cette capture : le plan ne décrit plus les données actuelles.`;
  }

  /** The delete button says why it is out on the plan on display. */
  protected deleteTooltip(snapshot: PlanSnapshot): string {
    return this.isPublished(snapshot)
      ? $localize`:@@snapshots.delete.publieTooltip:Le plan publié ne peut pas être supprimé : c'est celui que les animateurs ont reçu. La prochaine publication prendra sa place.`
      : $localize`:@@common.delete:Supprimer`;
  }

  /* -------------------------------- the writes -------------------------------- */

  protected async capture(): Promise<void> {
    const libelle = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@snapshots.capture.title:Enregistrer le plan actuel`,
      label: $localize`:@@snapshots.capture.label:Nom de l'instantané`,
      confirmLabel: $localize`:@@snapshots.capture.confirm:Enregistrer`,
    });
    if (!libelle) {
      return;
    }
    this.message.set('');
    await this.write('capture', async () => {
      await this.store.capturer(libelle);
      this.message.set(
        $localize`:@@snapshots.captured:Instantané « ${libelle}:libelle: » enregistré.`,
      );
    });
  }

  protected async restaurer(snapshot: PlanSnapshot): Promise<void> {
    if (this.locked()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@snapshots.restore.title:Restaurer cet instantané ?`,
      message: $localize`:@@snapshots.restore.message:Le plan actuellement enregistré est remplacé par « ${snapshot.libelle}:libelle: ». Enregistrez-le d'abord si vous voulez le garder.`,
      confirmLabel: $localize`:@@snapshots.restore.confirm:Restaurer`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.message.set('');
    await this.write(`s${snapshot.id}`, async () => {
      const resultat = await this.restaurerSnapshot(snapshot);
      if (!resultat) {
        return;
      }
      await this.resolution.reload();
      this.message.set(
        $localize`:@@snapshots.restored:${resultat.affectations}:count: affectation(s) restaurée(s) depuis « ${snapshot.libelle}:libelle: ».`,
      );
    });
  }

  /**
   * The server, not the row on screen, decides whether a snapshot is stale:
   * the first call never forces, and the staleness refusal — which writes
   * nothing — becomes the question, naming the change that caused it.
   */
  private async restaurerSnapshot(snapshot: PlanSnapshot): Promise<RestaurationSnapshot | null> {
    try {
      return await this.store.restaurer(snapshot.id);
    } catch (error) {
      if (!(error instanceof InstantanePerimeError)) {
        throw error;
      }
      return (await confirmStaleRestore(this.confirm, error))
        ? this.store.restaurer(snapshot.id, true)
        : null;
    }
  }

  protected async deleteSnapshot(snapshot: PlanSnapshot): Promise<void> {
    if (this.isPublished(snapshot)) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@snapshots.delete.title:Supprimer cet instantané ?`,
      message: $localize`:@@snapshots.delete.message:« ${snapshot.libelle}:libelle: » sera définitivement perdu.`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    await this.write(`s${snapshot.id}`, async () => {
      await this.store.supprimer(snapshot.id);
      this.forget(String(snapshot.id));
    });
  }

  protected async deleteSolve(entry: KpiHistoriqueEntry): Promise<void> {
    const date = entry.creeLe ? new Date(entry.creeLe).toLocaleString(intlLocale()) : '';
    const confirme = await this.confirm.ask({
      title: $localize`:@@kpi.delete.title:Supprimer cette ligne d'historique ?`,
      message: $localize`:@@kpi.delete.message:La mesure du ${date}:date: sera définitivement perdue.`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    await this.write(`r${entry.id}`, async () => {
      await this.analysesApi.deleteKpiEntry(entry.id);
      this.entries.update((entries) => entries.filter((each) => each.id !== entry.id));
    });
  }

  private async write(key: string, action: () => Promise<void>): Promise<void> {
    this.pending.set(key);
    this.error.set('');
    try {
      await action();
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.pending.set(null);
    }
  }

  /** A deleted snapshot leaves the selection and the comparison it was part of. */
  private forget(selector: string): void {
    this.ticked.update((ticked) => ticked.filter((each) => each !== selector));
    if (this.compared().includes(selector)) {
      this.compared.set([]);
    }
  }

  /** A refused restore names what is missing: that list is the actionable part. */
  private messageErreur(error: unknown): string {
    if (error instanceof ReferencesManquantesError) {
      return $localize`:@@snapshots.error.references:${error.message}:message: Références manquantes : ${error.references.join(', ')}:references:`;
    }
    return errorPrefix(error);
  }
}
