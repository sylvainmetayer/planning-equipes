import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink } from '@angular/router';
import { EditionStore } from '../core/edition.store';
import { Edition } from '../core/models';

/**
 * Persistent strip under the toolbar naming the edition every screen is
 * currently reading from, with a one-click switcher. Every page's data
 * depends on that answer, so it is given once in the shell instead of on any
 * one page — see `docs/decisions/0001-cloisonnement-par-edition.md`.
 *
 * Switching reloads the page rather than refreshing the stores: it swaps the
 * data behind *every* open screen at once, and a full reload is the only way
 * to guarantee no page keeps rendering the previous edition's rows (same
 * reasoning as the language toggle). It is a rare, deliberate action.
 */
@Component({
  selector: 'app-edition-actuelle-bar',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
  template: `
    @if (editionActuelle(); as edition) {
      <!-- Not a live region as a whole: it would announce the edition and the
           edition on every first render, when nothing changed. Only
           the switching action speaks, and it says so itself. -->
      <div class="edition-actuelle-bar">
        <mat-icon class="edition-actuelle-bar-icon">layers</mat-icon>
        <span class="edition-actuelle-bar-label">
          <span i18n="@@editionActuelle.label">Édition actuelle :</span>
          <strong>{{ edition.nom }}</strong>
        </span>
        <span class="edition-actuelle-bar-actions">
          @if (autresEditions().length > 0) {
            <button
              matButton
              [matMenuTriggerFor]="menu"
              i18n-aria-label="@@editionActuelle.switchAriaLabel"
              aria-label="Changer d'édition"
            >
              <span i18n="@@editionActuelle.switch">Changer</span>
              <mat-icon iconPositionEnd>expand_more</mat-icon>
            </button>
            <mat-menu #menu="matMenu">
              @for (autre of autresEditions(); track autre.id) {
                <button mat-menu-item (click)="basculer(autre)">{{ autre.nom }}</button>
              }
            </mat-menu>
          }
          <a
            matButton
            routerLink="/editions"
            i18n-aria-label="@@editionActuelle.manageAriaLabel"
            aria-label="Gérer les éditions"
          >
            <span i18n="@@editionActuelle.manage">Gérer</span>
          </a>
        </span>
      </div>
    }
  `,
  styles: `
    .edition-actuelle-bar {
      position: sticky;
      top: 0;
      z-index: 2;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.25rem 1.25rem;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      border-bottom: 1px solid var(--mat-sys-outline-variant);
      font: var(--mat-sys-body-medium);
    }
    .edition-actuelle-bar-icon {
      flex-shrink: 0;
    }
    .edition-actuelle-bar-label {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    .edition-actuelle-bar-label strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .edition-actuelle-bar-groupe {
      display: flex;
      align-items: center;
      min-width: 0;
      margin-left: 1rem;
      padding-left: 1rem;
      border-left: 1px solid var(--mat-sys-outline-variant);
    }
    .edition-actuelle-bar-actions {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      margin-left: auto;
    }
    @media (max-width: 740px) {
      .edition-actuelle-bar {
        padding-inline: 0.75rem;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EditionActuelleBar {
  private readonly store = inject(EditionStore);

  protected readonly editionActuelle = computed(() => this.store.courant());
  protected readonly autresEditions = computed(() => this.store.autres());

  protected basculer(edition: Edition): void {
    this.store.basculer(edition);
  }
}
