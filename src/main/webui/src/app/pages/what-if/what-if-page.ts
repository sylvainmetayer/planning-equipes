import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { MutationsWhatIf, PlanningDiagnostic, ResultatWhatIf } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { SelectionRecherche } from '../../shared/selection-recherche';
import { StatusMessage } from '../../shared/status-message';

/**
 * "What if?" screen (issue #73): recruit three more, three cancel, close the
 * outdoor stand — and read the impact without touching a single row.
 *
 * Two levels, as the issue asks: the capacity check answers instantly on every
 * change, and a short solve — explicitly requested, duration shown — gives a
 * comparable score when the verdict alone is not enough.
 *
 * There is deliberately no "apply for real" button: this screen is a one-way
 * read of the referential, so a simulation can never overwrite it by accident.
 */
@Component({
  selector: 'app-what-if-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    FeasibilityBanner,
    SelectionRecherche,
    StatusMessage
  ],
  templateUrl: './what-if-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class WhatIfPage {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);

  protected readonly animateursAjoutes = signal(0);
  protected readonly animateursRetires = signal<string[]>([]);
  protected readonly standsFermes = signal<string[]>([]);
  protected readonly standCible = signal<string | null>(null);
  protected readonly effectifCible = signal<number | null>(null);

  protected readonly resultat = signal<ResultatWhatIf | null>(null);
  protected readonly chargement = signal(false);
  protected readonly error = signal('');
  protected readonly solveEnCours = signal(false);
  protected readonly scoreSimule = signal<string>('');

  /** Duration of the optional short solve, in seconds — shown, never hidden. */
  protected readonly secondesSolve = signal(30);

  private readonly api = inject(ApiService);
  private readonly solverSettings = inject(SolverSettingsService);

  protected readonly optionsAnimateurs = computed(() =>
    this.store.animateurs().map((animateur) => ({
      id: animateur.id,
      label: `${animateur.prenom} ${animateur.nom}`.trim() || animateur.id
    }))
  );

  protected readonly optionsStands = computed(() =>
    this.store.stands().map((stand) => ({ id: stand.id, label: stand.nom || stand.id }))
  );

  /** Spoken summary of the variant: the verdict is what the user came for. */
  protected readonly resumeSimulation = computed(() => {
    const resultat = this.resultat();
    if (!resultat) {
      return '';
    }
    return resultat.simulation.feasible
      ? $localize`:@@whatIf.resume.feasible:Variante réalisable : ${resultat.animateurs}:animateurs: animateurs pour ${resultat.standsOuverts}:stands: stands ouverts.`
      : $localize`:@@whatIf.resume.infeasible:Variante non réalisable : ${resultat.simulation.manqueAnimateurs}:manque: animateur(s) manquant(s) au pire créneau.`;
  });

  protected readonly messageScore = computed(() =>
    this.scoreSimule()
      ? $localize`:@@whatIf.solve.scoreMessage:Score de la variante : ${this.scoreSimule()}:score:`
      : ''
  );

  protected readonly deltaAnimateurs = computed(() => {
    const resultat = this.resultat();
    return resultat ? resultat.animateurs - resultat.animateursReference : 0;
  });

  protected readonly deltaStands = computed(() => {
    const resultat = this.resultat();
    return resultat ? resultat.standsOuverts - resultat.standsOuvertsReference : 0;
  });

  /** True when the variant is feasible and today's referential is not: the good news case. */
  protected readonly ameliore = computed(() => {
    const resultat = this.resultat();
    return !!resultat && resultat.simulation.feasible && !resultat.reference.feasible;
  });

  protected readonly degrade = computed(() => {
    const resultat = this.resultat();
    return !!resultat && !resultat.simulation.feasible && resultat.reference.feasible;
  });

  constructor() {
    void this.store.reload().then(() => this.simuler());
    // Same wiring as the constraints page: the score comes back through the
    // job's result, whichever browser is watching. Unregistered on destroy —
    // this page is lazy-loaded and rebuilt on every navigation.
    inject(DestroyRef).onDestroy(
      this.jobs.onResult('ANALYZE', (result) => {
        const diagnostic = result as PlanningDiagnostic | null;
        this.scoreSimule.set(diagnostic?.score ?? '');
        this.solveEnCours.set(false);
      })
    );
  }

  protected mutations(): MutationsWhatIf {
    const effectifsMin: Record<string, number> = {};
    const stand = this.standCible();
    const effectif = this.effectifCible();
    if (stand && effectif !== null && effectif > 0) {
      effectifsMin[stand] = effectif;
    }
    return {
      animateursAjoutes: this.animateursAjoutes(),
      animateursRetires: this.animateursRetires(),
      standsFermes: this.standsFermes(),
      effectifsMin
    };
  }

  /** Instant level: capacity check, no solve. */
  protected async simuler(): Promise<void> {
    this.chargement.set(true);
    this.error.set('');
    try {
      this.resultat.set(await this.api.post<ResultatWhatIf>('/api/what-if', this.mutations()));
    } catch (error) {
      this.resultat.set(null);
      this.error.set(this.messageErreur(error));
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * Second level: a short solve on the variant, giving a score comparable to a
   * real run's. Never persists anything — an ANALYZE job never does.
   */
  protected async analyser(): Promise<void> {
    this.solveEnCours.set(true);
    this.error.set('');
    this.scoreSimule.set('');
    try {
      await this.jobs.submitWhatIfAnalyze(this.mutations(), this.secondesSolve());
    } catch (error) {
      this.error.set(this.messageErreur(error));
      this.solveEnCours.set(false);
    }
  }

  protected reinitialiser(): void {
    this.animateursAjoutes.set(0);
    this.animateursRetires.set([]);
    this.standsFermes.set([]);
    this.standCible.set(null);
    this.effectifCible.set(null);
    this.scoreSimule.set('');
    void this.simuler();
  }

  protected dureeReelle(): number {
    return this.solverSettings.secondsLimit() ?? 0;
  }

  private messageErreur(error: unknown): string {
    return $localize`:@@common.errorPrefix:Erreur : ${error instanceof Error ? error.message : String(error)}:message:`;
  }
}
