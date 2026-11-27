import { ChangeDetectionStrategy, Component, inject, ViewEncapsulation } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { intlLocale } from '../../core/locale';
import { NewsSeenService } from '../../core/news-seen';
import { APP_VERSION } from '../../version';
import { NEWS_COMMITS } from './news-data';
import { headingIcon, headingLabel, highlights, isPreRelease, NewsHeading, releases } from './news';

/**
 * What changed in the application, read off the repository's own history.
 *
 * <p>Nothing is fetched and nothing is stored server-side: the commits are
 * collected at build time by `scripts/generate-news.js` and shipped in this
 * route's lazy chunk. The screen is therefore as truthful as the deployed
 * build — the alternative, a page an operator updates by hand, is the one that
 * goes stale without anybody noticing.</p>
 *
 * <p>A plain list of versions, newest first: each shows its number, its date
 * and its highlights — the headings are those of the release notes
 * (`cliff.toml`, see `news.ts`), so an operator reading « À surveiller » here
 * reads the same warning a release note gives them. A `-rc.N` version is
 * announced as a pre-release.</p>
 */
@Component({
  selector: 'app-news-page',
  imports: [MatCardModule, MatIconModule],
  templateUrl: './news-page.html',
  styleUrl: './news-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewsPage {
  /** The whole history, cut into releases once: the data never changes at runtime. */
  protected readonly shown = highlights(releases(NEWS_COMMITS));

  protected readonly version = APP_VERSION;
  /** True when the build saw no git history at all — an export of the sources, a bare copy. */
  protected readonly historyMissing = NEWS_COMMITS.length === 0;

  constructor() {
    // Opening the page is reading the news: the marker on Aide goes out (#706).
    inject(NewsSeenService).markSeen();
  }

  protected preRelease(version: string | null): boolean {
    return isPreRelease(version);
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
