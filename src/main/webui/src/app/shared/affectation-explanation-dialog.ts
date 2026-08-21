// "Pourquoi lui ?" popup for a single poste: which constraints its current
// occupant violates/respects, plus an on-demand simulation of handing the
// same poste to a different animateur, with the resulting score delta.
// Complements the global diagnostic (constraints page): this is per-assignment
// explainability, computed on the already-solved planning the caller passes
// in — never triggers a solve.

import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { AffectationExplanationService } from '../core/affectation-explanation.service';
import { formatDeltaScore } from '../core/score-format';
import {
  AffectationExplanation,
  Animateur,
  ContrainteImpact,
  HardMediumSoftScore,
  PlanningFestival,
  PosteAffectation,
  Stand,
  SwapSimulation
} from '../core/models';
import { errorPrefix } from '../core/error-message';

export interface AffectationExplanationDialogData {
  poste: PosteAffectation;
  planning: PlanningFestival;
  /** Other animateurs offered as swap candidates (typically: competent for this poste's stand). */
  candidats: Animateur[];
}

@Component({
  selector: 'app-affectation-explanation-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    FormsModule
  ],
  template: `
    <h2 mat-dialog-title>
      {{ data.poste.stand?.nom }}
      @if (data.poste.animateur) {
        — {{ data.poste.animateur.prenom }} {{ data.poste.animateur.nom }}
      }
    </h2>
    <mat-dialog-content>
      @if (loading()) {
        <mat-spinner diameter="32" />
      } @else if (error()) {
        <p class="affectation-explanation-error">{{ error() }}</p>
      } @else if (explanation(); as explication) {
        <p class="affectation-explanation-score" i18n="@@affectationExplanation.score">
          Score global :
          <strong>{{ explication.score.hardScore }}hard / {{ explication.score.mediumScore }}medium / {{ explication.score.softScore }}soft</strong>
        </p>

        @if (explication.contraintesViolees.length > 0) {
          <h3 i18n="@@affectationExplanation.violatedTitle">Contraintes violées pour ce poste</h3>
          <ul class="affectation-explanation-list">
            @for (impact of explication.contraintesViolees; track impact.name) {
              <li>
                <span class="affectation-explanation-badge" [class]="'niveau-' + (impact.niveau ?? 'inconnu')">{{ impact.niveau }}</span>
                <strong>{{ impact.description ?? impact.name }}</strong>
                @if (impact.details.length > 0) {
                  <ul>
                    @for (detail of impact.details; track detail) {
                      <li>{{ detail }}</li>
                    }
                  </ul>
                }
              </li>
            }
          </ul>
        } @else {
          <p i18n="@@affectationExplanation.noViolation">Aucune contrainte violée détectée pour ce poste.</p>
        }

        <p class="affectation-explanation-respected-count" i18n="@@affectationExplanation.respectedCount">
          {{ explication.contraintesRespectees.length }} autre(s) contrainte(s) sans violation détectée pour ce poste.
        </p>

        @if (data.candidats.length > 0) {
          <h3 i18n="@@affectationExplanation.swapTitle">Simuler un remplacement</h3>
          <mat-form-field appearance="outline">
            <mat-label i18n="@@affectationExplanation.swapLabel">Remplacer par…</mat-label>
            <mat-select [(ngModel)]="candidatId" (ngModelChange)="onCandidatChange($event)">
              @for (candidat of data.candidats; track candidat.id) {
                <mat-option [value]="candidat.id">{{ candidat.prenom }} {{ candidat.nom }}</mat-option>
              }
            </mat-select>
          </mat-form-field>

          @if (simulationLoading()) {
            <mat-spinner diameter="24" />
          } @else if (simulationError()) {
            <p class="affectation-explanation-error">{{ simulationError() }}</p>
          } @else if (simulation(); as simulation) {
            <p class="affectation-explanation-delta" [class]="'delta-' + deltaSens()">
              <ng-container i18n="@@affectationExplanation.delta">Delta de score :</ng-container>
              <strong>{{ formatDelta(simulation.delta) }}</strong>
            </p>
            @if (violationsResolues().length > 0) {
              <p i18n="@@affectationExplanation.resolved">Violations résolues par ce remplacement :</p>
              <ul class="affectation-explanation-list">
                @for (impact of violationsResolues(); track impact.name) {
                  <li>{{ impact.description ?? impact.name }}</li>
                }
              </ul>
            }
            @if (nouvellesViolations().length > 0) {
              <p i18n="@@affectationExplanation.newViolations">Nouvelles violations introduites par ce remplacement :</p>
              <ul class="affectation-explanation-list">
                @for (impact of nouvellesViolations(); track impact.name) {
                  <li>{{ impact.description ?? impact.name }}</li>
                }
              </ul>
            }
          }
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="dialogRef.close()" i18n="@@violationDetails.close">Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .affectation-explanation-error {
      color: var(--mat-sys-error);
    }
    .affectation-explanation-score {
      color: var(--mat-sys-on-surface-variant);
    }
    .affectation-explanation-list {
      margin: 0 0 1rem;
      padding-left: 1.25rem;
    }
    .affectation-explanation-list li {
      margin-bottom: 0.5rem;
    }
    .affectation-explanation-badge {
      display: inline-block;
      font-size: 0.75rem;
      font-weight: 600;
      padding: 0.1rem 0.4rem;
      border-radius: 4px;
      margin-right: 0.5rem;
      background: var(--mat-sys-surface-variant);
    }
    .affectation-explanation-badge.niveau-HARD {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
    .affectation-explanation-respected-count {
      color: var(--mat-sys-on-surface-variant);
      font-style: italic;
    }
    mat-form-field {
      width: 100%;
      max-width: 20rem;
      display: block;
    }
    .affectation-explanation-delta.delta-better {
      color: var(--mat-sys-primary);
    }
    .affectation-explanation-delta.delta-worse {
      color: var(--mat-sys-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AffectationExplanationDialog {
  protected readonly dialogRef = inject<MatDialogRef<AffectationExplanationDialog>>(MatDialogRef);
  protected readonly data = inject<AffectationExplanationDialogData>(MAT_DIALOG_DATA);
  private readonly explanationService = inject(AffectationExplanationService);

  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly explanation = signal<AffectationExplanation | null>(null);

  protected readonly candidatId = signal<string | null>(null);
  protected readonly simulationLoading = signal(false);
  protected readonly simulationError = signal('');
  protected readonly simulation = signal<SwapSimulation | null>(null);

  protected readonly deltaSens = computed<'better' | 'worse' | 'same'>(() => {
    const delta = this.simulation()?.delta;
    return delta ? compareDelta(delta) : 'same';
  });

  protected readonly violationsResolues = computed<ContrainteImpact[]>(() => {
    const simulation = this.simulation();
    if (!simulation) {
      return [];
    }
    const nomsApres = new Set(simulation.contraintesVioleesApres.map((impact) => impact.name));
    return simulation.contraintesVioleesAvant.filter((impact) => !nomsApres.has(impact.name));
  });

  protected readonly nouvellesViolations = computed<ContrainteImpact[]>(() => {
    const simulation = this.simulation();
    if (!simulation) {
      return [];
    }
    const nomsAvant = new Set(simulation.contraintesVioleesAvant.map((impact) => impact.name));
    return simulation.contraintesVioleesApres.filter((impact) => !nomsAvant.has(impact.name));
  });

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const explication = await this.explanationService.explique(this.data.planning, this.data.poste.id);
      this.explanation.set(explication);
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected async onCandidatChange(animateurId: string | null): Promise<void> {
    this.simulation.set(null);
    this.simulationError.set('');
    if (!animateurId) {
      return;
    }
    this.simulationLoading.set(true);
    try {
      const simulation = await this.explanationService.simulerSwap(this.data.planning, this.data.poste.id, animateurId);
      this.simulation.set(simulation);
    } catch (error) {
      this.simulationError.set(errorPrefix(error));
    } finally {
      this.simulationLoading.set(false);
    }
  }

  protected formatDelta(delta: HardMediumSoftScore): string {
    return formatDeltaScore(delta);
  }
}

/** Lexicographic hard > medium > soft comparison, matching how Timefold itself compares scores. */
function compareDelta(delta: HardMediumSoftScore): 'better' | 'worse' | 'same' {
  if (delta.hardScore !== 0) {
    return delta.hardScore > 0 ? 'better' : 'worse';
  }
  if (delta.mediumScore !== 0) {
    return delta.mediumScore > 0 ? 'better' : 'worse';
  }
  if (delta.softScore !== 0) {
    return delta.softScore > 0 ? 'better' : 'worse';
  }
  return 'same';
}


/** True when the animateur holds an appreciation on at least one typologie this stand offers. */
export function aUneAppreciationPour(animateur: Animateur, stand: Stand): boolean {
  return stand.typologiesProposees.some((typologie) => typologie in (animateur.competences ?? {}));
}

/**
 * Opens the "Pourquoi lui ?" dialog for one filled seat, offering every other
 * animateur with an appreciation for the poste's stand as a swap candidate —
 * the shared entry point of the day and month calendars.
 */
export function ouvrirExplication(dialog: MatDialog, planning: PlanningFestival, poste: PosteAffectation): void {
  const candidats = (planning.animateurs ?? []).filter(
    (animateur) =>
      animateur.id !== poste.animateur?.id && poste.stand && aUneAppreciationPour(animateur, poste.stand)
  );
  dialog.open(AffectationExplanationDialog, {
    data: { poste, planning, candidats },
    width: '32rem'
  });
}
