// Read-only detail view of one reference-data row, opened from the
// "consultation" button of a list.
//
// Deliberately generic: the dialog knows how to *lay out* labelled values and
// chips, never what a stand or an animateur is made of. Each page builds its
// own sections in a plain `<entity>-detail.ts` next to it (same split as the
// bulk-edit rules), so what a detail view shows is unit-tested without
// rendering anything.

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SolverJobService } from '../core/solver-job.service';

/** One labelled value. `chips` renders a list of short values instead of `value`. */
export interface DetailRow {
  label: string;
  value?: string;
  chips?: string[];
  /** Rendered muted, for a value that is a hint rather than data (e.g. "aucun"). */
  muted?: boolean;
}

export interface DetailSection {
  title: string;
  rows: DetailRow[];
}

export interface DetailData {
  title: string;
  subtitle?: string;
  sections: DetailSection[];
}

/** `'edit'` when the user asked to edit: the caller opens its own form dialog. */
export type DetailResult = 'edit' | null;

@Component({
  selector: 'app-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, MatChipsModule, MatIconModule, MatTooltipModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      @if (data.subtitle) {
        <p class="detail-subtitle">{{ data.subtitle }}</p>
      }
      @for (section of data.sections; track section.title) {
        <section class="detail-section">
          <h3 class="detail-section-title">{{ section.title }}</h3>
          <dl class="detail-rows">
            @for (row of section.rows; track row.label) {
              <dt>{{ row.label }}</dt>
              <dd [class.detail-muted]="row.muted">
                @if (row.chips) {
                  <mat-chip-set>
                    @for (chip of row.chips; track chip) {
                      <mat-chip>{{ chip }}</mat-chip>
                    }
                  </mat-chip-set>
                } @else {
                  {{ row.value }}
                }
              </dd>
            }
          </dl>
        </section>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton mat-dialog-close type="button">{{ closeLabel }}</button>
      <button
        matButton="filled"
        type="button"
        [disabled]="editingLocked()"
        [matTooltip]="editingLocked() ? lockedTooltip : ''"
        (click)="dialogRef.close('edit')"
      >
        <mat-icon>edit</mat-icon>
        {{ editLabel }}
      </button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailDialog {
  protected readonly data = inject<DetailData>(MAT_DIALOG_DATA);
  protected readonly dialogRef = inject<MatDialogRef<DetailDialog, DetailResult>>(MatDialogRef);

  private readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly closeLabel = $localize`:@@violationDetails.close:Fermer`;
  protected readonly editLabel = $localize`:@@common.edit:Modifier`;
  protected readonly lockedTooltip = $localize`:@@detail.lockedBySolver:Modification impossible pendant une résolution du solveur.`;
}
