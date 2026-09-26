import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ElementRef, Signal, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Params } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { reportOrphanDrafts } from '../shared/brouillon-dialog';
import { DetailData, DetailDialog } from '../shared/detail-dialog';
import { PastePreviewService } from '../shared/paste-preview-dialog';
import { ApiService } from './api.service';
import { LOCAL_DRAFT_STORAGE, DraftFormType } from './brouillon-formulaire';
import { NotificationService } from './notification.service';
import { ReferenceCrudService } from './reference-crud.service';
import { ReferenceDataStore } from './reference-data.store';
import { SolverJobService } from './solver-job.service';
import { TableNavigation, trackRowById } from './table-navigation';
import { TableSelection } from './table-selection';
import { CSV_CONTENT_TYPE, CsvColumn, csvFileName, toCsv } from './csv-export';
import { PasteColumn, pastedText, planPaste } from './paste-rows';
import { correspondAuFiltre } from './text-filter';
import {
  IMPORTED_IDS_PARAM,
  importedIdsParam,
  keptByImportedIds,
  readImportedIds,
} from './imported-rows';
import { SortValue, sortRows } from './table-sort';
import {
  NO_SORT,
  SortState,
  consumeQueryParam,
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
  readSort,
  sortQueryParams,
} from './view-query-params';

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

  /**
   * What the quick filter matches on — the fields a row is looked up by. The
   * store is there for what a row only names by id, a stand's game categories.
   */
  champsFiltre: (row: T, store: ReferenceDataStore) => readonly ChampFiltrable[];

  /**
   * Read-only detail shown by {@link ReferenceTablePage.consult}. Absent on a
   * page whose rows have a page of their own — the stands, since their fiche,
   * which `open` leads to.
   */
  detail?: (row: T, store: ReferenceDataStore) => DetailData;

  /** Opens the create/edit form. `null` means create. */
  formulaire: (row: T | null, dialog: MatDialog) => void;

  /** REST resource of the entity, e.g. `'typologies'`. */
  ressource: string;

  /** Singular label, in the confirmation of a single delete. */
  libelle: () => string;

  /**
   * What the confirmation and the notification of a single delete call the
   * row — its nom or label, never its id, which is drawn per edition. None of
   * the three pages' names is personal data, so the notifications log keeps it.
   */
  name: (row: T) => string;

  /** Plural label, in the confirmation of a bulk delete. */
  libellePluriel: () => string;

  /**
   * Extra sentence shown before deleting one row — typically what still
   * references it. Absent when nothing can.
   */
  usages?: (row: T, store: ReferenceDataStore) => string;

  /**
   * The page's form keeps an auto-saved draft (`core/brouillon-formulaire.ts`):
   * once the rows are loaded, the drafts of rows deleted meanwhile are dropped
   * and `describe` names them in the notice.
   */
  drafts?: { type: DraftFormType; describe: (ids: string) => string };

  /**
   * What each sortable column is sorted on — the value its cell shows. With
   * no column chosen, the rows run in the natural order of their ids (T1, T2,
   * T10), never the string order that files T10 before T2.
   */
  sortValues?: Record<string, (row: T, store: ReferenceDataStore) => SortValue>;

  /** « Exporter cette liste »: the file's name, and its columns in the table's order. */
  export?: { name: string; columns: (store: ReferenceDataStore) => CsvColumn<T>[] };

  /** The columns a block copied from a spreadsheet can fill, previewed before anything is saved. */
  paste?: (store: ReferenceDataStore) => PasteColumn<T>[];

  /** « Dupliquer »: the create form, opened on a copy of the row. */
  duplicate?: (row: T, dialog: MatDialog) => void;

  /**
   * Where the name of a row — and Entrée on it — leads: its fiche, when the
   * entity has one; the edit form otherwise.
   */
  open?: (row: T) => void;

  /**
   * The query param naming a row to open in its form on arrival — `edit` by
   * default; `null` for a page whose `?edit=` is answered elsewhere (the stands,
   * redirected to their fiche by the route).
   */
  editParam?: string | null;

  /**
   * The keys `q`, `sort` and `dir` this table writes in the URL, given the
   * ones its view asks for — all of them by default. A page holding a second
   * table in a tab (the Stands and their Lieux) leaves them to that tab while
   * it is on screen, since the tab's own table owns the same keys then.
   */
  viewParams?: (view: Params) => Params;
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
 * (`sortedAnimateurs`) rather than the filtered ones; {@link refine} now offers
 * that hook, and moving it here is left for the day that page is touched. `creneaux` generates, orders and groups its
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

  /** Quick filter of the table, on the fields the config names; `?q=` in the URL. */
  protected readonly filtre = signal('');

  /**
   * `?ids=`: the rows an import just wrote, which its « Voir les N lignes
   * importées » opens the list on — shown as a filter and dropped by « Tout
   * afficher » ({@link showAllRows}). `null` when the address names none.
   */
  protected readonly importedIds = signal<ReadonlySet<string> | null>(null);

  /** The column the rows are sorted on; `?sort=` and `?dir=` in the URL. */
  protected readonly sort = signal<SortState>(NO_SORT);

  protected readonly lignesFiltrees: Signal<readonly T[]>;

  /** Keyed on the filtered rows, so "tout sélectionner" follows what the table shows. */
  protected readonly selection: TableSelection<string>;

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * detail, Espace ticks the row. `core/table-navigation.ts` holds the whole
   * mechanism.
   */
  protected readonly navigation: TableNavigation<T, string>;
  /** Rows kept across a reload of the store, and the focus with them. */
  protected readonly trackById = trackRowById;

  protected readonly crud = inject(ReferenceCrudService);
  protected readonly dialog = inject(MatDialog);

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly api = inject(ApiService);
  private readonly pastePreview = inject(PastePreviewService);
  private readonly notifier = inject(NotificationService);

  constructor(private readonly config: ReferenceTableConfig<T>) {
    const params = inject(ActivatedRoute, { optional: true })?.snapshot?.queryParamMap;
    if (params) {
      this.filtre.set(params.get('q') ?? '');
      this.sort.set(readSort(params));
    }
    keepViewInQueryParams(() => {
      const view = { q: optionalParam(this.filtre()), ...sortQueryParams(this.sort()) };
      return config.viewParams ? config.viewParams(view) : view;
    });
    this.lignesFiltrees = computed(() =>
      sortRows(
        this.refine(
          config
            .rows(this.store)
            .filter(
              (ligne) =>
                keptByImportedIds(this.importedIds(), config.id(ligne)) &&
                correspondAuFiltre(this.filtre(), config.champsFiltre(ligne, this.store)),
            ),
        ),
        this.sort(),
        (ligne, colonne) => config.sortValues?.[colonne]?.(ligne, this.store),
        config.id,
      ),
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
        this.openRow(ligne);
        return true;
      },
      announcer: inject(LiveAnnouncer),
    });
    this.importedIds.set(readImportedIds(currentViewParams().get(IMPORTED_IDS_PARAM)));
    keepViewInQueryParams(() => ({ [IMPORTED_IDS_PARAM]: importedIdsParam(this.importedIds()) }));
    const chargement = this.crud.reload();
    const drafts = config.drafts;
    if (drafts) {
      const notifications = inject(NotificationService);
      const storage = inject(LOCAL_DRAFT_STORAGE);
      void chargement.then((loaded) => {
        if (loaded) {
          reportOrphanDrafts(
            notifications,
            storage,
            drafts.type,
            (id) => config.rows(this.store).some((row) => config.id(row) === id),
            drafts.describe,
          );
        }
      });
    }
    // `?edit=<id>`: a link from a symptom (a problem, a warning) lands here
    // with the fiche to open. Followed rather than read once — the link very
    // often points at the screen already displayed, where nothing is
    // constructed — then dropped, so a reload does not open the fiche again.
    const editParam = config.editParam === undefined ? 'edit' : config.editParam;
    if (editParam) {
      consumeQueryParam(editParam, async (edit) => {
        await chargement;
        const ligne = config.rows(this.store).find((candidat) => config.id(candidat) === edit);
        if (ligne) {
          this.edit(ligne);
        }
      });
    }
  }

  /** « Tout afficher »: the whole referential again, the rest of the view untouched. */
  protected showAllRows(): void {
    this.importedIds.set(null);
  }

  /**
   * What a page does to its rows after the quick filter — a filter of its own;
   * the sort comes after. The selection and the keyboard navigation follow the
   * result, as they follow the quick filter. Called lazily, from the computed
   * rows, so an override may read the subclass's own signals.
   */
  protected refine(lignes: readonly T[]): readonly T[] {
    return lignes;
  }

  /** The name of a row, clicked: its fiche when it has one, the edit form otherwise. */
  protected openRow(ligne: T): void {
    if (this.config.open) {
      this.config.open(ligne);
    } else {
      this.edit(ligne);
    }
  }

  protected duplicate(ligne: T): void {
    this.config.duplicate?.(ligne, this.dialog);
  }

  /** The warnings the last save of this row raised, kept on it once the snack bar is gone. */
  protected warnings(ligne: T): readonly string[] {
    return this.crud.warningsOf(this.config.ressource, this.config.id(ligne));
  }

  /** « Exporter cette liste »: the rows as displayed — filtered, sorted — in the columns shown. */
  protected exportList(): void {
    const exportConfig = this.config.export;
    if (!exportConfig) {
      return;
    }
    const status = this.api.saveText(
      toCsv(this.lignesFiltrees(), exportConfig.columns(this.store)),
      csvFileName(exportConfig.name),
      CSV_CONTENT_TYPE,
    );
    this.notifier.notify({ title: status, variant: 'success', timeout: 4000 });
  }

  /**
   * A block copied from a spreadsheet, pasted on a row (Ctrl+V): laid over the
   * displayed rows from the focused one, previewed, and saved only on
   * « Appliquer » — each changed row once, through the bulk save.
   */
  protected async onPaste(event: ClipboardEvent): Promise<void> {
    const columns = this.config.paste?.(this.store);
    const text = pastedText(event);
    if (!columns || text === null || this.editingLocked()) {
      return;
    }
    event.preventDefault();
    const plan = planPaste(text, {
      rows: this.lignesFiltrees(),
      id: (ligne) => this.config.id(ligne),
      label: (ligne) => this.config.name(ligne) || this.config.id(ligne),
      columns,
      startRow: Math.max(0, this.navigation.index()),
    });
    if (!(await this.pastePreview.confirm(plan))) {
      return;
    }
    await this.crud.saveMany(
      this.config.ressource,
      plan.rows as unknown as { id: string }[],
      this.config.libellePluriel(),
    );
  }

  /**
   * Read-only detail of one row, with a "Modifier" button handing over to the
   * usual form dialog — locked, there as here, while a solve is running.
   */
  protected async consult(ligne: T): Promise<void> {
    const detail = this.config.detail;
    if (!detail) {
      return;
    }
    const result = await firstValueFrom(
      this.dialog
        .open(DetailDialog, {
          data: detail(ligne, this.store),
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
    const name = { text: this.config.name(ligne) };
    // `detail` is left off entirely when the entity has no usages to report,
    // rather than passed as the empty string its default already is: a page
    // with nothing to say must not look like one saying nothing.
    await (usages === undefined
      ? this.crud.remove(ressource, id, libelle, { name })
      : this.crud.remove(ressource, id, libelle, { detail: usages, name }));
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany(
      this.config.ressource,
      this.selection.selectedIds(),
      this.config.libellePluriel(),
    );
  }
}
