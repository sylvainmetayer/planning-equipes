import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { FeasibilityReport } from '../core/models';
import { niveauDeCause, niveauProblemeLabel } from '../core/problemes';
import { causesRestantesMessage, hardScoreNegativeMessage } from './feasibility-messages';

/** A still-violated hard constraint to list under the post-solve warning. */
export interface HardIssue {
  name: string;
  matchCount: number;
}

/** Beyond this, the banner stops listing and defers to the Problèmes page. */
const MAX_CAUSES_AFFICHEES = 5;

/**
 * Plain-language warning shown when a planning cannot be trusted as-is.
 *
 * Two distinct situations are covered, since neither alone tells the whole
 * story:
 * - `report`: a capacity estimate that needs no solve — it can say "réalisable"
 *   for a plan the solver still won't manage to bring to zero hard (it ignores
 *   the vacation découpage and legal constraints on minors). Its ranked
 *   `causes` name the créneaux and stands at fault; the most severe ones are
 *   listed here, the full list lives on the Problèmes page.
 * - `hardScore`: the hard score the solver actually reached. When it's
 *   negative, the persisted/returned plan really does still break a hard
 *   rule or leave a seat empty, whatever the capacity estimate said.
 *
 * Silent when both are clean, so it adds no noise on the nominal path.
 */
@Component({
  selector: 'app-feasibility-banner',
  imports: [MatCardModule, MatIconModule],
  template: `
    @if (infeasibleReport(); as report) {
      <mat-card appearance="outlined" class="feasibility-banner">
        <mat-card-content>
          <mat-icon>warning</mat-icon>
          <div>
            <p>{{ report.message }}</p>
            @if (causes().length > 0) {
              <ul>
                @for (cause of causes(); track cause.cle) {
                  <li>
                    <span
                      class="feasibility-severite"
                      [class.feasibility-severite-critique]="cause.critique"
                      >{{ cause.severiteLabel }}</span
                    >
                    {{ cause.message }}
                  </li>
                }
              </ul>
            }
            @if (causesRestantes(); as restantes) {
              <p class="feasibility-restantes">{{ restantes }}</p>
            }
          </div>
        </mat-card-content>
      </mat-card>
    } @else if (hardIssueMessage(); as message) {
      <mat-card appearance="outlined" class="feasibility-banner">
        <mat-card-content>
          <mat-icon>warning</mat-icon>
          <div>
            <p>{{ message }}</p>
            @if (hardIssues().length > 0) {
              <ul>
                @for (issue of hardIssues(); track issue.name) {
                  <li>{{ issue.name }} ({{ issue.matchCount }})</li>
                }
              </ul>
            }
          </div>
        </mat-card-content>
      </mat-card>
    }
  `,
  styles: `
    .feasibility-banner {
      --mdc-outlined-card-container-color: var(--mat-sys-error-container);
      margin-bottom: 1rem;
    }
    .feasibility-banner mat-card-content {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      color: var(--mat-sys-on-error-container);
    }
    .feasibility-banner mat-icon {
      color: var(--mat-sys-error);
      flex-shrink: 0;
    }
    .feasibility-banner p {
      margin: 0;
    }
    .feasibility-banner ul {
      margin: 0.5rem 0 0;
      padding-left: 1.25rem;
    }
    .feasibility-severite {
      font: var(--mat-sys-label-small);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      margin-right: 0.4rem;
    }
    .feasibility-severite-critique {
      font-weight: 700;
    }
    .feasibility-restantes {
      margin-top: 0.5rem;
      font: var(--mat-sys-body-small);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FeasibilityBanner {
  readonly report = input<FeasibilityReport | null>(null);
  /** Hard score of the last analysed solve, `null` when never analysed. */
  readonly hardScore = input<number | null>(null);
  /** Still-violated hard constraints, most useful ones to name in the message. */
  readonly hardIssues = input<HardIssue[]>([]);

  /** The report only when it warrants a warning, so the template stays a plain `@if`/`@else if`. */
  protected readonly infeasibleReport = computed(() => {
    const report = this.report();
    return report && !report.feasible ? report : null;
  });

  /** The most severe causes, already labelled: the template never calls a function per row. */
  protected readonly causes = computed(() => {
    const report = this.infeasibleReport();
    if (!report) {
      return [];
    }
    return (report.causes ?? []).slice(0, MAX_CAUSES_AFFICHEES).map((cause, index) => ({
      cle: `${index}-${cause.type}-${cause.creneauId ?? ''}`,
      critique: cause.severite === 'CRITIQUE',
      severiteLabel: niveauProblemeLabel(niveauDeCause(cause.severite)),
      message: cause.message,
    }));
  });

  /** Empty string (falsy, so the `@if` skips it) when every cause is listed. */
  protected readonly causesRestantes = computed(() => {
    const report = this.infeasibleReport();
    if (!report) {
      return '';
    }
    return causesRestantesMessage(report.totalCauses - this.causes().length);
  });

  protected readonly hardIssueMessage = computed(() => {
    const hardScore = this.hardScore();
    if (hardScore === null || hardScore >= 0) {
      return null;
    }
    return hardScoreNegativeMessage(hardScore);
  });
}
