import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute } from '@angular/router';
import { intlLocale } from '../../core/locale';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { APP_VERSION } from '../../version';
import { NEWS_COMMITS } from './news-data';
import {
  filterReleases,
  headingIcon,
  headingLabel,
  NEWS_HEADINGS,
  NewsHeading,
  readHeading,
  releases,
} from './news';

/**
 * What changed in the application, read off the repository's own history.
 *
 * <p>Nothing is fetched and nothing is stored server-side: the commits are
 * collected at build time by `scripts/generate-news.js` and shipped in this
 * route's lazy chunk. The screen is therefore as truthful as the deployed
 * build — the alternative, a page an operator updates by hand, is the one that
 * goes stale without anybody noticing.</p>
 *
 * <p>The headings are those of the release notes (`cliff.toml`, see
 * `news.ts`), so an operator reading « À surveiller » here reads the same
 * warning a release note gives them.</p>
 */
@Component({
  selector: 'app-news-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './news-page.html',
  styleUrl: './news-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewsPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly heading = signal<NewsHeading | 'all'>('all');
  protected readonly search = signal('');

  /** The whole history, cut into releases once: the data never changes at runtime. */
  private readonly all = releases(NEWS_COMMITS);

  protected readonly headings = NEWS_HEADINGS;
  protected readonly version = APP_VERSION;
  /** True when the build saw no git history at all — an export of the sources, a bare copy. */
  protected readonly historyMissing = NEWS_COMMITS.length === 0;

  protected readonly shown = computed(() =>
    filterReleases(this.all, this.heading(), this.search()),
  );
  protected readonly shownCount = computed(() =>
    this.shown().reduce((total, release) => total + release.count, 0),
  );

  /** True as soon as the screen shows something other than its default view. */
  protected readonly viewChanged = computed(
    () => this.heading() !== 'all' || this.search().trim() !== '',
  );

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.heading.set(readHeading(params.get('type')));
    this.search.set(params.get('q') ?? '');
    keepViewInQueryParams(() => ({
      type: this.heading() === 'all' ? null : this.heading(),
      q: optionalParam(this.search()),
    }));
  }

  protected reset(): void {
    this.heading.set('all');
    this.search.set('');
  }

  protected label(heading: NewsHeading): string {
    return headingLabel(heading);
  }

  protected icon(heading: NewsHeading): string {
    return headingIcon(heading);
  }

  /**
   * « 14 septembre 2026 ». The day is a **local** calendar date, so it is
   * rebuilt from its parts: `new Date('2026-09-14')` is UTC midnight, which
   * renders as the 13th anywhere west of Greenwich.
   */
  protected readableDay(day: string): string {
    const [year, month, dayOfMonth] = day.split('-').map(Number);
    return new Date(year, month - 1, dayOfMonth).toLocaleDateString(intlLocale(), {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  }

  /** « 14 sept. » — the year is already on the release's heading. */
  protected shortDay(day: string): string {
    const [year, month, dayOfMonth] = day.split('-').map(Number);
    return new Date(year, month - 1, dayOfMonth).toLocaleDateString(intlLocale(), {
      day: 'numeric',
      month: 'short',
    });
  }
}
