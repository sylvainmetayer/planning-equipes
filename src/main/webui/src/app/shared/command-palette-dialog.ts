// Command palette (Ctrl+K): one field that both navigates to any page of the
// application and finds an animateur, a stand or a créneau in the referential.
//
// Presentational on purpose: it never navigates and never writes anything. It
// closes with the entry the user picked, and `KeyboardShortcutsService` — the
// one that opened it — performs the navigation. That keeps what a result *is*
// (`core/keyboard-shortcuts.ts`) and what it *does* out of the rendering.

import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import {
  CommandePalette,
  buildDestinationsNavigation,
  chercherCommandes,
} from '../core/keyboard-shortcuts';
import { ReferenceDataStore } from '../core/reference-data.store';

@Component({
  selector: 'app-command-palette-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <h2 mat-dialog-title i18n="@@palette.title">Palette de commandes</h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" class="palette-champ">
        <mat-label i18n="@@palette.label">Aller à, ou rechercher</mat-label>
        <mat-icon matPrefix>search</mat-icon>
        <input
          matInput
          name="palette"
          role="combobox"
          aria-expanded="true"
          aria-autocomplete="list"
          aria-controls="palette-resultats"
          [attr.aria-activedescendant]="idOptionActive()"
          [ngModel]="query()"
          (ngModelChange)="query.set($event)"
          (keydown)="onKeyDown($event)"
          i18n-placeholder="@@palette.placeholder"
          placeholder="Page, animateur, stand, créneau…"
        />
      </mat-form-field>

      <p class="palette-resume" role="status">{{ resume() }}</p>

      @if (resultats().length > 0) {
        <ul
          id="palette-resultats"
          class="palette-liste"
          role="listbox"
          [attr.aria-label]="listeLabel"
        >
          @for (commande of resultats(); track commande.id; let index = $index) {
            <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events, @angular-eslint/template/interactive-supports-focus -- the combobox pattern: the keyboard stays in the <input>, which moves aria-activedescendant over these options; an option is never focused itself -->
            <li
              class="palette-item"
              role="option"
              [id]="'palette-option-' + index"
              [class.palette-item-active]="index === indexActif()"
              [attr.aria-selected]="index === indexActif()"
              (click)="activer(commande)"
              (mouseenter)="indexActif.set(index)"
            >
              <mat-icon aria-hidden="true">{{ commande.icon }}</mat-icon>
              <span class="palette-item-texte">
                <span class="palette-item-label">{{ commande.label }}</span>
                @if (commande.hint) {
                  <span class="palette-item-hint">{{ commande.hint }}</span>
                }
              </span>
              @if (commande.raccourci) {
                <kbd class="palette-item-raccourci">{{ commande.raccourci }}</kbd>
              }
            </li>
          }
        </ul>
      }
    </mat-dialog-content>
  `,
  styles: `
    .palette-champ {
      width: 100%;
    }

    .palette-resume {
      margin: 0 0 0.5rem;
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85rem;
    }

    .palette-liste {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 22rem;
      overflow-y: auto;
    }

    .palette-item {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.5rem 0.75rem;
      border-radius: var(--mat-sys-corner-small);
      cursor: pointer;
    }

    .palette-item-active {
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }

    .palette-item-texte {
      display: flex;
      flex-direction: column;
      min-width: 0;
      flex: 1;
    }

    .palette-item-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .palette-item-hint {
      font-size: 0.78rem;
      color: var(--mat-sys-on-surface-variant);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .palette-item-raccourci {
      font-family: inherit;
      font-size: 0.75rem;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: var(--mat-sys-corner-extra-small);
      padding: 0.1rem 0.4rem;
      white-space: nowrap;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommandPaletteDialog {
  private readonly dialogRef = inject(MatDialogRef<CommandPaletteDialog, CommandePalette | null>);
  private readonly store = inject(ReferenceDataStore);

  protected readonly query = signal('');
  private readonly destinations = buildDestinationsNavigation();

  protected readonly resultats = computed(() =>
    chercherCommandes(this.query(), {
      destinations: this.destinations,
      animateurs: this.store.animateurs(),
      stands: this.store.stands(),
      creneaux: this.store.creneaux(),
    }),
  );

  /**
   * Highlighted row. `linkedSignal` rather than an `effect` writing a signal:
   * typing a new query resets the highlight to the first result, and the
   * arrows move it from there until the next keystroke.
   */
  protected readonly indexActif = linkedSignal<readonly CommandePalette[], number>({
    source: this.resultats,
    computation: () => 0,
  });

  protected readonly idOptionActive = computed(() =>
    this.resultats().length > 0 ? `palette-option-${this.indexActif()}` : null,
  );

  protected readonly listeLabel = $localize`:@@palette.results.label:Résultats de la palette`;

  protected readonly resume = computed(() => {
    const total = this.resultats().length;
    return total === 0
      ? $localize`:@@palette.results.none:Aucun résultat. Essayez un autre terme.`
      : $localize`:@@palette.results.count:${total}:count: résultat(s) — ↑ ↓ pour parcourir, Entrée pour ouvrir.`;
  });

  constructor() {
    // The palette is opened from anywhere, including pages that never read the
    // referential (the calendars, the solver). Loading it here — and only when
    // it is still empty — is what makes "find an animateur" work on the first
    // Ctrl+K of a session, without adding a fetch to every page.
    if (
      this.store.animateurs().length === 0 &&
      this.store.stands().length === 0 &&
      this.store.creneaux().length === 0
    ) {
      void this.store.reload(['animateurs', 'stands', 'creneaux']).catch(() => undefined);
    }
  }

  protected onKeyDown(event: KeyboardEvent): void {
    const total = this.resultats().length;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.deplacer(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.deplacer(-1);
        break;
      case 'Home':
        event.preventDefault();
        this.indexActif.set(0);
        break;
      case 'End':
        event.preventDefault();
        this.indexActif.set(Math.max(0, total - 1));
        break;
      case 'Enter': {
        event.preventDefault();
        const choisi = this.resultats()[this.indexActif()];
        if (choisi) {
          this.activer(choisi);
        }
        break;
      }
      default:
        break;
    }
  }

  /** Wraps around: the list is short, and reaching the last entry by pressing ↑ once is the point. */
  private deplacer(delta: number): void {
    const total = this.resultats().length;
    if (total === 0) {
      return;
    }
    this.indexActif.update((index) => (index + delta + total) % total);
    this.faireDefilerVersActif();
  }

  /** Keeps the highlighted row inside the scrollable list, as the arrows walk past its edge. */
  private faireDefilerVersActif(): void {
    document
      .getElementById(`palette-option-${this.indexActif()}`)
      ?.scrollIntoView({ block: 'nearest' });
  }

  protected activer(commande: CommandePalette): void {
    this.dialogRef.close(commande);
  }
}
