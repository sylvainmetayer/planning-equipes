import { Injectable, inject, signal } from '@angular/core';

import { APP_VERSION, REPO_URL } from '../version';
import { AdminApi } from './api/admin-api';
import { opensAdministration } from './session';
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
 * <p><b>Only an authenticated administrator's browser asks.</b> The admin shell
 * carries no route guard — it renders, then the first 401 sends the visitor to
 * /login — so anybody landing on the application would otherwise have reached
 * GitHub before that redirect: an animateur's (often a minor's) IP address
 * handed to a third party for nothing, and, since GitHub rate-limits anonymous
 * calls per IP, a shared egress that silently 403s the real administrators out
 * of the hint. The session is therefore confirmed against `/api/auth/me` —
 * public by design, so an anonymous caller reads « not logged in » rather than
 * a 401 — before anything leaves for github.com.</p>
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
  private readonly adminApi = inject(AdminApi);

  private readonly latest = signal<AvailableUpdate | null>(null);
  /** Set once GitHub has been asked, or once we know it never will be on this build. */
  private done = false;
  /** The check in flight, so two components starting at once ask one question. */
  private pending: Promise<void> | null = null;

  /** The newer release, or null while unknown, absent, or not applicable. */
  readonly available = this.latest.asReadonly();

  /**
   * Runs the check once. Calling it again is free — from every component that
   * wants it, and on every construction of the shell.
   */
  check(currentVersion: string = APP_VERSION): void {
    if (this.done || this.pending) {
      return;
    }
    if (!isReleaseTag(currentVersion)) {
      this.done = true;
      return;
    }
    this.pending = this.checkAsAdmin(currentVersion).finally(() => (this.pending = null));
  }

  /**
   * The session probe, then — and only then — the question put to GitHub.
   *
   * <p>A refusal here does <b>not</b> close the matter, which is the whole
   * reason this holds two flags rather than a single « already started ». The
   * ordinary way into the application is precisely the one that fails it: the
   * shell renders without a session, is refused, redirects to /login, and the
   * login then navigates back to the shell <i>without reloading the page</i>.
   * Latching on that first refusal would therefore hide the hint from every
   * administrator who logged in, until they thought to press F5.</p>
   */
  private async checkAsAdmin(currentVersion: string): Promise<void> {
    try {
      // Signed in is not enough: under Keycloak an animateur has a session too,
      // and the hint is an administrator's business.
      if (!opensAdministration(await this.adminApi.session())) {
        return;
      }
    } catch {
      // No session to speak of: the visitor is on their way to /login.
      return;
    }
    // GitHub is asked at most once per page load, whatever it answers.
    this.done = true;
    await this.fetchLatest(currentVersion);
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
