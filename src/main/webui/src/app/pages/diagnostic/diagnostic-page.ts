import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { ProblemesStore } from '../../core/problemes.store';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { FragilitePage } from '../fragilite/fragilite-page';
import { TensionTab } from '../marge/tension-tab';
import { ProblemesPage } from '../problemes/problemes-page';
import { StaffingPage } from '../staffing/staffing-page';
import { OngletDiagnostic, readOnglet } from './diagnostic';

/**
 * « Diagnostic » : what blocks, what is missing, where it is tight, what is
 * fragile — four tabs of one screen instead of the six screens they were.
 * The bench, once a tab, is the Siège panel's « Qui peut tenir ce siège ? »;
 * « À former » is the foot of Besoin, whose « avant » margin is a column; the
 * former Marge disponible screen's « après » and « tension » readings are
 * the Tension tab.
 *
 * <p>Each tab is the screen it was, hosted without its heading; its own view
 * state (the fragility filters) still lives in
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
    TensionTab,
    FragilitePage,
  ],
  templateUrl: './diagnostic-page.html',
  styleUrl: './diagnostic-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DiagnosticPage {
  private readonly route = inject(ActivatedRoute);
  private readonly problemes = inject(ProblemesStore);

  protected readonly onglet = signal<OngletDiagnostic>('problemes');

  /** « Problèmes · 8 », once the list has been read by the tab; nothing before. */
  protected readonly problemCount = computed(() => this.problemes.comptage().total);

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
