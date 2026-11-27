import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { HoursSource } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { errorText } from '../../core/resource-state';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
  NO_SORT,
  readSort,
  sortQueryParams,
  SortState,
} from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import { TableFilter } from '../../shared/table-filter';
import { planningDays } from '../journee/journee';
import { PlanningPersonneView } from '../planning-personne/planning-personne-vue';
import { readHoursSource } from '../planning-personne/planning-personne';

/**
 * The Diagnostic's Charge report: what each animateur was given over the
 * whole event — the hours (weeks, total, evening, Sunday, holiday, night), their
 * distance to the median, the demanding seats, the variety, the wishes honoured,
 * the days worked and off — with the two CSV exports, the payroll's and the
 * Équité's. It hosts the synthesis of the Planning page's « Par personne »
 * axis as it was: the same two reports, the same joins, the same columns, only
 * asked for here, where the fairness of a plan is read next to what blocks it.
 *
 * <p>Its view state (`sort`, `dir`, `colonnes`, `sansRepos`, `source`) is the
 * synthesis's own and lives in the address, next to the `onglet` the page writes.</p>
 */
@Component({
  selector: 'app-charge-tab',
  imports: [PlanningPersonneView, StatusMessage, TableFilter],
  template: `
    <app-table-filter
      [(value)]="filtre"
      label="Filtrer par nom"
      i18n-label="@@journee.filter.label"
      placeholder="Nom, stand…"
      i18n-placeholder="@@journee.filter.placeholder"
    />
    <app-status-message [text]="erreur()" tone="error" />
    <app-planning-personne-vue
      view="synthese"
      [planning]="planning()"
      [jours]="jours()"
      [filtre]="filtre()"
      [(tri)]="tri"
      [(allColumns)]="allColumns"
      [(noRestOnly)]="noRestOnly"
      [(hoursSource)]="hoursSource"
    />
  `,
  // Global by design (AGENTS.md): loaded with the route, unscoped like the view it hosts.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChargeTab {
  private readonly planningState = inject(PlanningStateService);

  /** The persisted plan, as the Planning page reads it: the one the cells and the counts are of. */
  private readonly plan = resource({ loader: () => this.planningState.loadForDisplay() });
  protected readonly planning = computed(() => (this.plan.hasValue() ? this.plan.value() : null));
  protected readonly jours = computed(() => planningDays(this.planning()?.postes ?? []));
  protected readonly erreur = errorText(this.plan);

  protected readonly filtre = signal('');
  protected readonly tri = signal<SortState>(NO_SORT);
  protected readonly allColumns = signal(false);
  protected readonly noRestOnly = signal(false);
  protected readonly hoursSource = signal<HoursSource | null>(null);

  constructor() {
    // The address bar, not the router's snapshot: this tab is built each time it is opened.
    const params = currentViewParams();
    this.filtre.set(params.get('q') ?? '');
    this.tri.set(readSort(params));
    this.allColumns.set(params.get('colonnes') === 'toutes');
    this.noRestOnly.set(params.get('sansRepos') === '1');
    this.hoursSource.set(readHoursSource(params.get('source')));
    keepViewInQueryParams(() => ({
      q: optionalParam(this.filtre()),
      ...sortQueryParams(this.tri()),
      colonnes: this.allColumns() ? 'toutes' : null,
      sansRepos: this.noRestOnly() ? '1' : null,
      source: this.hoursSource(),
    }));
  }
}
