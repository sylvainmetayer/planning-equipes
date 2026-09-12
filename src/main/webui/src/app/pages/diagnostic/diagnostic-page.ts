import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { BancDeTouchePage } from '../banc-de-touche/banc-de-touche-page';
import { FragilitePage } from '../fragilite/fragilite-page';
import { ProblemesPage } from '../problemes/problemes-page';
import { StaffingPage } from '../staffing/staffing-page';
import { OngletDiagnostic, readOnglet } from './diagnostic';

/**
 * « Diagnostic » : why the plan does not hold, and what would happen if — the
 * problems, the staffing need, the fragility and the bench, as four tabs of
 * one screen instead of four screens with four headings and four « Actualiser ».
 *
 * <p>Each tab is the screen it was, hosted without its heading; its own view
 * state (the fragility filters, the bench's créneau and stand) still lives in
 * the URL next to the `onglet` this page writes — `keepViewInQueryParams`
 * lets every writer own the keys it names. The analyses themselves are
 * untouched: this page decides which one is on screen, nothing else.</p>
 */
@Component({
  selector: 'app-diagnostic-page',
  imports: [
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    ProblemesPage,
    StaffingPage,
    FragilitePage,
    BancDeTouchePage,
  ],
  templateUrl: './diagnostic-page.html',
  styleUrl: './diagnostic-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DiagnosticPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly onglet = signal<OngletDiagnostic>('problemes');

  constructor() {
    // Followed rather than read once: a link inside the page — « Besoin en
    // animateurs » on the Problèmes tab — navigates to this very route with
    // another `onglet`, and the router reuses the component instead of building
    // it again. Read in the constructor alone, the address changed and the
    // screen did not, until an F5. `replaceState` (ADR 0018) emits nothing
    // here, so the effect below cannot feed this subscription.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.onglet.set(readOnglet(params.get('onglet')));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'problemes' ? null : this.onglet(),
    }));
  }

  protected changerOnglet(onglet: OngletDiagnostic): void {
    this.onglet.set(onglet);
  }
}
