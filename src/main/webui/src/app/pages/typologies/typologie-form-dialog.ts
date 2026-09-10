import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { TypologieItem } from '../../core/models';

export interface TypologieFormData {
  typologie: TypologieItem | null;
}

/** Add/edit dialog for a typologie: id plus display label. */
@Component({
  selector: 'app-typologie-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './typologie-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypologieFormDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<TypologieFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<TypologieFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<string | null>(this.data.typologie?.id ?? null);
  protected readonly draft = signal<TypologieItem>(toDraft(this.data.typologie));
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id
      ? $localize`:@@typologies.form.editTitle:Modifier la typologie ${id}:id:`
      : $localize`:@@typologies.form.newTitle:Nouvelle typologie`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@typologies.submit.edit:Modifier la typologie`
      : $localize`:@@typologies.submit.create:Créer la typologie`,
  );

  protected patch(patch: Partial<TypologieItem>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    // ninja is carried over untouched: it is set from the list page's dedicated
    // select (only one typologie may hold it), and a PUT replaces the whole row.
    const typologie: TypologieItem = {
      id: draft.id.trim(),
      label: draft.label.trim(),
      ninja: draft.ninja ?? false,
      modifieLe: draft.modifieLe ?? null,
    };
    if (
      await this.crud.save(
        'typologies',
        typologie,
        this.editingId(),
        $localize`:@@typologies.entityLabel:Typologie`,
      )
    ) {
      this.dialogRef.close(true);
    }
  }
}

function toDraft(typologie: TypologieItem | null): TypologieItem {
  return typologie
    ? {
        id: typologie.id,
        label: typologie.label ?? '',
        ninja: typologie.ninja ?? false,
        modifieLe: typologie.modifieLe ?? null,
      }
    : { id: '', label: '', ninja: false, modifieLe: null };
}
