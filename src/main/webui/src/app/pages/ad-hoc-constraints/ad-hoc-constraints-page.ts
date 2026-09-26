import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { animateurNames, labelOf, labelsOf, standNames } from '../../core/reference-labels';
import { SolverJobService } from '../../core/solver-job.service';
import {
  consumeQueryParam,
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { ContrainteAdHoc, TypeContrainteAdHoc } from '../../core/models';
import {
  AdHocConstraintFormData,
  AdHocConstraintFormDialog,
  adHocDescription,
} from './ad-hoc-constraint-form-dialog';
import { TableFilter } from '../../shared/table-filter';
import { correspondAuFiltre } from '../../core/text-filter';

/** Called lazily (never at module scope, see `app.ts`'s `buildNavGroups`). */
function contrainteTypeLabel(value: TypeContrainteAdHoc): string {
  switch (value) {
    case 'INDISPONIBILITE_FORCEE':
      return $localize`:@@adHoc.type.indisponibiliteForcee:Indisponibilité forcée`;
    case 'INCOMPATIBILITE':
      return $localize`:@@adHoc.type.incompatibilite:Incompatibilité`;
    case 'AFFECTATION_FORCEE':
      return $localize`:@@adHoc.type.affectationForcee:Affectation forcée`;
    case 'AFFINITE':
      return $localize`:@@adHoc.type.affinite:Affinité (paire à privilégier)`;
    case 'ARRIVEE_GROUPEE':
      return $localize`:@@adHoc.type.arriveeGroupee:Arrivée groupée (covoiturage, 2 à 4 animateurs)`;
  }
}

/**
 * CRUD of the manual adjustments — {@code ContrainteAdHoc} in the domain and on
 * the wire, « Ajustements manuels » on screen: what an organiser types here is
 * an exception to the plan, not one of the catalogue's rules, and the two used
 * to read as the same thing on the Contraintes screen next door.
 *
 * <p>The prescriptive ones are evaluated as hard constraints by the solver. The
 * backend only exposes POST (create or overwrite by id) and DELETE, so an edit
 * is always saved as a creation (see the form dialog).</p>
 *
 * <p>A grouped arrival a validated covoiturage stands behind
 * (`issueDeCovoiturage`) is read here and changed nowhere: its row links to
 * Disponibilités > Covoiturage, whose cancellation tells the group.</p>
 *
 * <p>The « Ajustements » tab of « Consignes au solveur » (issue #719): the page
 * carries the title and the subtitle. The network of pairs it used to draw
 * (`?vue=reseau`) is gone; its `?personne=` narrows the table instead — the
 * question it answered was « what binds this person ».</p>
 */
@Component({
  selector: 'app-ad-hoc-constraints-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
    TableFilter,
  ],
  templateUrl: './ad-hoc-constraints-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdHocConstraintsPage {
  /** An adjustment was written: the next solve has something new to respect. */
  readonly changed = output<void>();

  /** `?personne=`: the rows naming somebody whose name holds these words, or whose id this is. */
  protected readonly personne = signal('');

  protected readonly columns = ['id', 'type', 'animateurs', 'portee', 'raison', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /**
   * Holds `causeParContrainteAdHocId`: exceptions that contradict each other
   * are refused at entry time, so what this badges is what was recorded before
   * that check existed, or imported in one go — nothing else would ever point
   * at them.
   */
  protected readonly problemes = inject(ProblemesStore);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  private readonly injector = inject(Injector);
  private readonly pageTitle = viewChild<ElementRef<HTMLElement>>('pageTitle');
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  /**
   * `?ids=a,b`: the adjustments a problem named — two that contradict each
   * other, the ones a rule failed on — shown side by side, the others hidden
   * until « Tout afficher ». Empty means no narrowing.
   */
  protected readonly onlyIds = signal<string[]>(
    (currentViewParams().get('ids') ?? '')
      .split(',')
      .map((id) => id.trim())
      .filter((id) => id !== ''),
  );

  /** The rows on screen: every adjustment, or only the ones the URL named, narrowed to a person. */
  protected readonly rows = computed(() => {
    const ids = this.onlyIds();
    const personne = this.personne();
    return this.store
      .contraintes()
      .filter((contrainte) => ids.length === 0 || ids.includes(contrainte.id))
      .filter((contrainte) =>
        correspondAuFiltre(personne, [
          this.animateursLabel(contrainte),
          // An id too: the fiche of an animateur links here by id, never by name.
          ...(contrainte.animateursConcernes ?? []).map((animateur) => animateur.id),
        ]),
      );
  });

  /** The one-action reset of the narrowing. */
  protected showAll(): void {
    this.onlyIds.set([]);
    // The button disappears with the narrowing: the focus goes to the heading.
    afterNextRender(() => this.pageTitle()?.nativeElement.focus(), { injector: this.injector });
  }

  constructor() {
    this.personne.set(currentViewParams().get('personne') ?? '');
    keepViewInQueryParams(() => ({ personne: optionalParam(this.personne()) }));
    keepViewInQueryParams(() => ({ ids: optionalParam(this.onlyIds().join(',')) }));
    const chargement = this.crud.reload();
    void this.problemes.reloadFeasibility();
    // `?edit=<id>`: « Voir la fiche » on an adjustment saved with a warning
    // lands here with its form open, as it does on the other reference screens.
    consumeQueryParam('edit', async (edit) => {
      await chargement;
      const contrainte = this.store.contraintes().find((candidate) => candidate.id === edit);
      // A request-backed grouped arrival has no form here: the server would
      // refuse the save, and the Covoiturage tab is where it is cancelled.
      if (contrainte && !contrainte.issueDeCovoiturage) {
        this.openDialog(contrainte);
      }
    });
  }

  protected typeLabel(contrainte: ContrainteAdHoc): string {
    return contrainteTypeLabel(contrainte.type);
  }

  /** Animateur id → « Prénom Nom »: the adjustment names its people by id only. */
  private readonly nomsAnimateurs = computed(() => animateurNames(this.store.animateurs()));
  private readonly nomsStands = computed(() => standNames(this.store.stands()));

  protected animateursLabel(contrainte: ContrainteAdHoc): string {
    const ids = (contrainte.animateursConcernes ?? []).map((animateur) => animateur.id);
    return ids.length ? labelsOf(this.nomsAnimateurs(), ids).join(', ') : '—';
  }

  protected porteeLabel(contrainte: ContrainteAdHoc): string {
    const standId = contrainte.stand?.id;
    const nom = standId ? labelOf(this.nomsStands(), standId) : '';
    const scope = [
      this.creneauScopeLabel(contrainte.creneau),
      standId ? $localize`:@@adHoc.scope.stand:stand ${nom}:nom:` : '',
    ]
      .filter(Boolean)
      .join(' · ');
    return scope || '—';
  }

  /** The backend only sends the créneau id (see `ContrainteAdHoc`): resolve the human-readable slot from the reference store rather than showing that internal id. */
  private creneauScopeLabel(creneauRef: { id: number } | null): string {
    if (!creneauRef) {
      return '';
    }
    const creneau = this.store.creneaux().find((c) => c.id === creneauRef.id);
    if (!creneau) {
      return $localize`:@@adHoc.scope.creneauSupprime:créneau supprimé`;
    }
    return $localize`:@@adHoc.scope.creneau:créneau J${creneau.jour}:jour: · ${creneau.date}:date: ${creneau.heureDebut}:heureDebut:–${creneau.heureFin}:heureFin:`;
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(contrainte: ContrainteAdHoc): void {
    this.openDialog(contrainte);
  }

  private openDialog(contrainte: ContrainteAdHoc | null): void {
    this.dialog
      .open<AdHocConstraintFormDialog, AdHocConstraintFormData, boolean>(
        AdHocConstraintFormDialog,
        {
          data: { contrainte },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        },
      )
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.changed.emit();
        }
      });
  }

  protected async remove(contrainte: ContrainteAdHoc): Promise<void> {
    const removed = await this.crud.remove(
      'contraintes-ad-hoc',
      contrainte.id,
      $localize`:@@adHoc.entityLabel:Ajustement`,
      // Names animateurs: in the confirmation and the snack bar, never in the log.
      { name: { text: adHocDescription(contrainte, this.store.animateurs()), personal: true } },
    );
    if (removed) {
      this.changed.emit();
    }
  }
}
