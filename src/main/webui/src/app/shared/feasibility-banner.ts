import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { FeasibilityReport } from '../core/models';
import { hardScoreNegativeMessage } from './feasibility-messages';

/** A still-violated hard constraint to list under the post-solve warning. */
export interface HardIssue {
  name: string;
  matchCount: number;
}

/**
 * Plain-language warning shown when a planning cannot be trusted as-is.
 *
 * Two distinct situations are covered, since neither alone tells the whole
 * story:
 * - `report`: a cheap, optimistic pre-solve capacity estimate — it can say
 *   "réalisable" for a plan the solver still won't manage to bring to zero
 *   hard (it ignores the vacation découpage and legal constraints on minors).
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
    @if (report() && !report()!.feasible) {
      <mat-card appearance="outlined" class="feasibility-banner">
        <mat-card-content>
          <mat-icon>warning</mat-icon>
          <p>{{ report()!.message }}</p>
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class FeasibilityBanner {
  readonly report = input<FeasibilityReport | null>(null);
  /** Hard score of the last analysed solve, `null` when never analysed. */
  readonly hardScore = input<number | null>(null);
  /** Still-violated hard constraints, most useful ones to name in the message. */
  readonly hardIssues = input<HardIssue[]>([]);

  protected readonly hardIssueMessage = computed(() => {
    const hardScore = this.hardScore();
    if (hardScore === null || hardScore >= 0) {
      return null;
    }
    return hardScoreNegativeMessage(hardScore);
  });
}
