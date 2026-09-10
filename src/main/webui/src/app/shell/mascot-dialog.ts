import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatDialogModule } from '@angular/material/dialog';

import { BRANDING } from '../core/branding';

/**
 * Easter egg: the deployment's mascot, summoned by the Konami code (see
 * AdminShell). Nothing but the picture, centered — closing is the dialog's
 * usual Escape/backdrop click.
 *
 * <p>A mascot belongs to a customer the way a logo does, so an instance that
 * configured none never opens this dialog at all: AdminShell drops the code
 * on the floor rather than showing an empty frame.</p>
 */
@Component({
  selector: 'app-mascot-dialog',
  imports: [MatDialogModule],
  template: `
    <img
      class="mascot-apparition"
      [src]="branding.mascotUrl"
      width="320"
      i18n-alt="@@mascot.alt"
      alt="La mascotte du déploiement"
    />
  `,
  styles: `
    :host {
      display: block;
      padding: 1.5rem;
    }
    .mascot-apparition {
      display: block;
      max-width: min(60vw, 480px);
      height: auto;
      animation: mascot-pop 0.6s cubic-bezier(0.34, 1.56, 0.64, 1);
    }
    @keyframes mascot-pop {
      from {
        transform: scale(0.2) rotate(-25deg);
        opacity: 0;
      }
      to {
        transform: scale(1) rotate(0);
        opacity: 1;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .mascot-apparition {
        animation: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MascotDialog {
  protected readonly branding = inject(BRANDING);
}
