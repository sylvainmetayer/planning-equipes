import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSortModule } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
import { labelStandsPluriel } from '../../core/entity-labels';
import { resumerHoraires } from '../../core/horaire-stand';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { RapportOuvertures, Stand, TypologieItem } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { EmptyState } from '../../shared/empty-state';
import { FilterChip, FilterChips } from '../../shared/filter-chips';
import { RowMenu } from '../../shared/row-menu';
import { RowWarning } from '../../shared/row-warning';
import { PasteColumn } from '../../core/paste-rows';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { GelNotice } from '../../shared/gel-notice';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { ImportedRowsFilter } from '../../shared/imported-rows-filter';
import { TableFilter } from '../../shared/table-filter';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StandBulkEditData, StandBulkEditDialog } from './stand-bulk-edit-dialog';
import { buildStandDetail } from './stand-detail';
import { MAX_STANDS_COMPARES, MIN_STANDS_COMPARES } from '../ouvertures/comparaison-ouvertures';
import { StandFormData, StandFormDialog } from './stand-form-dialog';
import { ImportButton } from '../../shared/import-button';

/**
 * Stands CRUD: identity, staffing bounds, adults-only flag and typologies.
 *
 * Rows named by a feasibility cause carry an alert icon whose tooltip is the
 * cause's own message, so a stand nobody can staff is visible where it is
 * edited, not only on the Problèmes page.
 *
 * Rows are multi-selectable, for a bulk delete or a bulk edit of the fields
 * stands share (emplacement, typologies, effectif, indicateurs).
 */
@Component({
  selector: 'app-stands-page',
  imports: [
    ImportedRowsFilter,
    ImportButton,
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatMenuModule,
    MatSortModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
    BulkActionsBar,
    EmptyState,
    FilterChips,
    RowMenu,
    RowWarning,
    TableFilter,
    GelNotice,
  ],
  templateUrl: './stands-page.html',
  styleUrl: '../../../styles/horaires-stand.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandsPage extends ReferenceTablePage<Stand> implements OnInit {
  private readonly gel = injectGelReferentiel();
  /**
   * What a STANDS freeze refuses as a whole (ADR 0052): creating, deleting,
   * compacting the opening hours, and the bulk actions — a bulk edit sets the
   * shared fields a freeze covers. The single edit stays open: a rename or a
   * new location passes, and the form shows the frozen fields read-only.
   */
  protected readonly standsLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('STANDS'),
  );

  protected readonly columns = [
    'select',
    'id',
    'code',
    'nom',
    'effectif',
    'typologies',
    'emplacement',
    'horaires',
    'actions',
  ];

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly standsFiltres = this.lignesFiltrees;

  /**
   * `?typologie=` and `?emplacement=`: the stands proposing that game
   * category, or standing on that location — where the counts of the
   * Typologies screen and the fiche of a location lead.
   */
  protected readonly typologieFiltre = signal('');
  protected readonly emplacementFiltre = signal('');

  /** The two filters as chips above the table, named rather than by their ids. */
  protected readonly chips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = [];
    const typologie = this.typologieFiltre();
    if (typologie) {
      const label =
        this.store.typologies().find((each) => each.id === typologie)?.label ?? typologie;
      chips.push({
        key: 'typologie',
        label: $localize`:@@stands.chip.typologie:Typologie : ${label}:typologie:`,
      });
    }
    const emplacement = this.emplacementFiltre();
    if (emplacement) {
      const nom =
        this.store.emplacements().find((each) => each.id === emplacement)?.nom ?? emplacement;
      chips.push({
        key: 'emplacement',
        label: $localize`:@@stands.chip.emplacement:Emplacement : ${nom}:emplacement:`,
      });
    }
    return chips;
  });

  /** True while the compaction round-trip is in flight, to keep it from being fired twice. */
  protected readonly compactageEnCours = signal(false);

  /** Holds `causeParStandId`: a memoised map, so each row only does a lookup. */
  protected readonly problemes = inject(ProblemesStore);

  /** Bounds the form's callback: this page is lazy, and a callback on a dead one writes into nothing. */
  private readonly destroyRef = inject(DestroyRef);
  private readonly standsApi = inject(StandsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  /**
   * The openings report, for the fiche: a stand's own anomalies are read
   * there, next to the rules that cause them. `null` until it is in — the
   * fiche then shows no section rather than a false « aucune ».
   */
  private readonly ouvertures = signal<RapportOuvertures | null>(null);

  constructor() {
    super({
      rows: (store) => store.stands(),
      id: (stand) => stand.id,
      champsFiltre: (stand, store) => [
        stand.id,
        stand.code,
        stand.nom,
        ...libellesTypologies(stand, store.typologies()),
        stand.emplacement?.nom,
        stand.emplacement?.id,
      ],
      detail: (stand, store) => ({
        title: stand.nom || stand.id,
        subtitle: stand.id,
        sections: buildStandDetail(
          stand,
          store.typologies(),
          this.ouvertures()?.anomalies.filter((anomalie) => anomalie.standId === stand.id) ?? null,
        ),
        // « Comparer avec… »: the comparator opens on this stand as the
        // reference, and asks which others to lay beside it.
        link: {
          label: $localize`:@@stands.comparerAvec:Comparer avec…`,
          icon: 'compare',
          path: '/ouvertures',
          queryParams: { vue: 'comparer', stands: stand.id },
        },
      }),
      drafts: {
        type: 'stand',
        describe: (ids) => $localize`:@@stands.brouillon.orphelin:Le stand ${ids}:ids:`,
      },
      formulaire: (stand, dialog: MatDialog) => {
        dialog
          .open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
            data: { stand },
            width: '40rem',
            maxWidth: '95vw',
            autoFocus: 'first-tabbable',
          })
          .afterClosed()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe((ecrit) => {
            // The openings are computed server-side from the schedule that was
            // just written: without this, the fiche reopened after an edit shows
            // the anomalies of the schedule before it — « aucune » on a window
            // the user has just broken.
            if (ecrit) {
              void this.chargerOuvertures();
            }
          });
      },
      ressource: 'stands',
      libelle: () => $localize`:@@stands.entityLabel:Stand`,
      name: (stand) => stand.nom,
      libellePluriel: labelStandsPluriel,
      sortValues: {
        id: (stand) => stand.id,
        code: (stand) => stand.code,
        nom: (stand) => stand.nom,
        effectif: (stand) => stand.effectifMin * 1000 + stand.effectifMax,
        typologies: (stand, store) => libellesTypologies(stand, store.typologies()).join(', '),
        emplacement: (stand) => stand.emplacement?.nom,
        horaires: (stand) =>
          (stand.horaires ?? []).length * 1000 +
          (stand.ouvertures ?? []).length +
          (stand.indisponibilites ?? []).length,
      },
      export: {
        name: 'stands',
        columns: (store) => [
          { title: $localize`:@@common.id:Id`, value: (stand) => stand.id },
          { title: $localize`:@@referentiel.field.code:Code`, value: (stand) => stand.code },
          { title: $localize`:@@common.nom:Nom`, value: (stand) => stand.nom },
          {
            title: $localize`:@@stands.field.effectifMin:Effectif minimum`,
            value: (stand) => stand.effectifMin,
          },
          {
            title: $localize`:@@stands.field.effectifMax:Effectif maximum`,
            value: (stand) => stand.effectifMax,
          },
          {
            title: $localize`:@@stands.column.typologies:Typologies`,
            value: (stand) => libellesTypologies(stand, store.typologies()),
          },
          {
            title: $localize`:@@stands.field.emplacement:Emplacement`,
            value: (stand) => stand.emplacement?.nom,
          },
          {
            title: $localize`:@@stands.column.horaires:Horaires`,
            value: (stand) => this.horairesLabel(stand),
          },
        ],
      },
      paste: () => [
        {
          key: 'code',
          title: $localize`:@@referentiel.field.code:Code`,
          read: (stand) => stand.code ?? '',
          write: (stand, text) => ({ ...stand, code: text }),
        },
        {
          key: 'nom',
          title: $localize`:@@common.nom:Nom`,
          read: (stand) => stand.nom,
          write: (stand, text) => ({ ...stand, nom: text }),
        },
        effectifColumn('effectifMin', $localize`:@@stands.field.effectifMin:Effectif minimum`),
        effectifColumn('effectifMax', $localize`:@@stands.field.effectifMax:Effectif maximum`),
      ],
      duplicate: (stand, dialog: MatDialog) => {
        dialog.open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
          data: { stand: null, modele: stand },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        });
      },
    });
    const params = inject(ActivatedRoute, { optional: true })?.snapshot?.queryParamMap;
    this.typologieFiltre.set(params?.get('typologie') ?? '');
    this.emplacementFiltre.set(params?.get('emplacement') ?? '');
    keepViewInQueryParams(() => ({
      typologie: optionalParam(this.typologieFiltre()),
      emplacement: optionalParam(this.emplacementFiltre()),
    }));
    void this.problemes.reloadFeasibility();
  }

  /** The chip filters — the quick filter already ran, the sort comes after. */
  protected override refine(lignes: readonly Stand[]): readonly Stand[] {
    const typologie = this.typologieFiltre();
    const emplacement = this.emplacementFiltre();
    return lignes.filter(
      (stand) =>
        (!typologie || (stand.typologiesProposees ?? []).includes(typologie)) &&
        (!emplacement || stand.emplacement?.id === emplacement),
    );
  }

  protected removeChip(key: string): void {
    if (key === 'typologie') {
      this.typologieFiltre.set('');
    } else {
      this.emplacementFiltre.set('');
    }
  }

  protected clearChips(): void {
    this.typologieFiltre.set('');
    this.emplacementFiltre.set('');
  }

  ngOnInit(): void {
    void this.chargerOuvertures();
  }

  /** Read again after a write: the anomalies follow the schedule that was just saved. */
  private async chargerOuvertures(): Promise<void> {
    try {
      this.ouvertures.set(await this.standsApi.openings());
    } catch {
      // The fiche simply omits its openings section; the Ouvertures page reports the failure itself.
      this.ouvertures.set(null);
    }
  }

  /** The step before the stands, named on the empty state. */
  protected previousStepLabel(): string {
    return $localize`:@@stands.empty.typologies:Saisir les typologies`;
  }

  /** The stand's game categories by label: its ids are generated (T1, T2…) and read as nothing. */
  protected typologiesLabel(stand: Stand): string {
    return libellesTypologies(stand, this.store.typologies()).join(', ') || '—';
  }

  protected effectifSuffix(stand: Stand): string {
    const majeurs = stand.reserveMajeurs ? $localize`:@@stands.suffix.majeurs: · majeurs` : '';
    const premium = stand.premium ? $localize`:@@stands.suffix.premium: · premium` : '';
    const epuisant =
      stand.niveauEffort === 'EPUISANT' ? $localize`:@@stands.suffix.epuisant: · épuisant` : '';
    return `${majeurs}${premium}${epuisant}`;
  }

  protected emplacementLabel(stand: Stand): string {
    return stand.emplacement?.nom || '—';
  }

  /**
   * "2 règles · 1 exception" instead of the raw window count. The old column
   * showed `24` for a stand simply open 10:00-12:00 then 14:00-20:00 every day,
   * which said nothing about its schedule.
   */
  protected horairesLabel(stand: Stand): string {
    return resumerHoraires(stand, {
      aucun: '—',
      regles: (n) => $localize`:@@stands.horaires.summary.regles:${n}:count: règle(s)`,
      exceptions: (n) => $localize`:@@stands.horaires.summary.exceptions:${n}:count: exception(s)`,
    });
  }

  /**
   * Rewrites hand-entered dated windows as the recurring rules they repeat.
   * Always a dry run first: the report it returns is what the confirmation
   * dialog shows, so nothing is written before the user has seen the trade.
   */
  protected async compacterHoraires(): Promise<void> {
    this.compactageEnCours.set(true);
    try {
      await this.lancerCompactage();
    } catch (error) {
      // Same channel as every other write of this page: a snack bar, not an
      // unhandled rejection swallowed by the click handler.
      this.crud.reportError(error);
    } finally {
      this.compactageEnCours.set(false);
    }
  }

  private async lancerCompactage(): Promise<void> {
    const apercu = await this.standsApi.compactSchedules(false);
    if (apercu.standsCompactes === 0) {
      this.notifications.notify({
        title: $localize`:@@stands.compactage.rienATitle:Aucun horaire à compacter`,
        message: $localize`:@@stands.compactage.rienAMessage:Aucun stand ne répète un motif qui pourrait devenir une règle.`,
        variant: 'info',
      });
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@stands.compactage.confirmTitle:Compacter les horaires ?`,
      message: $localize`:@@stands.compactage.confirmMessage:${apercu.standsCompactes}:stands: stand(s) : ${apercu.fenetresAvant}:avant: plages datées remplacées par ${apercu.fenetresApres}:apres: règles et exceptions. Un stand dont les règles changeraient ses ouvertures est laissé inchangé.`,
      confirmLabel: $localize`:@@stands.compactage.confirmLabel:Compacter`,
    });
    if (!confirme) {
      return;
    }
    const rapport = await this.standsApi.compactSchedules(true);
    await this.crud.reload();
    this.notifications.notify({
      title: $localize`:@@stands.compactage.doneTitle:Horaires compactés`,
      message: $localize`:@@stands.compactage.doneMessage:${rapport.standsCompactes}:stands: stand(s) compacté(s), ${rapport.fenetresAvant}:avant: plages ramenées à ${rapport.fenetresApres}:apres: entrées.`,
      variant: 'success',
    });
  }

  /**
   * The link to the openings comparator for the ticked stands, or null outside
   * two to eight — one stand has nothing to be compared with, and past eight
   * the Consulter grid, filtered, is the tool.
   */
  protected readonly comparaisonParams = computed(() => {
    const ids = this.selection.selectedIds();
    return ids.length >= MIN_STANDS_COMPARES && ids.length <= MAX_STANDS_COMPARES
      ? { vue: 'comparer', stands: ids.join(',') }
      : null;
  });

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<StandBulkEditDialog, StandBulkEditData, boolean>(StandBulkEditDialog, {
      data: { stands: this.store.stands().filter((stand) => selectionnes.has(stand.id)) },
      width: '48rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
  }
}

/** A stand's game categories by label, an unknown id kept as-is rather than dropped. */
function libellesTypologies(stand: Stand, typologies: readonly TypologieItem[]): string[] {
  const labels = new Map(typologies.map((typologie) => [typologie.id, typologie.label]));
  return (stand.typologiesProposees ?? []).map((id) => labels.get(id) ?? id);
}

/** A pasted headcount: a whole number of at least one, the minimum never above the maximum. */
function effectifColumn(key: 'effectifMin' | 'effectifMax', title: string): PasteColumn<Stand> {
  return {
    key,
    title,
    read: (stand) => String(stand[key]),
    write: (stand, text) => {
      const valeur = Number(text);
      if (!Number.isInteger(valeur) || valeur < 1) {
        return $localize`:@@stands.collage.effectif:un nombre entier d'au moins 1`;
      }
      const patched = { ...stand, [key]: valeur };
      return patched.effectifMin > patched.effectifMax
        ? $localize`:@@stands.collage.bornes:le minimum dépasserait le maximum`
        : patched;
    },
  };
}
