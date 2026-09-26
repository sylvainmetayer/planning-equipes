import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { pasteToCsv } from './collage';

/**
 * « Coller depuis un tableur »: the other way into an import card, for the
 * organiser whose rows are open in a spreadsheet rather than saved as a file.
 * Folded by default under the file button; once unfolded, the cells pasted
 * are turned into the CSV the import reads ({@link pasteToCsv}) and handed to
 * the card, which previews them exactly as it previews a file — nothing is
 * written before its « Importer ».
 */
@Component({
  selector: 'app-collage-tableur',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    @if (ouvert()) {
      <div>
        <mat-form-field appearance="outline" class="collage-tableur-champ">
          <mat-label i18n="@@collage.label">Cellules copiées depuis le tableur, en-têtes compris</mat-label>
          <textarea
            matInput
            rows="6"
            name="collage"
            [ngModel]="text()"
            (ngModelChange)="text.set($event)"
            [disabled]="disabled()"
          ></textarea>
        </mat-form-field>
        <div class="card-actions">
          <button matButton="filled" type="button" [disabled]="disabled() || text().trim() === ''" (click)="read()">
            <mat-icon>content_paste_go</mat-icon>
            <ng-container i18n="@@collage.lire">Lire le collage</ng-container>
          </button>
          <button matButton type="button" (click)="fermer()">
            <mat-icon>close</mat-icon>
            <ng-container i18n="@@collage.fermer">Fermer</ng-container>
          </button>
        </div>
      </div>
    } @else {
      <button matButton type="button" [disabled]="disabled()" (click)="ouvert.set(true)">
        <mat-icon>content_paste</mat-icon>
        <ng-container i18n="@@collage.ouvrir">Coller depuis un tableur</ng-container>
      </button>
    }
  `,
  styles: `
    .collage-tableur-champ {
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CollageTableur {
  readonly disabled = input(false);
  /** The pasted cells, as the CSV text the import reads. */
  readonly colle = output<string>();

  protected readonly ouvert = signal(false);
  protected readonly text = signal('');

  protected read(): void {
    const csv = pasteToCsv(this.text());
    if (csv.trim() !== '') {
      this.colle.emit(csv);
    }
  }

  protected fermer(): void {
    this.ouvert.set(false);
    this.text.set('');
  }
}
