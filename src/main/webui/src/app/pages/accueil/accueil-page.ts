import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ViewEncapsulation,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { EditionsApi } from '../../core/api/editions-api';
import { ConsignesStore } from '../../core/consignes.store';
import { errorText, retainedValue } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { buildLignes, buildToday, statutIcon, statutLabel, summarizeLignes } from './accueil';
import { coherenceGroups } from './coherence';
import { bandeLabel, libelleDate } from '../../core/consigne-wording';
import { GelReferentielCard } from '../../shared/gel-referentiel-card';
import { VoirPlanningButton } from '../../shared/voir-planning-button';

/**
 * « État de l'édition », the home screen (issue #485): the cycle of the
 * guide as a checklist, each line with its state, its figures and the screen
 * that moves it. One call, `GET /api/editions/courant/etat`; the states are
 * decided server-side so an assistant reading `etat_edition` sees the same
 * thing as the organiser.
 *
 * The screen only orders and words what it receives. Nothing is computed
 * here, and nothing is written: every action lives on the page each line
 * links to.
 */
@Component({
  selector: 'app-accueil-page',
  imports: [
    VoirPlanningButton,
    GelReferentielCard,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './accueil-page.html',
  styleUrl: './accueil-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccueilPage {
  private readonly editionsApi = inject(EditionsApi);
  private readonly jobs = inject(SolverJobService);
  private readonly consignes = inject(ConsignesStore);

  /** The consignes still to come or in force today (issue #4), worded for the banner; empty when none. */
  protected readonly consignesAVenir = computed(() => {
    const aujourdhui = this.consignes.aujourdhui();
    return this.consignes
      .consignes()
      .filter((consigne) => aujourdhui !== null && consigne.date >= aujourdhui)
      .sort((gauche, droite) => gauche.date.localeCompare(droite.date))
      .map((consigne) => ({
        date: consigne.date,
        libelleDate: libelleDate(consigne.date),
        bande: bandeLabel(consigne.fermetureDebut, consigne.fermetureFin),
        motif: consigne.motif,
      }));
  });

  private readonly etat = resource({ loader: () => this.editionsApi.etat() });
  /** Kept across a failed refresh: the checklist stays on screen behind the failure sentence. */
  protected readonly etatEdition = retainedValue(this.etat);
  protected readonly chargement = this.etat.isLoading;
  protected readonly erreur = errorText(this.etat);

  protected readonly lignes = computed(() => {
    const etat = this.etatEdition();
    return etat ? buildLignes(etat) : [];
  });
  protected readonly bilan = computed(() => summarizeLignes(this.lignes()));
  /** « À traiter aujourd'hui »: the subjects with something to say; the box is not drawn when empty. */
  protected readonly today = computed(() => {
    const etat = this.etatEdition();
    return etat ? buildToday(etat) : [];
  });

  /** Whether the coherence line is unfolded; its detail is only read then. */
  protected readonly coherenceOpen = signal(false);
  private readonly coherence = resource({
    params: () => (this.coherenceOpen() ? true : undefined),
    loader: () => this.editionsApi.coherence(),
  });
  protected readonly coherenceReport = retainedValue(this.coherence);
  protected readonly coherenceLoading = this.coherence.isLoading;
  protected readonly coherenceError = errorText(this.coherence);
  protected readonly coherenceGroups = computed(() => {
    const rapport = this.coherenceReport();
    return rapport ? coherenceGroups(rapport) : [];
  });

  protected readonly statutLabel = statutLabel;
  protected readonly statutIcon = statutIcon;

  constructor() {
    // A solve started from anywhere (this browser or another) moves three
    // lines at once: refresh once it lands. Unregistered with the page.
    //
    // Both kinds, as the shell and the two other screens already do: an
    // incremental replan rewrites the plan and its score just the same, and
    // listening for `SOLVE` alone left the checklist stale until an F5 —
    // « Corriger après un changement » is precisely what a reader watching this
    // page launches from another tab.
    const destroyRef = inject(DestroyRef);
    for (const type of ['SOLVE', 'SOLVE_INCREMENTAL'] as const) {
      destroyRef.onDestroy(this.jobs.onResult(type, () => this.etat.reload()));
    }
    void this.consignes.reload();
  }

  protected recharger(): void {
    this.etat.reload();
    if (this.coherenceOpen()) {
      this.coherence.reload();
    }
    void this.consignes.reload();
  }

  protected toggleCoherence(): void {
    this.coherenceOpen.update((open) => !open);
  }

  /** Whether the freeze line is unfolded into its switches (ADR 0052). */
  protected readonly gelOpen = signal(false);

  protected toggleGel(): void {
    this.gelOpen.update((open) => !open);
  }

  /** A switch moved: the line's own sentence and state follow at once, the panel still open. */
  protected gelChanged(): void {
    this.etat.reload();
  }
}
