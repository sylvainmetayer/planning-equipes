import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink } from '@angular/router';
import { GroupeStore } from '../core/groupe.store';
import { Groupe } from '../core/models';

/**
 * Persistent strip under the toolbar naming the edition (`groupe`) every screen
 * is currently reading from, with a one-click switcher. Every page's data
 * depends on that answer, so it is given once in the shell instead of on any
 * one page — see `docs/groupes.md`.
 *
 * Switching reloads the page rather than refreshing the stores: it swaps the
 * data behind *every* open screen at once, and a full reload is the only way
 * to guarantee no page keeps rendering the previous group's rows (same
 * reasoning as the language toggle). It is a rare, deliberate action.
 */
@Component({
  selector: 'app-groupe-actuel-bar',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
  template: `
    @if (groupeActuel(); as groupe) {
      <div class="groupe-actuel-bar" role="status">
        <mat-icon class="groupe-actuel-bar-icon">layers</mat-icon>
        <span class="groupe-actuel-bar-label">
          <span i18n="@@groupeActuel.label">Groupe actuel :</span>
          <strong>{{ groupe.nom }}</strong>
        </span>
        <span class="groupe-actuel-bar-actions">
          @if (autresGroupes().length > 0) {
            <button
              matButton
              [matMenuTriggerFor]="menu"
              i18n-aria-label="@@groupeActuel.switchAriaLabel"
              aria-label="Changer de groupe"
            >
              <span i18n="@@groupeActuel.switch">Changer</span>
              <mat-icon iconPositionEnd>expand_more</mat-icon>
            </button>
            <mat-menu #menu="matMenu">
              @for (autre of autresGroupes(); track autre.id) {
                <button mat-menu-item (click)="basculer(autre)">{{ autre.nom }}</button>
              }
            </mat-menu>
          }
          <a
            matButton
            routerLink="/groupes"
            i18n-aria-label="@@groupeActuel.manageAriaLabel"
            aria-label="Gérer les groupes"
          >
            <span i18n="@@groupeActuel.manage">Gérer</span>
          </a>
        </span>
      </div>
    }
  `,
  styles: `
    .groupe-actuel-bar {
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
    .groupe-actuel-bar-icon {
      flex-shrink: 0;
    }
    .groupe-actuel-bar-label {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    .groupe-actuel-bar-label strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .groupe-actuel-bar-actions {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      margin-left: auto;
    }
    @media (max-width: 740px) {
      .groupe-actuel-bar {
        padding-inline: 0.75rem;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GroupeActuelBar {
  private readonly store = inject(GroupeStore);

  protected readonly groupeActuel = computed(() => this.store.courant());
  protected readonly autresGroupes = computed(() => this.store.autres());

  protected basculer(groupe: Groupe): void {
    this.store.basculer(groupe);
  }
}
