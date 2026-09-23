// The list of keyboard shortcuts, opened by `?`.
//
// Reads the same table as the shortcuts themselves (`core/keyboard-shortcuts.ts`):
// a letter reassigned there moves in this dialog on its own, so the overlay
// cannot describe a keyboard that no longer exists. The help page repeats the
// table in prose, for the reader who looks for it by typing « raccourci ».

import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { RaccourciNavigation, buildRaccourcisNavigation } from '../core/keyboard-shortcuts';
import { SingleKeyShortcutsToggle } from './single-key-shortcuts-toggle';

/** One general shortcut: the keys, and what pressing them does. */
interface RaccourciGeneral {
  touches: string;
  description: string;
}

@Component({
  selector: 'app-keyboard-shortcuts-dialog',
  imports: [MatButtonModule, MatDialogModule, RouterLink, SingleKeyShortcutsToggle],
  template: `
    <h2 mat-dialog-title i18n="@@shortcuts.title">Raccourcis clavier</h2>
    <mat-dialog-content>
      <p class="raccourcis-intro" i18n="@@shortcuts.intro">
        Les raccourcis à une touche sont inactifs pendant la saisie d'un texte.
      </p>
      <!-- Where the shortcuts are learnt is where they can be switched off:
           dictation or a tremor turns them into unwanted navigations. -->
      <app-single-key-shortcuts-toggle class="raccourcis-interrupteur" />

      <h3 class="raccourcis-titre" i18n="@@shortcuts.general.title">Général</h3>
      <dl class="raccourcis-liste">
        @for (raccourci of general; track raccourci.touches) {
          <dt>
            <kbd>{{ raccourci.touches }}</kbd>
          </dt>
          <dd>{{ raccourci.description }}</dd>
        }
      </dl>

      <h3 class="raccourcis-titre" i18n="@@shortcuts.table.title">Dans un tableau de données de référence</h3>
      <p class="raccourcis-intro" i18n="@@shortcuts.table.intro">
        Pour entrer dans le tableau : « / » place le curseur dans le filtre, puis Flèche bas saute
        sur la ligne courante. Tab y entre aussi et en ressort vers les boutons de la ligne.
      </p>
      <dl class="raccourcis-liste">
        @for (raccourci of tableau; track raccourci.touches) {
          <dt>
            <kbd>{{ raccourci.touches }}</kbd>
          </dt>
          <dd>{{ raccourci.description }}</dd>
        }
      </dl>

      <h3 class="raccourcis-titre" i18n="@@shortcuts.grille.title">Dans une grille de saisie</h3>
      <p class="raccourcis-intro" i18n="@@shortcuts.grille.intro">
        Les grilles « Ouvertures des stands » et « Compétences ». Rien n'est enregistré avant
        « Enregistrer » : une reprise de trop s'annule avec les modifications.
      </p>
      <dl class="raccourcis-liste">
        @for (raccourci of grille; track raccourci.touches) {
          <dt>
            <kbd>{{ raccourci.touches }}</kbd>
          </dt>
          <dd>{{ raccourci.description }}</dd>
        }
      </dl>

      <h3 class="raccourcis-titre" i18n="@@shortcuts.navigation.title">
        Navigation : « g » puis une lettre
      </h3>
      <dl class="raccourcis-liste">
        @for (raccourci of navigation; track raccourci.touche) {
          <dt>
            <kbd>g</kbd> <kbd>{{ raccourci.touche }}</kbd>
          </dt>
          <dd>{{ raccourci.label }}</dd>
        }
      </dl>

      <p class="raccourcis-note" i18n="@@shortcuts.note">
        Les pages sans lettre restent accessibles par la palette, qui liste toutes les pages de
        l'application.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <a matButton routerLink="/aide" mat-dialog-close i18n="@@shortcuts.help">Voir l'aide</a>
      <button matButton="filled" type="button" mat-dialog-close i18n="@@common.close">
        Fermer
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .raccourcis-intro,
    .raccourcis-note {
      margin: 0 0 1rem;
      color: var(--mat-sys-on-surface-variant);
    }

    .raccourcis-interrupteur {
      display: block;
      margin: -0.5rem 0 0.5rem;
    }

    .raccourcis-titre {
      margin: 1rem 0 0.5rem;
      font-size: 1rem;
    }

    .raccourcis-liste {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 0.4rem 1rem;
      margin: 0;
    }

    .raccourcis-liste dd {
      margin: 0;
    }

    kbd {
      font-family: inherit;
      font-size: 0.8rem;
      border: 1px solid var(--mat-sys-outline-variant);
      border-bottom-width: 2px;
      border-radius: var(--mat-sys-corner-extra-small);
      padding: 0.1rem 0.4rem;
      white-space: nowrap;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KeyboardShortcutsDialog {
  protected readonly navigation: RaccourciNavigation[] = buildRaccourcisNavigation();

  /**
   * Local to a table, and only while the focus is inside one — which is why
   * they are not in `keyboard-shortcuts.service.ts` with the global ones. Same
   * roving tabindex as the heatmap and the month calendar.
   */
  protected readonly tableau: RaccourciGeneral[] = [
    {
      touches: $localize`:@@shortcuts.key.arrowDown:Flèche bas`,
      description: $localize`:@@shortcuts.table.enter:Depuis le filtre de la page, entrer dans le tableau : le focus saute sur la ligne courante.`,
    },
    {
      touches: '↑ ↓',
      description: $localize`:@@shortcuts.table.move:Passer d'une ligne à l'autre du tableau.`,
    },
    {
      touches: $localize`:@@shortcuts.key.homeEnd:Début / Fin`,
      description: $localize`:@@shortcuts.table.bounds:Aller à la première ou à la dernière ligne affichée.`,
    },
    {
      touches: $localize`:@@shortcuts.key.enter:Entrée`,
      description: $localize`:@@shortcuts.table.open:Ouvrir la ligne : sa fiche de consultation, ou son formulaire là où il n'y a pas de fiche.`,
    },
    {
      touches: $localize`:@@shortcuts.key.space:Espace`,
      description: $localize`:@@shortcuts.table.select:Cocher ou décocher la ligne, pour une action groupée.`,
    },
  ];

  /**
   * Local to an entry grid, and only while the focus is in a cell — same
   * reasoning as the table ones above. The moves themselves are in
   * `core/grille-saisie.ts`, shared by the two grids.
   */
  protected readonly grille: RaccourciGeneral[] = [
    {
      touches: '↑ ↓ ← →',
      description: $localize`:@@shortcuts.grille.move:Passer d'une case à l'autre ; Entrée descend, comme dans un tableur.`,
    },
    {
      touches: 'Ctrl + D',
      description: $localize`:@@shortcuts.grille.dupliquer:Reprendre dans cette ligne ce que dit la ligne du dessus.`,
    },
    {
      touches: 'Ctrl + ' + $localize`:@@shortcuts.key.shift:Maj` + ' + ↓',
      description: $localize`:@@shortcuts.grille.colonne:Appliquer la case courante à toutes les lignes affichées de sa colonne.`,
    },
  ];

  protected readonly general: RaccourciGeneral[] = [
    {
      touches: 'Ctrl + K',
      description: $localize`:@@shortcuts.general.palette:Ouvrir la palette de commandes : aller à une page, ou chercher un animateur, un stand, un créneau.`,
    },
    { touches: '?', description: $localize`:@@shortcuts.general.help:Afficher cette liste.` },
    {
      touches: '/',
      description: $localize`:@@shortcuts.general.filter:Placer le curseur dans le filtre de la page, quand elle en a un.`,
    },
    {
      touches: 'Ctrl + ' + $localize`:@@shortcuts.key.enter:Entrée`,
      description: $localize`:@@shortcuts.general.submit:Valider le formulaire en cours de saisie.`,
    },
    {
      touches: $localize`:@@shortcuts.key.escape:Échap`,
      description: $localize`:@@shortcuts.general.close:Fermer la fenêtre ouverte.`,
    },
  ];
}
