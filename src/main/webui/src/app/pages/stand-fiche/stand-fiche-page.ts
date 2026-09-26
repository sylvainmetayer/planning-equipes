import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  resource,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, map } from 'rxjs';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { StandsApi } from '../../core/api/stands-api';
import { isInformationalAnomaly } from '../../core/horaire-stand';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { formatHeure } from '../../core/time-of-day';
import { typologieLabels } from '../../core/typologie-colors';
import { consumeQueryParam, readSort, sortQueryParams } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';
import { anomalyLabel, scheduleRows } from '../stands/stand-detail';
import { StandFormData, StandFormDialog } from '../stands/stand-form-dialog';
import { sortStands, standCoverage, standOpenings } from '../stands/stand-order';
import { StandGridEditor } from './stand-grid-editor';

/** One seat of this stand nobody holds, as the fiche links it to the Siège panel. */
interface EmptySeat {
  posteId: string;
  date: string | null;
  heureDebut: string;
  heureFin: string;
}

/**
 * « Fiche stand » (`/stands/:id`), the symmetric of the fiche animateur: one
 * stand on one page. The head says what it is — its game categories, its
 * location, its headcounts, its levels — and « Modifier l'identité » opens the
 * stand form cut down to those fields. Below, its grid day × timeslot, edited
 * in place and saved as the Horaires des stands grid saves it; the anomalies of its
 * openings; once a plan is computed, its seats, the empty ones opened in the
 * Siège panel of the Journée; « Comparer avec… » and the history of its
 * changes; and, folded, the « Règles » its grid compacts into, for whoever
 * reads or writes the condensed form.
 *
 * `?modifier=1` opens the identity form on arrival: the `?edit=` links of the
 * application land here.
 */
@Component({
  selector: 'app-stand-fiche-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
    StatusMessage,
    StandGridEditor,
  ],
  templateUrl: './stand-fiche-page.html',
  // The stand form's schedule sections draw with the stands' stylesheet.
  styleUrls: [
    '../../../styles/horaires-stand.css',
    '../../../styles/fiche.css',
    './stand-fiche-page.css',
  ],
  // Global by design (AGENTS.md): loaded with the route, unscoped like a partial.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandFichePage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly standsApi = inject(StandsApi);
  private readonly journeesTypesApi = inject(JourneesTypesApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly confirm = inject(ConfirmService);
  protected readonly store = inject(ReferenceDataStore);
  protected readonly problemes = inject(ProblemesStore);
  private readonly gel = injectGelReferentiel();

  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  /** A solve, or a STANDS freeze: the grid reads, it does not write (ADR 0052). */
  protected readonly gridLocked = computed(
    () => this.editingLocked() || this.gel.isFrozen('STANDS'),
  );

  protected readonly standId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: this.route.snapshot.paramMap.get('id') ?? '' },
  );
  protected readonly stand = computed<Stand | null>(
    () => this.store.stands().find((stand) => stand.id === this.standId()) ?? null,
  );
  /** The referential is read, and names nobody by this id. */
  protected readonly notFound = computed(
    () => this.store.stands().length > 0 && this.stand() === null && !this.storeLoading(),
  );
  private readonly storeLoad = resource({
    loader: () => this.store.reload(['stands', 'typologies', 'emplacements', 'creneaux']),
  });
  protected readonly storeLoading = this.storeLoad.isLoading;

  /** The openings of every stand: this one's row, its anomalies, and the columns of the grid. */
  private readonly openings = resource({
    params: () => ({ id: this.standId() }),
    loader: () => this.standsApi.openings(),
  });
  protected readonly rapport = computed(() =>
    this.openings.hasValue() ? this.openings.value() : null,
  );
  protected readonly openingsError = errorText(this.openings);
  protected readonly ligne = computed(
    () => this.rapport()?.stands.find((ligne) => ligne.standId === this.standId()) ?? null,
  );
  protected readonly joursOuverts = computed(
    () => this.ligne()?.jours.filter((jour) => jour.etat !== 'FERME').length ?? 0,
  );
  protected readonly anomalies = computed(
    () => this.rapport()?.anomalies.filter((anomalie) => anomalie.standId === this.standId()) ?? [],
  );

  /** Date → the day template's name, for the grid's rows; nothing when it cannot be read. */
  private readonly templatesData = resource({
    loader: () => this.journeesTypesApi.etat().catch(() => null),
  });
  protected readonly templates = computed<ReadonlyMap<string, string>>(() => {
    const etat = this.templatesData.hasValue() ? this.templatesData.value() : null;
    if (!etat) {
      return new Map();
    }
    const names = new Map(etat.journeesTypes.map((type) => [type.id, type.nom]));
    return new Map(
      etat.calendrier.map((entry) => [entry.date, names.get(entry.journeeTypeId) ?? '']),
    );
  });

  /** The persisted plan, for the seats of this stand; nothing before a solve, or when unreadable. */
  private readonly plan = resource({
    params: () => ({ id: this.standId() }),
    loader: () => this.planningState.loadForDisplay().catch(() => null),
  });
  private readonly planning = computed<PlanningEvenement | null>(() =>
    this.plan.hasValue() ? this.plan.value() : null,
  );
  private readonly seats = computed<PosteAffectation[]>(() =>
    (this.planning()?.postes ?? []).filter((poste) => poste.stand?.id === this.standId()),
  );
  /** A plan is computed once somebody holds a seat, anywhere. */
  protected readonly planComputed = computed(() =>
    (this.planning()?.postes ?? []).some((poste) => poste.animateur),
  );
  protected readonly filled = computed(
    () => this.seats().filter((poste) => poste.animateur).length,
  );
  protected readonly emptySeats = computed<EmptySeat[]>(() =>
    this.seats()
      .filter((poste) => !poste.animateur && poste.creneau)
      .map((poste) => ({
        posteId: poste.id,
        date: poste.creneau!.date ?? null,
        heureDebut: formatHeure(poste.heureDebutEffective ?? poste.creneau!.heureDebut),
        heureFin: formatHeure(poste.heureFinEffective ?? poste.creneau!.heureFin),
      }))
      .sort(
        (left, right) =>
          (left.date ?? '').localeCompare(right.date ?? '') ||
          left.heureDebut.localeCompare(right.heureDebut),
      ),
  );
  protected readonly cause = computed(
    () => this.problemes.causeParStandId().get(this.standId()) ?? null,
  );

  /* ------------------------------ the head ------------------------------ */

  protected readonly typologies = computed(() => {
    const labels = typologieLabels(this.store.typologies());
    return (this.stand()?.typologiesProposees ?? []).map((id) => ({
      id,
      label: labels.get(id) ?? id,
    }));
  });
  protected readonly rules = computed(() => {
    const stand = this.stand();
    return stand ? scheduleRows(stand) : [];
  });

  /** The Stands table's sort, carried in this page's URL: what « précédent / suivant » walks. */
  private readonly listSort = readSort(this.route.snapshot.queryParamMap);
  protected readonly listParams = Object.fromEntries(
    Object.entries(sortQueryParams(this.listSort)).filter(([, value]) => value !== null),
  );
  private readonly ordered = computed(() =>
    sortStands(this.store.stands(), this.listSort, {
      typologies: typologieLabels(this.store.typologies()),
      openings: standOpenings(this.rapport()),
      coverage: standCoverage(this.planning()),
    }),
  );
  protected readonly neighbours = computed(() => {
    const ordered = this.ordered();
    const index = ordered.findIndex((stand) => stand.id === this.standId());
    return {
      previous: index > 0 ? ordered[index - 1] : null,
      next: index >= 0 ? (ordered[index + 1] ?? null) : null,
    };
  });
  /** The other stands « Comparer avec… » offers. */
  protected readonly others = computed(() =>
    this.ordered().filter((stand) => stand.id !== this.standId()),
  );

  private readonly grid = viewChild(StandGridEditor);

  protected readonly anomalyLabel = anomalyLabel;
  protected readonly isInformationalAnomaly = isInformationalAnomaly;

  constructor() {
    void this.problemes.reloadFeasibility();
    // `?modifier=1`: the identity form, once the stand is known — where the
    // `?edit=` links of the application land.
    consumeQueryParam('modifier', async () => {
      await this.store.reload(['stands', 'typologies', 'emplacements']).catch(() => undefined);
      if (this.stand()) {
        await this.editIdentity();
      }
    });
  }

  /** Unsaved cells in the grid would vanish with the page: asked first. */
  async canLeave(): Promise<boolean> {
    if (!this.grid()?.modified()) {
      return true;
    }
    return this.confirm.ask({
      title: $localize`:@@standFiche.quitter.titre:Abandonner les cases modifiées ?`,
      message: $localize`:@@standFiche.quitter.message:La grille de ce stand a des cases modifiées non enregistrées.`,
      confirmLabel: $localize`:@@competences.quitter.label:Abandonner`,
      danger: true,
    });
  }

  /** « Modifier l'identité » : the stand form, cut down to the fields of its identity. */
  protected async editIdentity(): Promise<void> {
    await this.openForm(true);
  }

  /** « Modifier les règles » : the whole form, for whoever writes the condensed form. */
  protected async editRules(): Promise<void> {
    await this.openForm(false);
  }

  private async openForm(identityOnly: boolean): Promise<void> {
    const stand = this.stand();
    if (!stand) {
      return;
    }
    const saved = await firstValueFrom(
      this.dialog
        .open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
          data: { stand, identityOnly },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable',
        })
        .afterClosed(),
    );
    if (saved) {
      this.reloadOpenings();
    }
  }

  /** The grid was saved, or the schedule changed: the openings are read again. */
  protected reloadOpenings(): void {
    this.openings.reload();
    this.planningState.set(null);
  }

  /**
   * The grid was saved: the server rewrote the stand's rules, its headcounts
   * and its stamp. The head and the « Règles » read the stand again, and so
   * does the next « Modifier l'identité », whose save is refused on the
   * former stamp.
   */
  protected onGridSaved(): void {
    this.reloadOpenings();
    void this.store.reload(['stands']).catch(() => undefined);
  }

  /** « Comparer avec… » : the comparator, this stand as the reference. */
  protected compareWith(otherId: string | null): void {
    if (!otherId) {
      return;
    }
    const id = this.standId();
    void this.router.navigate(['/ouvertures'], {
      queryParams: { vue: 'comparer', stands: `${id},${otherId}`, ref: id },
    });
  }

  protected seatQuery(seat: EmptySeat): Record<string, string> {
    return seat.date ? { date: seat.date, siege: seat.posteId } : { siege: seat.posteId };
  }
}
