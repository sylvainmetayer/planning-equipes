import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  input,
} from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * The « Férié » mark of a date on the entry screens: a word, not a colour,
 * with the holiday's name and what it changes in a tooltip that the keyboard
 * reaches too. Informative only — a stand open on 14 July is legitimate; the
 * mark is there so that it is a choice.
 *
 * `compact` shows « Férié » where a column is a few characters wide; otherwise
 * the holiday's own name is shown.
 */
@Component({
  selector: 'app-pastille-ferie',
  imports: [MatTooltipModule],
  template: `<span class="pastille-ferie" tabindex="0" [matTooltip]="infobulle()"
      >{{ text() }}<span class="visually-hidden"> — {{ infobulle() }}</span></span
    >`,
  styles: `
    .pastille-ferie {
      display: inline-block;
      padding: 0.05rem 0.4rem;
      border-radius: 999px;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      font-size: 0.7rem;
      font-weight: 500;
      line-height: 1.3;
      white-space: nowrap;
      cursor: help;
    }
    .pastille-ferie:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
      outline-offset: 1px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PastilleFerie {
  /** The holiday's name, « Assomption ». */
  readonly libelle = input.required<string>();
  readonly compact = input(false, { transform: booleanAttribute });

  protected readonly text = computed(() =>
    this.compact() ? $localize`:@@ferie.pastille:Férié` : this.libelle(),
  );

  protected readonly infobulle = computed(
    () =>
      $localize`:@@ferie.infobulle:${this.libelle()}:libelle: — jour férié : les mineurs ne peuvent pas y travailler (L3164-6), heures comptées fériées à la paie`,
  );
}
