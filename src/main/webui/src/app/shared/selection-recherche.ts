import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
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
    MatInputModule,
  ],
  template: `
    <mat-form-field
      appearance="outline"
      class="selection-recherche"
      [subscriptSizing]="compact() ? 'dynamic' : 'fixed'"
    >
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
      @if (!compact()) {
        <mat-hint>{{ resume() }}</mat-hint>
      }
    </mat-form-field>
  `,
  styles: `
    .selection-recherche {
      display: block;
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectionRecherche {
  readonly options = input<OptionSelection[]>([]);
  readonly label = input('');
  readonly placeholder = input('');
  readonly multiple = input(false);
  readonly disabled = input(false);
  /**
   * A field of a filter bar: no hint under it, no room kept for one — the
   * Planning page lines four of them up above the plan it has to leave room for.
   */
  readonly compact = input(false);

  /** Selected ids — one entry at most when `multiple` is false. */
  readonly valeurs = model<string[]>([]);

  protected readonly saisie = signal('');
  /** The single pick the field last showed the label of — to clear it when the pick is withdrawn from outside. */
  private dernierChoisi: string | null = null;

  protected readonly optionsChoisies = computed(() => {
    const choisies = new Set(this.valeurs());
    return this.options().filter((option) => choisies.has(option.id));
  });

  protected readonly optionsFiltrees = computed(() => {
    const query = normaliserPourFiltre(this.saisie().trim());
    const dejaChoisies = new Set(this.multiple() ? this.valeurs() : []);
    return this.options()
      .filter((option) => !dejaChoisies.has(option.id))
      .filter((option) => !query || normaliserPourFiltre(option.label).includes(query))
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

  constructor() {
    // The other half of "the field never shows one thing while the component
    // answers another": a selection can also arrive from outside — the timeline
    // reads its animateur from the URL — and `choisir` never ran, so the field
    // stayed on its placeholder while a pick was live. Writing the label back
    // is what makes the input honest, and what keeps the first keystroke from
    // dropping a selection the user could not see.
    effect(() => {
      if (this.multiple()) {
        return;
      }
      const choisi = this.valeurs()[0];
      if (!choisi) {
        // Withdrawn from outside — a page's « Réinitialiser la vue »: the field
        // still shows the name, so it goes too. Typed over, the field no longer
        // shows that name and keeps what is being typed.
        const precedent = this.dernierChoisi;
        this.dernierChoisi = null;
        const label = this.options().find((option) => option.id === precedent)?.label;
        if (label && untracked(this.saisie) === label) {
          this.saisie.set('');
        }
        return;
      }
      this.dernierChoisi = choisi;
      // No label yet means the options have not loaded: leave the field alone
      // rather than blank it, this effect runs again when they arrive.
      const label = this.options().find((option) => option.id === choisi)?.label;
      if (label && this.saisie() !== label) {
        this.saisie.set(label);
      }
    });
  }

  protected retirerLabel(label: string): string {
    return $localize`:@@selectionRecherche.remove:Retirer ${label}:label:`;
  }

  protected onSaisieSimple(valeur: string): void {
    this.saisie.set(valeur);
    // The picker never keeps a value the input no longer shows. Only an empty
    // field used to clear it, so typing over a pick left the old id selected:
    // the field read one name while the component answered another, and a
    // caller exported the planning of someone the user had stopped looking at.
    const choisi = this.valeurs()[0];
    if (!choisi) {
      return;
    }
    const label = this.options().find((option) => option.id === choisi)?.label ?? '';
    if (valeur.trim() !== label.trim()) {
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
