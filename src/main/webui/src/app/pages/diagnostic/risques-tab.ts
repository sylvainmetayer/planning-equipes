import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  Injector,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { consumeQueryParam } from '../../core/view-query-params';
import { FormationPage } from '../formation/formation-page';
import { FragilitePage } from '../fragilite/fragilite-page';
import { TensionTab } from '../marge/tension-tab';
import { SECTIONS_RISQUES, SectionRisques } from './diagnostic';

/**
 * The Diagnostic's Risques tab: where the plan is fragile, whom to train, and
 * where it is tight — the three screens that read the same shortage from three
 * sides, stacked in that order. Each keeps its own analysis and its own view
 * state in the address; this component only stacks them and obeys
 * `section=fragilite|former|tension`, a landing that scrolls to one once the
 * sections above it have their figures, then leaves the address.
 */
@Component({
  selector: 'app-risques-tab',
  imports: [FragilitePage, FormationPage, TensionTab],
  templateUrl: './risques-tab.html',
  styleUrl: './risques-tab.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the tabs it hosts.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RisquesTab {
  private readonly injector = inject(Injector);
  private readonly fragilite = viewChild(FragilitePage);
  private readonly formation = viewChild(FormationPage);
  private readonly tension = viewChild(TensionTab);

  constructor() {
    consumeQueryParam('section', (section) =>
      (SECTIONS_RISQUES as readonly string[]).includes(section)
        ? this.scrollToOnceLoaded(section as SectionRisques)
        : undefined,
    );
  }

  /** Scrolls once the sections above the target have loaded: their rows push it down meanwhile. */
  private scrollToOnceLoaded(section: SectionRisques): Promise<void> {
    return new Promise((resolve) => {
      const loaded = effect(
        () => {
          // A section not drawn yet counts as loading; the ones below the target do not matter.
          const loading = [
            this.fragilite()?.chargement() ?? true,
            this.formation()?.chargement() ?? true,
            this.tension()?.loading() ?? true,
          ];
          if (loading.slice(0, SECTIONS_RISQUES.indexOf(section)).some(Boolean)) {
            return;
          }
          loaded.destroy();
          afterNextRender(
            () => {
              const target = document.getElementById(`risques-${section}`);
              target?.scrollIntoView({ block: 'start' });
              // The reading resumes at the section landed on, not at the top of the page.
              target?.focus({ preventScroll: true });
              resolve();
            },
            { injector: this.injector },
          );
        },
        { injector: this.injector },
      );
    });
  }
}
