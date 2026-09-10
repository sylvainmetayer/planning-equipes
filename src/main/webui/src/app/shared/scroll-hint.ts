import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { BRANDING } from '../core/branding';

/**
 * How many pixels of hidden content are worth pointing at. Below that, the
 * page is essentially fully visible and the hint would be noise — a couple of
 * pixels of rounding, a shadow, the last line of a footer.
 */
const SEUIL_PIXELS = 48;

/** Share of the visible height one click travels: enough to move, little enough to keep one's bearings. */
const PART_DEFILEE = 0.85;

/** The three numbers the decision rests on — an element supplies them as is. */
export interface MetriquesDefilement {
  scrollHeight: number;
  scrollTop: number;
  clientHeight: number;
}

/**
 * Whether enough content hides below the fold to be worth pointing at. Pure,
 * so the rule can be pinned down without a DOM: a few stray pixels are not
 * "there is more below", and a reader already at the bottom must not be told
 * to scroll.
 */
export function resteDuContenuPlusBas(metriques: MetriquesDefilement): boolean {
  return metriques.scrollHeight - metriques.scrollTop - metriques.clientHeight > SEUIL_PIXELS;
}

/**
 * Bottom-right hint saying "there is more below", shown only while content
 * actually extends past the fold.
 *
 * <p>Several screens end with something that matters and looks like nothing
 * from above — the feasibility banner under the solver actions, the constraint
 * breakdown under a score, the queue under the buttons. On a laptop, the page
 * looks finished. The hint hopping in the corner says otherwise, and clicking
 * takes the reader there rather than asking them to find the scrollbar of an
 * inner container.</p>
 *
 * <p>Fed by the element that actually scrolls, handed in by the shell: in this
 * layout that is Material's drawer content, not the window, so watching
 * {@code window.scrollY} would never fire.</p>
 */
@Component({
  selector: 'app-scroll-hint',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (visible()) {
      <button
        matIconButton
        type="button"
        class="scroll-hint"
        [matTooltip]="libelle"
        matTooltipPosition="left"
        [attr.aria-label]="libelle"
        (click)="descendre()"
      >
        <span class="scroll-hint-pile">
          @if (branding.mascotIconUrl) {
            <img [src]="branding.mascotIconUrl" alt="" class="scroll-hint-mascot" />
          }
          <mat-icon class="scroll-hint-arrow">keyboard_double_arrow_down</mat-icon>
        </span>
      </button>
    }
  `,
  styles: `
    .scroll-hint {
      position: fixed;
      right: 1.25rem;
      bottom: 1.25rem;
      z-index: 10;
      width: 3rem;
      height: 3rem;
      border-radius: 50%;
      background: var(--mat-sys-surface-container-high);
      box-shadow: var(--mat-sys-level2);
    }
    .scroll-hint-pile {
      display: flex;
      flex-direction: column;
      align-items: center;
      line-height: 1;
      animation: scroll-hint-saut 1.6s ease-in-out infinite;
    }
    .scroll-hint-mascot {
      width: 20px;
      height: 20px;
      object-fit: contain;
    }
    .scroll-hint-arrow {
      width: 16px;
      height: 16px;
      font-size: 16px;
      color: var(--mat-sys-primary);
    }
    @keyframes scroll-hint-saut {
      0%,
      100% {
        transform: translateY(0);
      }
      50% {
        transform: translateY(-25%);
      }
    }
    /* The hint must still be visible when animation is refused: only the hop goes. */
    @media (prefers-reduced-motion: reduce) {
      .scroll-hint-pile {
        animation: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScrollHint {
  protected readonly branding = inject(BRANDING);

  /** The element that scrolls — not necessarily the window (see the class doc). */
  readonly conteneur = input.required<HTMLElement>();

  protected readonly visible = signal(false);

  /** Built in the field initialiser, never at module scope: see `locale.ts`. */
  protected readonly libelle = $localize`:@@scrollHint.label:Du contenu se poursuit plus bas — cliquez pour y descendre`;

  constructor() {
    const destroyRef = inject(DestroyRef);
    effect((onCleanup) => {
      const element = this.conteneur();
      const recalculer = (): void => this.recalculer(element);

      element.addEventListener('scroll', recalculer, { passive: true });
      // The container's own box covers a window resize; its children cover the
      // page growing under it — data arriving, a panel unfolding, a route
      // swapping the whole content.
      const observer = new ResizeObserver(recalculer);
      observer.observe(element);
      for (const enfant of Array.from(element.children)) {
        observer.observe(enfant);
      }
      recalculer();

      onCleanup(() => {
        element.removeEventListener('scroll', recalculer);
        observer.disconnect();
      });
    });
    destroyRef.onDestroy(() => this.visible.set(false));
  }

  private recalculer(element: HTMLElement): void {
    this.visible.set(resteDuContenuPlusBas(element));
  }

  protected descendre(): void {
    const element = this.conteneur();
    element.scrollBy({ top: element.clientHeight * PART_DEFILEE, behavior: 'smooth' });
  }
}
