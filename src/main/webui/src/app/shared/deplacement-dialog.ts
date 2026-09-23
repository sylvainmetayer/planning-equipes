// The keyboard — and single-pointer — twin of the drag-and-drop of the day
// views (RGAA 7.3, WCAG 2.1.1 and 2.5.7).
//
// `@angular/cdk/drag-drop` listens to pointer events only: moving somebody
// from one seat to another had no way in from a keyboard, nor for a person who
// can click but cannot hold a button down while moving. This dialog asks the
// same question the drop answers — « where to? » — and hands the answer back
// to the view, which calls the very method its drop calls. Nothing about the
// move itself lives here.

import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { OptionSelection, SelectionRecherche } from './selection-recherche';

export interface DeplacementDialogData {
  /** « Déplacer Alice », « Déplacer une vacation de Bruno ». */
  title: string;
  /**
   * What is moved, when there is a choice to make (a person holding several
   * shifts on the rail). One entry, or none, and the field is not shown.
   */
  sources?: OptionSelection[];
  /** Where it can go — free seats, people to swap with — as the drop would accept. */
  targets: OptionSelection[];
  /** The label of the destination field. */
  targetLabel: string;
}

export interface ChosenMove {
  source: string | null;
  target: string;
}

@Component({
  selector: 'app-deplacement-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatSelectModule,
    SelectionRecherche,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <form (submit)="$event.preventDefault(); submit()">
      <mat-dialog-content>
        @if (severalSources()) {
          <mat-form-field appearance="outline" class="deplacement-champ">
            <mat-label i18n="@@deplacement.dialog.source">Vacation à déplacer</mat-label>
            <mat-select [ngModel]="source()" (ngModelChange)="source.set($event)" name="source">
              @for (option of data.sources; track option.id) {
                <mat-option [value]="option.id">{{ option.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }
        @if (data.targets.length === 0) {
          <p i18n="@@deplacement.dialog.aucuneCible">
            Aucune destination possible ce jour-là : ni siège libre, ni personne avec qui échanger.
          </p>
        } @else {
          <app-selection-recherche
            [options]="data.targets"
            [label]="data.targetLabel"
            [valeurs]="target() ? [target()!] : []"
            (valeursChange)="target.set($event.length > 0 ? $event[0] : null)"
          />
        }
        <p class="deplacement-aide" i18n="@@deplacement.dialog.aide">
          Refusé si une règle dure serait cassée ; la règle en cause est alors nommée.
        </p>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button matButton type="button" mat-dialog-close i18n="@@common.cancel">Annuler</button>
        <button matButton="filled" type="submit" [disabled]="!ready()" i18n="@@deplacement.dialog.valider">
          Déplacer
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .deplacement-champ {
      display: block;
      width: 100%;
    }
    .deplacement-aide {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeplacementDialog {
  protected readonly data = inject<DeplacementDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<DeplacementDialog, ChosenMove>>(MatDialogRef);

  protected readonly severalSources = computed(() => (this.data.sources?.length ?? 0) > 1);
  protected readonly source = signal<string | null>(this.data.sources?.[0]?.id ?? null);
  protected readonly target = signal<string | null>(null);
  protected readonly ready = computed(
    () => this.target() !== null && (!this.data.sources?.length || this.source() !== null),
  );

  protected submit(): void {
    const target = this.target();
    if (!this.ready() || target === null) {
      return;
    }
    this.ref.close({ source: this.source(), target });
  }
}

/** Opens the dialog; the result is `undefined` when it was dismissed. */
export function openMoveDialog(dialog: MatDialog, data: DeplacementDialogData) {
  return dialog.open<DeplacementDialog, DeplacementDialogData, ChosenMove>(DeplacementDialog, {
    data,
    width: '32rem',
    maxWidth: '95vw',
    autoFocus: 'first-tabbable',
    restoreFocus: true,
  });
}
