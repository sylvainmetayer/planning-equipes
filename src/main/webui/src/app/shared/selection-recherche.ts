import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { normaliserPourFiltre } from '../core/text-filter';

/** One selectable entry: a stable id and what the user reads. */
export interface OptionSelection {
  id: string;
  label: string;
}

/**
 * Type-to-filter picker for the long referential lists.
 *
 * A `mat-select` over 153 animateurs (or 65 stands) means scrolling an
 * unsearchable list, and at the keyboard it means arrowing through every entry
 * — the single most expensive interaction in the application. Here the user
 * types two letters instead.
 *
 * In `multiple` mode the picks are shown as removable chips, so what is
 * selected stays visible instead of collapsing into "3 selected".
 */
@Component({
  selector: 'app-selection-recherche',
  imports: [
    FormsModule,
    MatAutocompleteModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule
  ],
  template: `
    <mat-form-field appearance="outline" class="selection-recherche">
      <mat-label>{{ label() }}</mat-label>
      @if (multiple()) {
        <mat-chip-grid #grille [attr.aria-label]="label()">
          @for (choisi of optionsChoisies(); track choisi.id) {
            <mat-chip-row (removed)="retirer(choisi.id)">
              {{ choisi.label }}
              <button matChipRemove [attr.aria-label]="retirerLabel(choisi.label)">
                <mat-icon>cancel</mat-icon>
              </button>
            </mat-chip-row>
          }
          <input
            [placeholder]="placeholder()"
            [matChipInputFor]="grille"
            [matAutocomplete]="auto"
            [ngModel]="saisie()"
            (ngModelChange)="saisie.set($event)"
            [disabled]="disabled()"
          />
        </mat-chip-grid>
      } @else {
        <input
          matInput
          [matAutocomplete]="auto"
          [ngModel]="saisie()"
          (ngModelChange)="onSaisieSimple($event)"
          [disabled]="disabled()"
          [placeholder]="placeholder()"
        />
      }
      <mat-autocomplete #auto="matAutocomplete" (optionSelected)="choisir($event.option.value)">
        @for (option of optionsFiltrees(); track option.id) {
          <mat-option [value]="option.id">{{ option.label }}</mat-option>
        }
      </mat-autocomplete>
      <mat-hint>{{ resume() }}</mat-hint>
    </mat-form-field>
  `,
  styles: `
    .selection-recherche {
      display: block;
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SelectionRecherche {
  readonly options = input<OptionSelection[]>([]);
  readonly label = input('');
  readonly placeholder = input('');
  readonly multiple = input(false);
  readonly disabled = input(false);

  /** Selected ids — one entry at most when `multiple` is false. */
  readonly valeurs = model<string[]>([]);

  protected readonly saisie = signal('');

  protected readonly optionsChoisies = computed(() => {
    const choisies = new Set(this.valeurs());
    return this.options().filter((option) => choisies.has(option.id));
  });

  protected readonly optionsFiltrees = computed(() => {
    const requete = normaliserPourFiltre(this.saisie().trim());
    const dejaChoisies = new Set(this.multiple() ? this.valeurs() : []);
    return this.options()
      .filter((option) => !dejaChoisies.has(option.id))
      .filter((option) => !requete || normaliserPourFiltre(option.label).includes(requete))
      .slice(0, 50);
  });

  /** Says how many entries the list holds, and how many the filter kept. */
  protected readonly resume = computed(() => {
    const total = this.options().length;
    const filtrees = this.optionsFiltrees().length;
    if (this.multiple()) {
      return $localize`:@@selectionRecherche.hintMultiple:${this.valeurs().length}:selection: sélectionné(s) sur ${total}:total:`;
    }
    return this.saisie().trim()
      ? $localize`:@@selectionRecherche.hintFiltre:${filtrees}:count: proposition(s) sur ${total}:total:`
      : $localize`:@@selectionRecherche.hintTotal:${total}:total: entrée(s) — tapez pour filtrer`;
  });

  protected retirerLabel(label: string): string {
    return $localize`:@@selectionRecherche.remove:Retirer ${label}:label:`;
  }

  protected onSaisieSimple(valeur: string): void {
    this.saisie.set(valeur);
    // Clearing the field clears the selection: the picker never keeps a value
    // the input no longer shows.
    if (!valeur.trim()) {
      this.valeurs.set([]);
    }
  }

  protected choisir(id: string): void {
    if (this.multiple()) {
      this.valeurs.set([...new Set([...this.valeurs(), id])]);
      this.saisie.set('');
      return;
    }
    this.valeurs.set([id]);
    this.saisie.set(this.options().find((option) => option.id === id)?.label ?? '');
  }

  protected retirer(id: string): void {
    this.valeurs.set(this.valeurs().filter((valeur) => valeur !== id));
  }
}
