import { Injectable, signal } from '@angular/core';

import { APP_VERSION, REPO_URL } from '../version';
import { compareVersions, isReleaseTag } from './version-link';

/** A published version newer than the one running, and where to read about it. */
export interface AvailableUpdate {
  readonly version: string;
  readonly url: string;
}

/**
 * Whether a newer release than the running one has been published.
 *
 * <p>Asked of GitHub's public API, from the browser, once per page load — the
 * server never calls out (an instance may well be deployed without any egress),
 * and `connect-src https:` already lets the SPA reach it. It only applies to a
 * build that sits on a tag: a commit SHA is a development or staging build,
 * where « a newer version exists » is true every day and means nothing; there
 * the check is not even attempted.</p>
 *
 * <p>« Latest » is GitHub's own notion (`/releases/latest`: the most recent
 * published, non-pre-release release), which is what an operator can act on —
 * a tag with no release behind it has no notes to read yet. Every failure —
 * offline, rate-limited, unexpected body — is silent: the hint is a courtesy,
 * and nobody should learn from an error banner that their browser could not
 * reach GitHub.</p>
 */
@Injectable({ providedIn: 'root' })
export class UpdateCheckService {
  private readonly latest = signal<AvailableUpdate | null>(null);
  private started = false;

  /** The newer release, or null while unknown, absent, or not applicable. */
  readonly available = this.latest.asReadonly();

  /** Runs the check once; later calls are no-ops. Safe to call from any component. */
  check(currentVersion: string = APP_VERSION): void {
    if (this.started) {
      return;
    }
    this.started = true;
    if (!isReleaseTag(currentVersion)) {
      return;
    }
    void this.fetchLatest(currentVersion);
  }

  private async fetchLatest(currentVersion: string): Promise<void> {
    try {
      const response = await fetch(latestReleaseEndpoint(REPO_URL), {
        headers: { Accept: 'application/vnd.github+json' },
      });
      if (!response.ok) {
        return;
      }
      const body = (await response.json()) as { tag_name?: unknown; html_url?: unknown };
      const tag = typeof body.tag_name === 'string' ? body.tag_name : '';
      if (!isReleaseTag(tag) || compareVersions(tag, currentVersion) <= 0) {
        return;
      }
      const url =
        typeof body.html_url === 'string' && body.html_url.startsWith('https://github.com/')
          ? body.html_url
          : `${REPO_URL}/releases/tag/${tag}`;
      this.latest.set({ version: tag, url });
    } catch {
      // Offline, blocked, rate-limited: the hint simply does not show.
    }
  }
}

/** `https://github.com/o/r` → `https://api.github.com/repos/o/r/releases/latest`. */
export function latestReleaseEndpoint(repoUrl: string): string {
  return `${repoUrl.replace('https://github.com/', 'https://api.github.com/repos/')}/releases/latest`;
}
