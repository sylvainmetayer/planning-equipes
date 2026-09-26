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
import { ReferentielImportTarget } from '../core/models';
import { ImportAnimateursPage } from '../pages/import-animateurs/import-animateurs-page';
import { ImportReferentielCard } from '../pages/imports/import-referentiel-card';
import { referentialImportTexts } from '../pages/imports/import-texts';
import { ReferentialImportCard } from '../pages/imports/imports';

/** The card of Fichiers › Importer to open. */
export interface ImportDialogData {
  card: ReferentialImportCard;
}

const TARGETS: Record<Exclude<ReferentialImportCard, 'animateurs'>, ReferentielImportTarget> = {
  typologies: 'TYPOLOGIES',
  emplacements: 'EMPLACEMENTS',
  stands: 'STANDS',
  creneaux: 'CRENEAUX',
  'journees-types': 'JOURNEES_TYPES',
};

/**
 * The import card of one referential, in a dialog over its own screen: the
 * same card Fichiers › Importer shows, so a stand list is filled from a file
 * or a paste without leaving the stands. {@link ImportDialog.imported} says,
 * once it closes, whether a write went through, so the host knows to reload
 * what it shows.
 */
@Component({
  selector: 'app-import-dialog',
  imports: [ImportAnimateursPage, ImportReferentielCard, MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ titre() }}</h2>
    <mat-dialog-content class="import-dialog-content">
      @if (data.card === 'animateurs') {
        <app-import-animateurs-page [entete]="false" [lienReferentiel]="false" (imported)="markImported()" />
      } @else {
        @if (card(); as card) {
          <app-import-referentiel
            [target]="card.target"
            [colonnes]="card.colonnes"
            [aide]="card.aide"
            [lienReferentiel]="false"
            (imported)="markImported()"
          />
        }
      }
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

  protected readonly card = computed(() => {
    const card = this.data.card;
    if (card === 'animateurs') {
      return null;
    }
    const target = TARGETS[card];
    return { target, ...referentialImportTexts(target) };
  });

  protected readonly titre = computed(() => importTitle(this.data.card));

  protected markImported(): void {
    this._imported.set(true);
  }

  protected fermer(): void {
    this.ref.close(this.imported());
  }
}

/** « Importer des stands », in the words of the screen the button sits on. */
export function importTitle(card: ReferentialImportCard): string {
  switch (card) {
    case 'typologies':
      return $localize`:@@importButton.titre.typologies:Importer des typologies`;
    case 'emplacements':
      return $localize`:@@importButton.titre.emplacements:Importer des emplacements`;
    case 'stands':
      return $localize`:@@nav.tab.importStands:Importer des stands`;
    case 'creneaux':
      return $localize`:@@nav.tab.importCreneaux:Importer des créneaux`;
    case 'journees-types':
      return $localize`:@@importButton.titre.journeesTypes:Importer des journées types`;
    case 'animateurs':
      return $localize`:@@nav.tab.importAnimateurs:Importer des animateurs`;
  }
}
