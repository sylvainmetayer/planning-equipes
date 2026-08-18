import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { GroupeCreneau } from '../core/models';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { ReferenceDataStore } from '../core/reference-data.store';
import { slugify } from '../core/slug';
import { SolverJobService } from '../core/solver-job.service';
import { PromptDialog, PromptDialogData } from './prompt-dialog';

/**
 * Active groupe de créneaux, shown and switchable from every screen (issue
 * #120). It rides in the "Édition actuelle" strip rather than in the toolbar:
 * both answer the same question — which dataset is on screen — and the strip
 * already exists for the wider of the two scopes.
 *
 * Switching reloads the page, exactly like switching edition: the active group
 * decides which créneaux (and therefore which calendars, exports and solver
 * input) every open screen is built from, and a reload is the only way to be
 * sure none keeps rendering the previous group's rows.
 *
 * Disabled while a solve is running, same lock as the Créneaux page: swapping
 * the group under the solver's feet would silently invalidate its result.
 */
@Component({
  selector: 'app-groupe-creneau-selector',
  imports: [MatButtonModule, MatDividerModule, MatIconModule, MatMenuModule, MatTooltipModule],
  template: `
    @if (groupeActif(); as actif) {
      <span class="groupe-creneau-selector">
        <mat-icon class="groupe-creneau-selector-icon">schedule</mat-icon>
        <span class="groupe-creneau-selector-label">
          <span i18n="@@groupeCreneauSelector.label">Groupe de créneaux :</span>
          <strong>{{ actif.nom }}</strong>
        </span>
        <button
          matButton
          [matMenuTriggerFor]="menu"
          [disabled]="verrouille() || enCours()"
          [matTooltip]="verrouille() ? tooltipVerrouille : ''"
          i18n-aria-label="@@groupeCreneauSelector.switchAriaLabel"
          aria-label="Changer de groupe de créneaux"
        >
          <span i18n="@@groupeCreneauSelector.switch">Changer</span>
          <mat-icon iconPositionEnd>expand_more</mat-icon>
        </button>
        <mat-menu #menu="matMenu">
          @for (groupe of autresGroupes(); track groupe.id) {
            <button mat-menu-item (click)="activer(groupe)">{{ groupe.nom }}</button>
          }
          @if (autresGroupes().length > 0) {
            <mat-divider />
          }
          <button mat-menu-item (click)="creer()">
            <mat-icon>add</mat-icon>
            <span i18n="@@groupeCreneauSelector.create">Nouveau groupe…</span>
          </button>
        </mat-menu>
      </span>
    }
  `,
  styles: `
    .groupe-creneau-selector {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    .groupe-creneau-selector-icon {
      flex-shrink: 0;
    }
    .groupe-creneau-selector-label {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    .groupe-creneau-selector-label strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    @media (max-width: 740px) {
      .groupe-creneau-selector-label span {
        display: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GroupeCreneauSelector {
  private readonly store = inject(ReferenceDataStore);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly jobs = inject(SolverJobService);
  private readonly dialog = inject(MatDialog);

  /** True while an activation or a creation is in flight. */
  protected readonly enCours = signal(false);
  protected readonly verrouille = computed(() => this.jobs.solverBusy());
  protected readonly tooltipVerrouille = $localize`:@@groupeCreneauSelector.lockedTooltip:Résolution en cours : le groupe de créneaux ne peut pas être changé.`;

  protected readonly groupeActif = computed(() => this.store.groupesCreneaux().find((groupe) => groupe.actif) ?? null);
  protected readonly autresGroupes = computed(() => this.store.groupesCreneaux().filter((groupe) => !groupe.actif));

  constructor() {
    // The shell never loads the whole referential; this component only needs
    // the group list, so it fetches that one collection when it is missing.
    if (this.store.groupesCreneaux().length === 0) {
      void this.store.reloadGroupesCreneaux();
    }
  }

  protected async activer(groupe: GroupeCreneau): Promise<void> {
    if (groupe.actif || this.verrouille() || this.enCours()) {
      return;
    }
    this.enCours.set(true);
    try {
      await this.store.activerGroupeCreneau(groupe.id);
      // The mismatch banner compares against the active group; refresh it
      // before the reload so a failed reload still leaves a coherent UI.
      await this.resolution.reload();
      this.rechargerPage();
    } finally {
      this.enCours.set(false);
    }
  }

  protected async creer(): Promise<void> {
    if (this.verrouille() || this.enCours()) {
      return;
    }
    const nom = await this.demanderNom();
    if (!nom) {
      return;
    }
    this.enCours.set(true);
    try {
      const id = slugify(
        nom,
        this.store.groupesCreneaux().map((groupe) => groupe.id)
      );
      await this.store.save<GroupeCreneau>('groupes-creneaux', { id, nom, actif: false }, null);
    } finally {
      this.enCours.set(false);
    }
  }

  private async demanderNom(): Promise<string | null> {
    const data: PromptDialogData = {
      title: $localize`:@@groupeCreneauSelector.createTitle:Nouveau groupe de créneaux`,
      label: $localize`:@@groupeCreneauSelector.createLabel:Nom du groupe`,
      confirmLabel: $localize`:@@groupeCreneauSelector.createConfirm:Créer`
    };
    return PromptDialog.ask(this.dialog, data);
  }

  /** Isolated so a test can stub it: jsdom has no navigation. */
  protected rechargerPage(): void {
    location.reload();
  }
}
