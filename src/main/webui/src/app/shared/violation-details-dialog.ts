// Detail popup for a single hard constraint's violations: who/what/when, in
// plain language, so a non-technical admin can go fix the underlying data
// (an animateur's disponibilités, a stand's compétences requises...) without
// having to interpret a raw score breakdown.

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

export interface ViolationDetailsData {
  constraintName: string;
  description: string;
  matchCount: number;
  /** Human-readable lines, one per match — capped server-side, see PlanningService.MAX_VIOLATIONS_PAR_CONTRAINTE. */
  violations: string[];
}

@Component({
  selector: 'app-violation-details-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.constraintName }}</h2>
    <mat-dialog-content>
      <p class="violation-details-description">{{ data.description }}</p>
      <ul class="violation-details-list">
        @for (violation of data.violations; track violation) {
          <li>{{ violation }}</li>
        }
      </ul>
      @if (data.violations.length < data.matchCount) {
        <p class="violation-details-truncated" i18n="@@violationDetails.truncated">
          Affichage limité aux {{ data.violations.length }} premières occurrences sur
          {{ data.matchCount }}.
        </p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton (click)="dialogRef.close()" i18n="@@violationDetails.close">Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .violation-details-description {
      color: var(--mat-sys-on-surface-variant);
      margin-top: 0;
    }
    .violation-details-list {
      margin: 0;
      padding-left: 1.25rem;
      max-height: 50vh;
      overflow-y: auto;
    }
    .violation-details-list li {
      margin-bottom: 0.25rem;
    }
    .violation-details-truncated {
      color: var(--mat-sys-on-surface-variant);
      font-style: italic;
      margin-bottom: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ViolationDetailsDialog {
  protected readonly dialogRef = inject<MatDialogRef<ViolationDetailsDialog>>(MatDialogRef);
  protected readonly data = inject<ViolationDetailsData>(MAT_DIALOG_DATA);
}
