import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  resource,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PlanningApi } from '../../core/api/planning-api';
import { intlLocale } from '../../core/locale';
import { PlanSnapshot } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import { ComparaisonPanel } from '../versions/comparaison-panel';
import { CURRENT_PLAN, comparisonSides, readComparison } from '../versions/versions';

/**
 * The « Versions du plan » view of « Comparer deux versions »: two versions of
 * every edition, or the plan in place, picked in two lists; the older of the
 * two is the reference (A), whatever the order picked. The pair rides in
 * `?comparer=a,b`, which is also what the Versions screen opens it with.
 */
@Component({
  selector: 'app-comparer-versions-view',
  imports: [
    ComparaisonPanel,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    StatusMessage,
  ],
  templateUrl: './comparer-versions-view.html',
  styleUrl: './comparer-versions-view.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparerVersionsView {
  private readonly api = inject(PlanningApi);

  private readonly initial = readComparison(currentViewParams().get('comparer'));
  protected readonly first = signal<string | null>(this.initial[0] ?? null);
  protected readonly second = signal<string | null>(this.initial[1] ?? null);

  /** The versions of every edition: a variant of an edition is another edition. */
  private readonly list = resource({ loader: () => this.api.comparableSnapshots() });
  protected readonly versions = retainedValue(this.list);
  protected readonly chargement = this.list.isLoading;
  protected readonly erreur = errorText(this.list);

  protected readonly current = CURRENT_PLAN;
  protected readonly sides = computed(() => {
    const [a, b] = [this.first(), this.second()];
    return a && b && a !== b ? comparisonSides([a, b], this.versions() ?? []) : null;
  });
  /** The older side, named: the table reads from it, whichever list it was picked in. */
  protected readonly reference = computed(() => {
    const base = this.sides()?.base;
    const version = (this.versions() ?? []).find((each) => String(each.id) === base);
    return version ? this.label(version) : null;
  });
  protected readonly sameTwice = computed(() => !!this.first() && this.first() === this.second());

  constructor() {
    keepViewInQueryParams(() => ({
      comparer: optionalParam(
        this.first() && this.second() ? `${this.first()},${this.second()}` : null,
      ),
    }));
  }

  protected choose(side: 'first' | 'second', value: string): void {
    (side === 'first' ? this.first : this.second).set(optionalParam(value));
  }

  private readonly firstList = viewChild<ElementRef<HTMLSelectElement>>('firstList');

  /** The panel and its « Fermer » go away: focus returns to where a new pair starts. */
  protected close(): void {
    this.first.set(null);
    this.second.set(null);
    this.firstList()?.nativeElement.focus();
  }

  /** « Name — date (edition) »: the edition is said because the lists span all of them. */
  protected label(version: PlanSnapshot): string {
    const date = version.creeLe ? new Date(version.creeLe).toLocaleString(intlLocale()) : '';
    return `${version.libelle} — ${date}${version.editionNom ? ` (${version.editionNom})` : ''}`;
  }
}
