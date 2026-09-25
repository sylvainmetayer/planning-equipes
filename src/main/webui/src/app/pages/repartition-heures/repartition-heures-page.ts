import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  resource,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { parseDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText } from '../../core/resource-state';
import { typologieLabels } from '../../core/typologie-colors';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import {
  GROUP_HEADER,
  HoursNode,
  NO_EMPLACEMENT,
  Rect,
  Tile,
  TreemapGrouping,
  TreemapPeriod,
  aggregateHours,
  coverageLevel,
  findPath,
  knownStands,
  layoutTiles,
  mondayOf,
  periodChoices,
  tableRows,
  withOthers,
} from './treemap';

/**
 * Size the tiles are laid out in until the treemap has been measured — and
 * under a test DOM, which never lays anything out. The box keeps this 5:3
 * ratio whatever its width (`aspect-ratio` in the stylesheet).
 */
const FALLBACK_WIDTH = 1000;
const FALLBACK_HEIGHT = 600;
/**
 * Rendered room, in rem, a tile needs to print its name and figures: its
 * padding and two lines of body-small, borders included. Under it the label
 * lives in the tooltip.
 */
const LABEL_MIN_WIDTH_REM = 5.625;
const LABEL_MIN_HEIGHT_REM = 2.75;
/** The group title's room, in rem: {@link GROUP_HEADER} at a 16 px root font. */
const GROUP_HEADER_REM = GROUP_HEADER / 16;

/** One option of the period selector. */
interface PeriodOption {
  value: string;
  label: string;
}

/**
 * « Répartition des heures »: what weighs in the edition, and where it
 * stalls, in one picture. A treemap of the seat-hours to staff — size is the
 * need, colour its coverage in the Heatmap's colours on thresholds of its
 * own (critique under 80 %) — grouped by emplacement
 * or by typologie combination, over the plan persisted. The geometry and the
 * aggregation are the pure `treemap.ts`; this component puts labels, a zoom
 * and a table around them.
 */
@Component({
  selector: 'app-repartition-heures-page',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './repartition-heures-page.html',
  styleUrl: './repartition-heures-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like a partial.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RepartitionHeuresPage {
  private readonly planningState = inject(PlanningStateService);
  private readonly reference = inject(ReferenceDataStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly treemapBox = viewChild<ElementRef<HTMLElement>>('treemap');
  /** The treemap's rendered size and the root font size, once measured; null before. */
  private readonly measured = signal<{ width: number; height: number; rem: number } | null>(null);

  protected readonly grouping = signal<TreemapGrouping>('stand');
  /** `all`, `w:<monday>` or `d:<date>` — the value of the period selector. */
  protected readonly periodValue = signal('all');
  /** An emplacement id, `aucun`, or `''` for every place. */
  protected readonly emplacementFilter = signal('');
  /** Id of the node the treemap is zoomed on; the root when empty or unknown. */
  protected readonly zoomId = signal('');

  protected readonly viewChanged = computed(
    () =>
      this.grouping() !== 'stand' ||
      this.periodValue() !== 'all' ||
      this.emplacementFilter() !== '' ||
      this.zoomId() !== '',
  );

  /**
   * The plan, and the referential it is read against: the stands (so a stand
   * with no seat in the period still has its 0 h line), and the typologie
   * labels. A referential that fails to load degrades to the stands the plan
   * carries and to raw typologie ids rather than failing the screen.
   */
  private readonly data = resource({
    loader: async () => {
      const [planning] = await Promise.all([
        this.planningState.loadForDisplay(),
        this.reference.reload(['stands', 'typologies']).catch(() => undefined),
      ]);
      return planning;
    },
  });
  protected readonly loading = this.data.isLoading;
  protected readonly error = errorText(this.data);
  private readonly postes = computed(() =>
    this.data.hasValue() ? (this.data.value()?.postes ?? []) : [],
  );
  protected readonly hasPlan = computed(() => this.postes().length > 0);

  private readonly period = computed<TreemapPeriod>(() => readPeriod(this.periodValue()));

  /** The full tree: what the table lists, 0 h stands included. */
  protected readonly tree = computed<HoursNode>(() =>
    aggregateHours({
      postes: this.postes(),
      stands: this.reference.stands(),
      grouping: this.grouping(),
      period: this.period(),
      emplacementId: this.emplacementFilter() || null,
      typologieLabels: typologieLabels(this.reference.typologies()),
      labels: {
        root: $localize`:@@repartitionHeures.root:Édition`,
        noEmplacement: $localize`:@@repartitionHeures.noEmplacement:Sans emplacement`,
        noTypologie: $localize`:@@repartitionHeures.noTypologie:Sans typologie`,
      },
    }),
  );

  /** The tree the treemap draws: slivers gathered under « Autres » where one child crushes the rest. */
  private readonly shownTree = computed(() =>
    withOthers(
      this.tree(),
      (count) => $localize`:@@repartitionHeures.others:Autres (${count}:count: stands)`,
    ),
  );

  /** Root first, the zoomed node last. An unknown zoom (a stale link) falls back to the root. */
  protected readonly path = computed<HoursNode[]>(() => {
    const tree = this.shownTree();
    return (this.zoomId() && findPath(tree, this.zoomId())) || [tree];
  });

  protected readonly zoomed = computed(() => this.path()[this.path().length - 1]);

  /** The tiles are laid out in rendered pixels, so a title's room and a label's are real ones. */
  private readonly bounds = computed<Rect>(() => {
    const size = this.measured();
    return {
      x: 0,
      y: 0,
      width: size?.width ?? FALLBACK_WIDTH,
      height: size?.height ?? FALLBACK_HEIGHT,
    };
  });
  private readonly rem = computed(() => this.measured()?.rem ?? 16);

  protected readonly tiles = computed<Tile[]>(() =>
    layoutTiles(this.zoomed(), this.bounds(), GROUP_HEADER_REM * this.rem()),
  );

  protected readonly rows = computed(() => tableRows(this.tree()));

  protected readonly periodOptions = computed(() => {
    const { weeks, days } = periodChoices(this.postes());
    return {
      weeks: weeks.map<PeriodOption>((monday) => ({
        value: `w:${monday}`,
        label: $localize`:@@repartitionHeures.period.week:Semaine du ${formatDate(monday)}:date:`,
      })),
      days: days.map<PeriodOption>((date) => ({
        value: `d:${date}`,
        label: formatDate(date, true),
      })),
    };
  });

  /** The emplacements the stands sit on, and « Sans emplacement » when some sit on none. */
  protected readonly emplacementOptions = computed(() => {
    const options = new Map<string, string>();
    let orphans = false;
    for (const stand of knownStands(this.reference.stands(), this.postes())) {
      if (stand.emplacement) {
        options.set(stand.emplacement.id, stand.emplacement.nom || stand.emplacement.id);
      } else {
        orphans = true;
      }
    }
    const sorted = [...options.entries()]
      .map(([value, label]) => ({ value, label }))
      .sort((left, right) => left.label.localeCompare(right.label));
    if (orphans) {
      sorted.push({
        value: NO_EMPLACEMENT,
        label: $localize`:@@repartitionHeures.noEmplacement:Sans emplacement`,
      });
    }
    return sorted;
  });

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.grouping.set(params.get('regroupement') === 'typologie' ? 'typologie' : 'stand');
    const date = params.get('date');
    const week = params.get('semaine');
    if (date && isDateKey(date)) {
      this.periodValue.set(`d:${date}`);
    } else if (week && isDateKey(week)) {
      // Any date of the week selects it: the selector only knows Mondays.
      this.periodValue.set(`w:${mondayOf(week)}`);
    }
    this.emplacementFilter.set(params.get('emplacement') ?? '');
    this.zoomId.set(params.get('zoom') ?? '');
    keepViewInQueryParams(() => {
      const period = this.period();
      return {
        regroupement: this.grouping() === 'stand' ? null : this.grouping(),
        semaine: period.kind === 'week' ? period.monday : null,
        date: period.kind === 'day' ? period.date : null,
        emplacement: this.emplacementFilter() || null,
        // The zoom asked for, not the one resolved: a link keeps it while the
        // plan loads or fails to, instead of losing it to the root fallback.
        zoom: this.zoomId() || null,
      };
    });
    // A zoom the loaded tree no longer holds — a group the new period or a
    // refresh emptied — is dropped, so it does not come back unasked later.
    effect(() => {
      const zoom = this.zoomId();
      if (zoom && this.data.hasValue() && !this.loading() && !findPath(this.shownTree(), zoom)) {
        this.zoomId.set('');
      }
    });
    this.measureTreemap();
  }

  /**
   * Follows the treemap's rendered size, so the layout reserves a group title
   * its real room and a tile shows its label only when it fits. Where there is
   * no ResizeObserver (a test DOM), the fallback size stands.
   */
  private measureTreemap(): void {
    effect((onCleanup) => {
      const box = this.treemapBox()?.nativeElement;
      const view = this.document.defaultView;
      if (!box || !view || typeof view.ResizeObserver === 'undefined') {
        return;
      }
      const observer = new view.ResizeObserver((entries) => {
        const rect = entries[0]?.contentRect;
        if (!rect || rect.width <= 0) {
          return;
        }
        const width = Math.round(rect.width);
        const height = Math.round(rect.height) || Math.round((width * 3) / 5);
        const rem = Number.parseFloat(
          view.getComputedStyle(this.document.documentElement).fontSize,
        );
        const current = this.measured();
        const next = { width, height, rem: rem > 0 ? rem : 16 };
        if (
          !current ||
          current.width !== next.width ||
          current.height !== next.height ||
          current.rem !== next.rem
        ) {
          this.measured.set(next);
        }
      });
      observer.observe(box);
      onCleanup(() => observer.disconnect());
    });
  }

  protected setGrouping(grouping: TreemapGrouping): void {
    this.grouping.set(grouping);
    // A group of the other reading does not exist here: back to the whole edition.
    this.zoomId.set('');
  }

  protected setPeriod(value: string): void {
    this.periodValue.set(value);
  }

  protected setEmplacement(value: string): void {
    this.emplacementFilter.set(value);
    this.zoomId.set('');
  }

  protected resetView(): void {
    this.grouping.set('stand');
    this.periodValue.set('all');
    this.emplacementFilter.set('');
    this.zoomId.set('');
  }

  protected refresh(): void {
    this.data.reload();
  }

  protected zoomTo(node: HoursNode): void {
    this.zoomId.set(node.kind === 'root' ? '' : node.id);
  }

  /** A group or « Autres » zooms in; a stand opens its day, on the period's date when it is one. */
  protected open(tile: Tile): void {
    const node = tile.node;
    if (node.kind !== 'stand' || !node.standId) {
      this.zoomTo(node);
      return;
    }
    const period = this.period();
    void this.router.navigate(['/journee'], {
      queryParams: { stand: node.standId, date: period.kind === 'day' ? period.date : null },
    });
  }

  /* ------------------------------ Drawing -------------------------------- */

  // Drawn in percentages of the laid-out size: between a resize and its
  // measure, the tiles stretch with the box rather than spill out of it.
  protected left(tile: Tile): string {
    return `${(tile.x / this.bounds().width) * 100}%`;
  }

  protected top(tile: Tile): string {
    return `${(tile.y / this.bounds().height) * 100}%`;
  }

  protected width(tile: Tile): string {
    return `${(tile.width / this.bounds().width) * 100}%`;
  }

  protected height(tile: Tile): string {
    return `${(tile.height / this.bounds().height) * 100}%`;
  }

  /** Rendered room enough for the name, the hours and the rate; otherwise they live in the tooltip. */
  protected labelled(tile: Tile): boolean {
    const rem = this.rem();
    return tile.width >= LABEL_MIN_WIDTH_REM * rem && tile.height >= LABEL_MIN_HEIGHT_REM * rem;
  }

  protected level(node: HoursNode): string {
    return coverageLevel(node.filledMinutes, node.requiredMinutes);
  }

  protected hours(minutes: number): string {
    const value = (minutes / 60).toLocaleString(intlLocale(), { maximumFractionDigits: 1 });
    return $localize`:@@repartitionHeures.hours:${value}:hours: h`;
  }

  /**
   * Floored, never rounded: 99,6 % must not print « 100 % » on a tile the
   * colour says is short.
   */
  protected rate(node: HoursNode): string {
    if (node.requiredMinutes <= 0) {
      return '—';
    }
    return `${Math.floor((node.filledMinutes / node.requiredMinutes) * 100)} %`;
  }

  protected share(node: HoursNode): string {
    const total = this.tree().requiredMinutes;
    if (total <= 0) {
      return '—';
    }
    return `${((node.requiredMinutes / total) * 100).toLocaleString(intlLocale(), {
      maximumFractionDigits: 1,
    })} %`;
  }

  /** Hover, focus and screen-reader text of a tile: everything the table says of it. */
  protected describe(node: HoursNode): string {
    const required = this.hours(node.requiredMinutes);
    const filled = this.hours(node.filledMinutes);
    const empty = this.hours(node.requiredMinutes - node.filledMinutes);
    const rate = this.rate(node);
    const share = this.share(node);
    return $localize`:@@repartitionHeures.tile.describe:${node.label}:label: — ${required}:required: à pourvoir, ${filled}:filled: pourvues, ${empty}:empty: vides (${rate}:rate:), ${share}:share: du total`;
  }

  /** What a click on the tile does, said to the screen reader after the figures. */
  protected action(node: HoursNode): string {
    return node.kind === 'stand'
      ? $localize`:@@repartitionHeures.tile.openDay:ouvrir la journée du stand`
      : $localize`:@@repartitionHeures.tile.zoom:zoomer sur ce groupe`;
  }
}

function readPeriod(value: string): TreemapPeriod {
  if (value.startsWith('w:')) {
    return { kind: 'week', monday: value.slice(2) };
  }
  if (value.startsWith('d:')) {
    return { kind: 'day', date: value.slice(2) };
  }
  return { kind: 'all' };
}

function isDateKey(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function formatDate(date: string, withWeekday = false): string {
  return parseDateKey(date).toLocaleDateString(intlLocale(), {
    weekday: withWeekday ? 'long' : undefined,
    day: 'numeric',
    month: 'long',
  });
}
