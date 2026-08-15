import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ApiService } from '../core/api.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { GroupeCreneau } from '../core/models';

/**
 * Persistent strip under the toolbar naming the group every screen is
 * currently reading from, with a one-click switcher. The active group already
 * decides which créneaux the solver sees, and is meant to widen into a full
 * scope over the whole referential (see `docs/groupes.md`) — which makes
 * "which group am I looking at?" a question every page depends on, so it is
 * answered once in the shell instead of on the créneaux page only.
 *
 * Switching reloads the page rather than refreshing the stores: it swaps the
 * data behind *every* open screen at once, and a full reload is the only way
 * to guarantee no page keeps rendering the previous group's rows (same
 * reasoning as the language toggle). It is a rare, deliberate action.
 */
@Component({
  selector: 'app-groupe-actuel-bar',
  imports: [MatButtonModule, MatIconModule, MatMenuModule],
  template: `
    @if (groupeActuel(); as groupe) {
      <div class="groupe-actuel-bar" role="status">
        <mat-icon class="groupe-actuel-bar-icon">layers</mat-icon>
        <span class="groupe-actuel-bar-label">
          <span i18n="@@groupeActuel.label">Groupe actuel :</span>
          <strong>{{ groupe.nom }}</strong>
        </span>
        @if (autresGroupes().length > 0) {
          <button
            matButton
            class="groupe-actuel-bar-switch"
            [matMenuTriggerFor]="menu"
            [disabled]="bascule()"
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
    .groupe-actuel-bar-switch {
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
  private readonly api = inject(ApiService);
  private readonly resolution = inject(PlanningResolutionStore);

  /** True while the switch request is in flight, so it cannot be fired twice. */
  protected readonly bascule = signal(false);

  protected readonly groupeActuel = computed(() => this.resolution.activeGroupe());
  protected readonly autresGroupes = computed(() =>
    this.resolution.groupesCreneaux().filter((groupe) => !groupe.actif)
  );

  /** Overridable so the test can assert the reload without navigating. */
  protected reloadPage(): void {
    window.location.reload();
  }

  protected async basculer(groupe: GroupeCreneau): Promise<void> {
    if (this.bascule()) {
      return;
    }
    this.bascule.set(true);
    try {
      await this.api.put(`/api/groupes-creneaux/${encodeURIComponent(groupe.id)}/actif`, {});
      this.reloadPage();
    } finally {
      this.bascule.set(false);
    }
  }
}
