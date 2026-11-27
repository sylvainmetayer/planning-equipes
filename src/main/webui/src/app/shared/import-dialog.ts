import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ImportAnimateursPage } from '../pages/import-animateurs/import-animateurs-page';
import { ReferentialImportCard } from '../pages/imports/imports';

/** The card of Fichiers › Importer to open. */
export interface ImportDialogData {
  card: ReferentialImportCard;
}

/**
 * The import card of one referential, in a dialog over its own screen: the
 * same card Fichiers › Importer shows, so the animateurs are filled from a
 * file or a paste without leaving their list. {@link ImportDialog.imported} says,
 * once it closes, whether a write went through, so the host knows to reload
 * what it shows.
 */
@Component({
  selector: 'app-import-dialog',
  imports: [ImportAnimateursPage, MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ titre() }}</h2>
    <mat-dialog-content class="import-dialog-content">
      <app-import-animateurs-page [entete]="false" [lienReferentiel]="false" (imported)="markImported()" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" (click)="fermer()" i18n="@@common.close">Fermer</button>
    </mat-dialog-actions>
  `,
  styleUrl: '../../styles/import-animateurs.css',
  // Global by design (AGENTS.md): the import partial the card relies on, loaded with the dialog's chunk.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportDialog {
  protected readonly data = inject<ImportDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ImportDialog, boolean>>(MatDialogRef);

  private readonly _imported = signal(false);
  /**
   * Whether a write went through while it was open — read by the host once it
   * closes, however it closes: the button, Escape and the backdrop alike.
   */
  readonly imported = this._imported.asReadonly();

  protected readonly titre = computed(() => importTitle(this.data.card));

  protected markImported(): void {
    this._imported.set(true);
  }

  protected fermer(): void {
    this.ref.close(this.imported());
  }
}

/** « Importer des animateurs », in the words of the screen the button sits on. */
export function importTitle(card: ReferentialImportCard): string {
  switch (card) {
    case 'animateurs':
      return $localize`:@@nav.tab.importAnimateurs:Importer des animateurs`;
  }
}
