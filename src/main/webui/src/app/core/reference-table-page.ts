import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ElementRef, Signal, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { DetailData, DetailDialog } from '../shared/detail-dialog';
import { ReferenceCrudService } from './reference-crud.service';
import { ReferenceDataStore } from './reference-data.store';
import { SolverJobService } from './solver-job.service';
import { TableNavigation } from './table-navigation';
import { TableSelection } from './table-selection';
import { correspondAuFiltre } from './text-filter';
import { consumeQueryParam } from './view-query-params';

/** What `correspondAuFiltre` knows how to compare. */
type ChampFiltrable = string | number | null | undefined;

/**
 * What one referential page has that the others do not: where its rows come
 * from, what the quick filter looks at, and which dialogs open on them.
 *
 * Everything else — the filter signal, the selection keyed on the filtered
 * rows, the roving tabindex, the reload on construction, the detail-then-edit
 * hand-off, the two deletes — is the same on every one of them, and lives in
 * {@link ReferenceTablePage}.
 */
export interface ReferenceTableConfig<T> {
  /** The rows, read from the store: `(store) => store.typologies()` and friends. */
  rows: (store: ReferenceDataStore) => readonly T[];

  /** Identity of a row, and the key the selection and the URL use. */
  id: (row: T) => string;

  /** What the quick filter matches on — the fields a row is looked up by. */
  champsFiltre: (row: T) => readonly ChampFiltrable[];

  /** Read-only detail shown by {@link ReferenceTablePage.consult}. */
  detail: (row: T, store: ReferenceDataStore) => DetailData;

  /** Opens the create/edit form. `null` means create. */
  formulaire: (row: T | null, dialog: MatDialog) => void;

  /** REST resource of the entity, e.g. `'typologies'`. */
  ressource: string;

  /** Singular label, in the confirmation of a single delete. */
  libelle: () => string;

  /** Plural label, in the confirmation of a bulk delete. */
  libellePluriel: () => string;

  /**
   * Extra sentence shown before deleting one row — typically what still
   * references it. Absent when nothing can.
   */
  usages?: (row: T, store: ReferenceDataStore) => string;
}

/**
 * The shared half of a referential page: typologies, emplacements and stands.
 *
 * <p>The three had recopied the same skeleton — store, jobs, editingLocked,
 * filtre, TableSelection, TableNavigation, consult, openCreate, edit,
 * openDialog, remove, removeSelection — comments included, down to three
 * near-copies of the same twenty lines of roving tabindex. Only what actually
 * differs stays in the page, as a {@link ReferenceTableConfig}.</p>
 *
 * <p>Deliberately TypeScript only. The templates stay per-page: they are what
 * genuinely differs (a column of coordinates, a column of typologies, a
 * horaires summary), and folding them into one component would trade three
 * readable tables for one full of conditionals.</p>
 *
 * <p>Two referential pages do <b>not</b> inherit from this. `animateurs` keys
 * its selection and its navigation on the <b>sorted</b> rows
 * (`sortedAnimateurs`) rather than the filtered ones, which this base does not
 * offer; an optional sort hook would cover it, and would be the right next step
 * if a fourth page ever needs one. `creneaux` generates, orders and groups its
 * own rows, which is another page altogether.</p>
 *
 * <p>This is the application's first abstract base class using `inject()`. It
 * works without an `@Directive()` decorator because `inject()` reads the
 * <em>ambient</em> injector stack, not a lexical one, and because every subclass
 * declares its own constructor and the base carries no Angular feature needing
 * metadata. Adding an `input()` or a host binding <b>here</b> would silently do
 * nothing until it becomes `@Directive()`.</p>
 */
export abstract class ReferenceTablePage<T> {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);

  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  /** Quick filter of the table, on the fields the config names. */
  protected readonly filtre = signal('');

  protected readonly lignesFiltrees: Signal<readonly T[]>;

  /** Keyed on the filtered rows, so "tout sélectionner" follows what the table shows. */
  protected readonly selection: TableSelection<string>;

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * detail, Espace ticks the row. `core/table-navigation.ts` holds the whole
   * mechanism.
   */
  protected readonly navigation: TableNavigation<T, string>;

  protected readonly crud = inject(ReferenceCrudService);
  protected readonly dialog = inject(MatDialog);

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor(private readonly config: ReferenceTableConfig<T>) {
    this.lignesFiltrees = computed(() =>
      config
        .rows(this.store)
        .filter((ligne) => correspondAuFiltre(this.filtre(), config.champsFiltre(ligne))),
    );
    this.selection = new TableSelection<string>(
      computed(() => this.lignesFiltrees().map((ligne) => config.id(ligne))),
    );
    this.navigation = new TableNavigation<T, string>({
      rows: this.lignesFiltrees,
      id: (ligne: T) => config.id(ligne),
      host: () => this.hote.nativeElement,
      selection: this.selection,
      open: (ligne: T) => {
        void this.consult(ligne);
        return true;
      },
      announcer: inject(LiveAnnouncer),
    });
    const chargement = this.crud.reload();
    // `?edit=<id>`: a link from a symptom (a problem, a warning) lands here
    // with the fiche to open. Followed rather than read once — the link very
    // often points at the screen already displayed, where nothing is
    // constructed — then dropped, so a reload does not open the fiche again.
    consumeQueryParam('edit', async (edit) => {
      await chargement;
      const ligne = config.rows(this.store).find((candidat) => config.id(candidat) === edit);
      if (ligne) {
        this.edit(ligne);
      }
    });
  }

  /**
   * Read-only detail of one row, with a "Modifier" button handing over to the
   * usual form dialog — locked, there as here, while a solve is running.
   */
  protected async consult(ligne: T): Promise<void> {
    const result = await firstValueFrom(
      this.dialog
        .open(DetailDialog, {
          data: this.config.detail(ligne, this.store),
          width: '40rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    if (result === 'edit') {
      this.edit(ligne);
    }
  }

  protected openCreate(): void {
    this.config.formulaire(null, this.dialog);
  }

  protected edit(ligne: T): void {
    this.config.formulaire(ligne, this.dialog);
  }

  protected async remove(ligne: T): Promise<void> {
    const ressource = this.config.ressource;
    const id = this.config.id(ligne);
    const libelle = this.config.libelle();
    const usages = this.config.usages?.(ligne, this.store);
    // The fourth argument is left off entirely when the entity has no usages to
    // report, rather than passed as the empty string its default already is: a
    // page with nothing to say must not look like one saying nothing.
    await (usages === undefined
      ? this.crud.remove(ressource, id, libelle)
      : this.crud.remove(ressource, id, libelle, usages));
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany(
      this.config.ressource,
      this.selection.selectedIds(),
      this.config.libellePluriel(),
    );
  }
}
