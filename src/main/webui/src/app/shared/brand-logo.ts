import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { BRANDING } from '../core/branding';

/**
 * The deployment's logo, wherever a toolbar or a card shows one.
 *
 * <p>Six templates used to hard-code the same `<img src="logo.png">`, which is
 * six files to touch to deploy for another customer. They now share this
 * component, and it renders <b>nothing at all</b> when no logo is configured:
 * an instance with no mark of its own shows its name, never someone else's.</p>
 *
 * <p>The host is laid out with `display: contents` (styles/branding.css) so the
 * image stays the flex item its container styles — `.espace-toolbar .app-logo`
 * and friends keep working untouched.</p>
 */
@Component({
  selector: 'app-brand-logo',
  template: `
    @if (branding.logoUrl) {
      <img [src]="branding.logoUrl" [alt]="branding.productName" class="app-logo" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandLogo {
  protected readonly branding = inject(BRANDING);
}
