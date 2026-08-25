// "Pourquoi lui ?" popup for a single poste: which constraints its current
// occupant violates/respects, plus — on demand — the replacements that hold
// without introducing a hard violation.
// Complements the global diagnostic (constraints page): this is per-assignment
// explainability, computed on the already-solved planning the caller passes
// in — never triggers a solve.

import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AffectationExplanationService } from '../core/affectation-explanation.service';
import { formatDeltaScore } from '../core/score-format';
import { LegalText } from './legal-text';
import {
  AffectationExplanation,
  HardMediumSoftScore,
  PlanningEvenement,
  PosteAffectation,
  SuggestionReparation,
  SuggestionsReparation
} from '../core/models';
import { errorPrefix } from '../core/error-message';
import {
  compareDelta,
  meilleuresSuggestions,
  nomAnimateur,
  suggestionsTronquees
} from './affectation-explanation-rules';

export interface AffectationExplanationDialogData {
  poste: PosteAffectation;
  planning: PlanningEvenement;
}

@Component({
  selector: 'app-affectation-explanation-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    LegalText
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
                <strong><app-legal-text [text]="impact.description ?? impact.name" /></strong>
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

        <h3 i18n="@@affectationExplanation.repairTitle">Suggestions de réparation</h3>
        <p class="affectation-explanation-respected-count" i18n="@@affectationExplanation.repairHint">
          Cherche les remplaçants qui n'introduisent aucune violation dure, classés par impact sur le score.
        </p>
        <button matButton="tonal" [disabled]="suggestionsLoading()" (click)="chercherSuggestions()">
          <mat-icon>healing</mat-icon>
          <ng-container i18n="@@affectationExplanation.repairSearch">Chercher des remplaçants viables</ng-container>
        </button>

        @if (suggestionsLoading()) {
          <mat-spinner diameter="24" />
        } @else if (suggestionsError()) {
          <p class="affectation-explanation-error">{{ suggestionsError() }}</p>
        } @else if (suggestions(); as reparations) {
          <p class="affectation-explanation-respected-count" i18n="@@affectationExplanation.repairCost">
            {{ reparations.suggestions.length }} remplacement(s) viable(s), sur {{ reparations.candidatsEvalues }} candidat(s)
            évalué(s) parmi {{ reparations.candidatsEligibles }} éligible(s).
          </p>
          @if (tronquees()) {
            <p class="affectation-explanation-error" i18n="@@affectationExplanation.repairTruncated">
              Recherche arrêtée au plafond de {{ reparations.plafond }} candidats : ce sont les meilleurs de ceux évalués,
              pas une réponse exhaustive.
            </p>
          }
          @if (reparations.suggestions.length === 0) {
            <p i18n="@@affectationExplanation.repairNone">
              Aucun remplacement possible sans introduire de violation dure.
            </p>
          } @else {
            <ul class="affectation-explanation-list">
              @for (suggestion of meilleures(); track suggestion.animateurId) {
                <li>
                  <strong>{{ nom(suggestion.animateurId) }}</strong>
                  <span class="affectation-explanation-delta" [class]="'delta-' + sens(suggestion)">
                    {{ formatDelta(suggestion.delta) }}
                  </span>
                  @if (suggestion.violationsResolues.length > 0) {
                    <ul>
                      @for (impact of suggestion.violationsResolues; track impact.name) {
                        <li i18n="@@affectationExplanation.repairResolves">
                          règle : {{ impact.description ?? impact.name }}
                        </li>
                      }
                    </ul>
                  }
                  @if (suggestion.violationsIntroduites.length > 0) {
                    <ul>
                      @for (impact of suggestion.violationsIntroduites; track impact.name) {
                        <li i18n="@@affectationExplanation.repairIntroduces">
                          au prix de : {{ impact.description ?? impact.name }}
                        </li>
                      }
                    </ul>
                  }
                  <button matButton [disabled]="applicationEnCours()" (click)="appliquer(suggestion)">
                    <mat-icon>check</mat-icon>
                    <ng-container i18n="@@affectationExplanation.repairApply">Appliquer</ng-container>
                  </button>
                </li>
              }
            </ul>
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

  protected readonly suggestionsLoading = signal(false);
  protected readonly suggestionsError = signal('');
  protected readonly suggestions = signal<SuggestionsReparation | null>(null);
  protected readonly applicationEnCours = signal(false);

  protected readonly tronquees = computed(() => suggestionsTronquees(this.suggestions()));
  protected readonly meilleures = computed<SuggestionReparation[]>(() => meilleuresSuggestions(this.suggestions()));

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

  protected formatDelta(delta: HardMediumSoftScore): string {
    return formatDeltaScore(delta);
  }

  /** Display name of a suggestion's animateur — the server only sends the id. */
  protected nom(animateurId: string): string {
    return nomAnimateur(this.data.planning, animateurId);
  }

  protected sens(suggestion: SuggestionReparation): 'better' | 'worse' | 'same' {
    return compareDelta(suggestion.delta);
  }

  /**
   * On demand, never on open: the search costs one full score analysis per
   * candidate server-side, so it is not something to pay for every time a user
   * asks "pourquoi lui ?".
   */
  protected async chercherSuggestions(): Promise<void> {
    this.suggestionsLoading.set(true);
    this.suggestionsError.set('');
    try {
      this.suggestions.set(
        await this.explanationService.suggererReparations(this.data.planning, this.data.poste.id)
      );
    } catch (error) {
      this.suggestions.set(null);
      this.suggestionsError.set(errorPrefix(error));
    } finally {
      this.suggestionsLoading.set(false);
    }
  }

  /**
   * Applies one suggestion and closes, handing the caller what changed: the
   * persisted plan now differs from the one this dialog was opened on, so the
   * page behind has to reload rather than keep showing the pre-repair seat.
   */
  protected async appliquer(suggestion: SuggestionReparation): Promise<void> {
    this.applicationEnCours.set(true);
    this.suggestionsError.set('');
    try {
      await this.explanationService.appliquerReparation(this.data.poste.id, suggestion.animateurId);
      this.dialogRef.close({ posteId: this.data.poste.id, animateurId: suggestion.animateurId });
    } catch (error) {
      this.suggestionsError.set(errorPrefix(error));
    } finally {
      this.applicationEnCours.set(false);
    }
  }
}

/** What the dialog closes with once a repair was applied; `undefined` when it was only consulted. */
export interface ReparationAppliquee {
  posteId: string;
  animateurId: string;
}

// Re-exported so the two calendars keep importing it from here, next to the
// dialog it feeds; the rule itself lives in `affectation-explanation-rules.ts`.
export { aUneAppreciationPour } from './affectation-explanation-rules';

/**
 * Opens the "Pourquoi lui ?" dialog for one filled seat, offering every other
 * animateur with an appreciation for the poste's stand as a swap candidate —
 * the shared entry point of the day and month calendars.
 *
 * Returns the dialog ref: it closes with a {@link ReparationAppliquee} when the
 * repair assistant wrote to the persisted plan, and with `undefined` when the
 * dialog was only consulted. A caller that displays the plan has to reload on
 * the former.
 */
export function ouvrirExplication(
  dialog: MatDialog,
  planning: PlanningEvenement,
  poste: PosteAffectation
): MatDialogRef<AffectationExplanationDialog, ReparationAppliquee | undefined> {
  return dialog.open(AffectationExplanationDialog, {
    data: { poste, planning },
    width: '32rem'
  });
}
