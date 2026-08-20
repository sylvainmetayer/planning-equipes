import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatDialogModule } from '@angular/material/dialog';

/**
 * Easter egg: the festival mascot, summoned by the Konami code
 * (see AdminShell). Nothing but the picture, centered — closing is the
 * dialog's usual Escape/backdrop click.
 */
@Component({
  selector: 'app-mascotte-dialog',
  imports: [MatDialogModule],
  template: `
    <img
      class="mascotte-apparition"
      src="/mascotte.png"
      width="320"
      i18n-alt="@@mascotte.alt"
      alt="la mascotte du festival"
    />
  `,
  styles: `
    :host {
      display: block;
      padding: 1.5rem;
    }
    .mascotte-apparition {
      display: block;
      max-width: min(60vw, 480px);
      height: auto;
      animation: mascotte-pop 0.6s cubic-bezier(0.34, 1.56, 0.64, 1);
    }
    @keyframes mascotte-pop {
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
      .mascotte-apparition {
        animation: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MascotteDialog {}
