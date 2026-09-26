import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ReferentialImportCard } from '../pages/imports/imports';
import { ImportDialog, ImportDialogData } from './import-dialog';

/**
 * « Importer », in the header of a referential screen: opens the import card
 * of that referential — the one of Fichiers › Importer — in a dialog, so the
 * list is filled from a file or a paste without leaving it. The reference
 * store is reloaded by the card itself on a write; `imported` tells the host,
 * for whatever else it reads.
 *
 *   <app-import-button card="stands" />
 */
@Component({
  selector: 'app-import-button',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <button matButton type="button" [disabled]="disabled()" (click)="open()">
      <mat-icon>upload_file</mat-icon>
      <ng-container i18n="@@importButton.label">Importer</ng-container>
    </button>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportButton {
  readonly card = input.required<ReferentialImportCard>();
  readonly disabled = input(false);
  /** A write went through in the dialog, now closed. */
  readonly imported = output<void>();

  private readonly dialog = inject(MatDialog);

  protected open(): void {
    const ref = this.dialog.open<ImportDialog, ImportDialogData, boolean>(ImportDialog, {
      data: { card: this.card() },
      width: '64rem',
      maxWidth: '95vw',
      autoFocus: 'dialog',
    });
    // Held now: Material lets go of the instance once the dialog is closed.
    const contenu = ref.componentInstance;
    ref.afterClosed().subscribe(() => {
      if (contenu.imported()) {
        this.imported.emit();
      }
    });
  }
}
