import { ChangeDetectionStrategy, Component } from '@angular/core';

import { versionUrl } from '../core/version-link';
import { APP_VERSION } from '../version';

/**
 * The version actually running, at the foot of every screen.
 *
 * <p>Until now it only showed on `/debug`, a screen an animateur never opens
 * and an administrator rarely does: "which version are you on?" had no answer
 * anybody could read off the page, and a bug report could not carry one. Both
 * shells end with this footer so the question is answered where the person
 * already is — the espace animateur included, since that is where most of the
 * people using the product spend their time.</p>
 *
 * <p>`APP_VERSION` is the tag when the build sits exactly on one and the short
 * commit otherwise (`scripts/generate-version.js`); `versionUrl` sends a tag to
 * its release page and a commit to its diff. `noreferrer` and not only
 * `noopener`: the espace animateur's URL carries the access token, and it must
 * not leave in a `Referer` header.</p>
 */
@Component({
  selector: 'app-version-footer',
  template: `
    <footer class="app-version-footer">
      <span i18n="@@footer.version">Version</span>
      <a [href]="url" target="_blank" rel="noopener noreferrer">{{ version }}</a>
    </footer>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VersionFooter {
  protected readonly version = APP_VERSION;
  protected readonly url = versionUrl(APP_VERSION);
}
