import { Location } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { currentViewParams, keepViewInQueryParams } from '../../core/view-query-params';
import { RelancerCalcul } from '../../shared/relancer-calcul';
import { AdHocConstraintsPage } from '../ad-hoc-constraints/ad-hoc-constraints-page';
import { ConsignesPage } from '../consignes/consignes-page';
import { VerrouillagesPage } from '../verrouillages/verrouillages-page';
import { OngletConsignesSolveur, readOngletConsignesSolveur } from './consignes-solveur';

/**
 * « Consignes au solveur » (issue #719): what the next solve must respect, in
 * three tabs carried by `?onglet=` — the adjustments (who goes where, or
 * never), the locks (what a solve must keep), the consignes (a band every
 * stand is shut on). Three screens of the same shape that used to stand side
 * by side in the menu, each defined against its neighbour; one page and one
 * subtitle now say what they have in common.
 *
 * <p>After any entry on a tab, « Relancer le calcul » is offered in place, in
 * its incremental form when a plan exists: an adjustment, a lock or a consigne
 * only invalidates what it touches.</p>
 */
@Component({
  selector: 'app-consignes-solveur-page',
  imports: [
    MatButtonToggleModule,
    MatIconModule,
    AdHocConstraintsPage,
    ConsignesPage,
    RelancerCalcul,
    VerrouillagesPage,
  ],
  templateUrl: './consignes-solveur-page.html',
  styleUrl: './consignes-solveur-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsignesSolveurPage {
  private readonly location = inject(Location);
  private readonly route = inject(ActivatedRoute);

  protected readonly onglet = signal<OngletConsignesSolveur>(
    readOngletConsignesSolveur(currentViewParams().get('onglet')),
  );

  /** Something the next solve must respect was written on this page. */
  protected readonly written = signal(false);

  constructor() {
    // Followed rather than read once, like Diagnostic: the palette's tab
    // entries navigate to this very route with another `onglet`, and the
    // router reuses the component. `replaceState` (ADR 0018) emits nothing
    // here, so the effect below cannot feed this subscription.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.onglet.set(readOngletConsignesSolveur(params.get('onglet')));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'ajustements' ? null : this.onglet(),
    }));
  }

  /**
   * A tab's own keys (`?personne=`, `?animateur=`, `?date=`) mean nothing to
   * the next one: the address is reset to the tab alone before the new tab
   * reads it, so it opens on its defaults.
   */
  protected changerOnglet(onglet: OngletConsignesSolveur): void {
    const [chemin] = this.location.path().split('?');
    this.location.replaceState(chemin || '/');
    this.onglet.set(onglet);
  }

  protected onChanged(): void {
    this.written.set(true);
  }
}
