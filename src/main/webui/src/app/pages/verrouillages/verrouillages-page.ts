import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { TypeVerrouillage, VerrouillagePlanning } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';

const TYPE_VALUES: TypeVerrouillage[] = ['ANIMATEUR', 'STAND', 'JOUR', 'CRENEAU'];

/** Called lazily (never at module scope, see `app.ts`'s `buildNavGroups`). */
function typeLabel(value: TypeVerrouillage): string {
  switch (value) {
    case 'ANIMATEUR':
      return $localize`:@@verrouillages.type.animateur:Animateur`;
    case 'STAND':
      return $localize`:@@verrouillages.type.stand:Stand`;
    case 'JOUR':
      return $localize`:@@verrouillages.type.jour:Journée`;
    case 'CRENEAU':
      return $localize`:@@verrouillages.type.creneau:Créneau`;
  }
}

interface VerrouillageRow extends VerrouillagePlanning {
  typeLabel: string;
  cibleLabel: string;
  /** False for a lock recorded on another groupe de créneaux: kept, but dormant. */
  actif: boolean;
}

/**
 * Management of the partial planning locks (issue #87). Freezing a target
 * pins, on the next solve, every seat it covers that the last persisted solve
 * had staffed — empty seats are never frozen, so a lock can't make a hole
 * permanent.
 */
@Component({
  selector: 'app-verrouillages-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './verrouillages-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class VerrouillagesPage {
  protected readonly columns = ['type', 'cible', 'raison', 'groupe', 'actions'];
  protected readonly types = TYPE_VALUES.map((value) => ({ value, label: typeLabel(value) }));
  protected readonly store = inject(ReferenceDataStore);
  protected readonly verrous = inject(VerrouillageStore);
  protected readonly jobs = inject(SolverJobService);
  /** Locking is disabled while a solve runs: it would not be taken into account by the run in progress. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly type = signal<TypeVerrouillage>('JOUR');
  protected readonly animateurId = signal('');
  protected readonly standId = signal('');
  protected readonly creneauId = signal<number | ''>('');
  protected readonly jour = signal('');
  protected readonly raison = signal('');

  /** Distinct festival days, from the créneaux of the reference data. */
  protected readonly jours = computed(() =>
    Array.from(new Set(this.store.creneaux().map((creneau) => creneau.date))).sort()
  );

  protected readonly rows = computed<VerrouillageRow[]>(() =>
    this.verrous.verrouillages().map((verrouillage) => ({
      ...verrouillage,
      typeLabel: typeLabel(verrouillage.type),
      cibleLabel: this.cibleLabel(verrouillage),
      actif: verrouillage.groupeCreneauId === this.verrous.groupeActifId()
    }))
  );

  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

  constructor() {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    try {
      await Promise.all([this.store.reload(), this.verrous.reload()]);
    } catch (error) {
      this.report(error);
    }
  }

  /** True when the form holds a target matching the selected type. */
  protected readonly cibleRenseignee = computed(() => {
    switch (this.type()) {
      case 'ANIMATEUR':
        return !!this.animateurId();
      case 'STAND':
        return !!this.standId();
      case 'CRENEAU':
        return this.creneauId() !== '';
      case 'JOUR':
        return !!this.jour();
    }
  });

  protected async verrouiller(): Promise<void> {
    if (!this.cibleRenseignee()) {
      return;
    }
    const type = this.type();
    try {
      await this.verrous.create({
        type,
        animateurId: type === 'ANIMATEUR' ? this.animateurId() : null,
        standId: type === 'STAND' ? this.standId() : null,
        creneauId: type === 'CRENEAU' ? Number(this.creneauId()) : null,
        jour: type === 'JOUR' ? this.jour() : null,
        raison: this.raison() || null
      });
      this.raison.set('');
      this.notifications.notify({
        title: $localize`:@@verrouillages.created:Verrouillage enregistré.`,
        variant: 'success',
        timeout: 4000
      });
    } catch (error) {
      this.report(error);
    }
  }

  protected async deverrouiller(row: VerrouillageRow): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@verrouillages.deleteTitle:Déverrouiller ${row.cibleLabel}:cible: ?`,
      message: $localize`:@@verrouillages.deleteMessage:La prochaine résolution pourra de nouveau modifier ces affectations.`,
      confirmLabel: $localize`:@@verrouillages.deleteConfirm:Déverrouiller`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    try {
      await this.verrous.remove(row.id);
      this.notifications.notify({
        title: $localize`:@@verrouillages.deleted:Verrouillage supprimé.`,
        variant: 'success',
        timeout: 4000
      });
    } catch (error) {
      this.report(error);
    }
  }

  /** Human-readable target: names rather than the raw ids the backend stores. */
  private cibleLabel(verrouillage: VerrouillagePlanning): string {
    switch (verrouillage.type) {
      case 'ANIMATEUR': {
        const animateur = this.store.animateurs().find((candidate) => candidate.id === verrouillage.animateurId);
        return animateur ? `${animateur.prenom} ${animateur.nom}`.trim() : (verrouillage.animateurId ?? '—');
      }
      case 'STAND': {
        const stand = this.store.stands().find((candidate) => candidate.id === verrouillage.standId);
        return stand?.nom || (verrouillage.standId ?? '—');
      }
      case 'JOUR':
        return verrouillage.jour ?? '—';
      case 'CRENEAU': {
        const creneau = this.store.creneaux().find((candidate) => candidate.id === verrouillage.creneauId);
        return creneau ? `${creneau.date} ${creneau.heureDebut}–${creneau.heureFin}` : String(verrouillage.creneauId ?? '—');
      }
    }
  }

  private report(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: error instanceof Error ? error.message : String(error),
      variant: 'error'
    });
  }
}
