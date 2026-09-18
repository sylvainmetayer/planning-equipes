import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { normaliseHour } from '../../core/horaire-stand';
import {
  ErreurForm,
  FenetreSaisie,
  RepasSaisie,
  alignSoirOnCompensation,
  erreursRepas,
  repasFormEmpty,
} from './consignes';

/**
 * « Fenêtres repas ce jour-là » (issue #4): the meal windows a consigne — or
 * the preset it is made from — restates for its own dates, folded under a
 * `<details>` since most consignes leave the edition's alone. The same six
 * fields serve the consigne form and the preset dialog; the state stays with
 * the dialog, which receives every keystroke through `repasChange`.
 *
 * <p>The section opens by itself as soon as something is restated in it — a
 * row being modified, a preset chosen — so a value in force is never hidden
 * behind a closed fold.</p>
 */
@Component({
  selector: 'app-consigne-repas-fields',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
  ],
  templateUrl: './consigne-repas-fields.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsigneRepasFields {
  readonly repas = input.required<RepasSaisie>();
  /** The day's default compensation windows, what « Aligner le soir » reads. */
  readonly fenetres = input<readonly FenetreSaisie[]>([]);
  /** False when the host dialog words the errors itself, at its foot. */
  readonly inlineErrors = input(true);
  readonly repasChange = output<RepasSaisie>();

  protected readonly open = signal(false);
  protected readonly empty = computed(() => repasFormEmpty(this.repas()));
  protected readonly canAlign = computed(() =>
    this.fenetres().some((fenetre) => normaliseHour(fenetre.debut) !== null),
  );
  protected readonly messageErreur = computed(() => {
    const first = erreursRepas(this.repas())[0];
    return first ? repasErrorLabel(first) : '';
  });

  constructor() {
    effect(() => {
      if (!this.empty()) {
        this.open.set(true);
      }
    });
  }

  protected patch(patch: Partial<RepasSaisie>): void {
    this.repasChange.emit({ ...this.repas(), ...patch });
  }

  /** `type="number"` hands a number or `null` over; the field keeps text. */
  protected onCoupure(valeur: unknown): void {
    this.patch({ coupureMinutes: String(valeur ?? '') });
  }

  protected align(): void {
    this.repasChange.emit(alignSoirOnCompensation(this.repas(), this.fenetres()));
  }
}

/** The sentence a meal-section code reads as; empty for a code of another section. */
export function repasErrorLabel(erreur: ErreurForm): string {
  switch (erreur) {
    case 'REPAS_FENETRE':
      return $localize`:@@consignes.form.error.repasFenetre:Une fenêtre repas a besoin de son début et de sa fin, ou d'aucun des deux.`;
    case 'REPAS_COUPURE':
      return $localize`:@@consignes.form.error.repasCoupure:La coupure repas est un nombre entier de minutes supérieur à zéro.`;
    case 'REPAS_JUSTIFICATION':
      return $localize`:@@consignes.form.error.repasJustification:Dites pourquoi les fenêtres repas changent ce jour-là : la justification est obligatoire dès qu'un champ est rempli.`;
    default:
      return '';
  }
}
