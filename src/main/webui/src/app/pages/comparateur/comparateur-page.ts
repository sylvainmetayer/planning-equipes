import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { intlLocale } from '../../core/locale';
import { ComparaisonSnapshots, CoteComparaison, PlanSnapshot } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';
import { LigneMetrique, construireLignesMetriques } from './comparateur-metrics';
import { errorMessage } from '../../core/error-message';

/** Value designating the currently persisted plan instead of a snapshot id. */
const COURANT = 'courant';

/**
 * A/B comparator (issue #70): a baseline against a variant, side by side, with
 * the direction of each variation made explicit. A pure read of metrics
 * already measured — it never triggers a solve.
 *
 * <p>Both sides are picked among the snapshots of <b>every</b> edition, plus
 * the plan currently persisted: since #172 a variant of an edition is another
 * edition, so the pair worth comparing usually straddles two of them.</p>
 */
@Component({
  selector: 'app-comparateur-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './comparateur-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparateurPage {
  protected readonly columns = ['metrique', 'base', 'variante', 'delta'];
  protected readonly columnsViolations = ['contrainte', 'baseViolations', 'varianteViolations'];
  protected readonly courant = COURANT;

  protected readonly instantanes = signal<PlanSnapshot[]>([]);
  protected readonly baseId = signal<string>(COURANT);
  protected readonly varianteId = signal<string>('');
  protected readonly comparaison = signal<ComparaisonSnapshots | null>(null);
  protected readonly chargement = signal(false);
  protected readonly error = signal('');

  protected readonly lignes = computed<LigneMetrique[]>(() => {
    const comparaison = this.comparaison();
    return comparaison
      ? construireLignesMetriques(comparaison.base.kpi, comparaison.variante.kpi)
      : [];
  });

  protected readonly pretAComparer = computed(
    () => this.baseId() !== '' && this.varianteId() !== '' && this.baseId() !== this.varianteId(),
  );

  /** Degraded mode: at least one side predates KPI capture and had to be recomputed. */
  protected readonly kpiRecalcule = computed(() => {
    const comparaison = this.comparaison();
    return (
      comparaison !== null && (comparaison.base.kpiRecalcule || comparaison.variante.kpiRecalcule)
    );
  });

  private readonly planningApi = inject(PlanningApi);

  constructor() {
    void this.chargerInstantanes();
  }

  /** Reloads the pickers: a solve run in another tab adds snapshots. */
  protected async rafraichir(): Promise<void> {
    await this.chargerInstantanes();
  }

  /** Selector sent to the API for a snapshot side — its id, as text. */
  protected valeurSelection(snapshot: PlanSnapshot): string {
    return String(snapshot.id);
  }

  private async chargerInstantanes(): Promise<void> {
    this.error.set('');
    this.chargement.set(true);
    try {
      // Every edition's snapshots, not just the current one's: the variant of
      // an edition is another edition.
      const instantanes = await this.planningApi.comparableSnapshots();
      this.instantanes.set(instantanes);
      if (this.varianteId() === '' && instantanes.length > 0) {
        this.varianteId.set(String(instantanes[0].id));
      }
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async comparer(): Promise<void> {
    if (!this.pretAComparer()) {
      return;
    }
    this.chargement.set(true);
    this.error.set('');
    this.comparaison.set(null);
    try {
      this.comparaison.set(
        await this.planningApi.compareSnapshots(this.baseId(), this.varianteId()),
      );
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.chargement.set(false);
    }
  }

  /** Label of a snapshot in the picker: what it is, in which edition, and when. */
  protected libelle(snapshot: PlanSnapshot): string {
    const date = snapshot.creeLe ? new Date(snapshot.creeLe).toLocaleString(intlLocale()) : '';
    const edition = snapshot.editionNom ?? snapshot.editionId;
    return [snapshot.libelle, edition, date].filter((part) => part.length > 0).join(' — ');
  }

  /** Column header of one side: its label, or the "current plan" wording it has none. */
  protected libelleCote(cote: CoteComparaison): string {
    return cote.libelle ?? $localize`:@@comparateur.cote.courant:Plan actuellement persisté`;
  }

  protected deltaLabel(ligne: LigneMetrique): string {
    if (ligne.delta === null) {
      return '—';
    }
    const signe = ligne.delta > 0 ? '+' : '';
    const valeur = `${signe}${arrondi(ligne.delta)}`;
    if (ligne.tendance === 'amelioration') {
      return $localize`:@@comparateur.delta.amelioration:${valeur}:delta: (amélioration)`;
    }
    if (ligne.tendance === 'degradation') {
      return $localize`:@@comparateur.delta.degradation:${valeur}:delta: (dégradation)`;
    }
    return valeur;
  }

  protected violationsLabel(valeur: number | null): string {
    return valeur === null
      ? $localize`:@@comparateur.violations.inconnu:non mesuré`
      : String(valeur);
  }
}

function arrondi(valeur: number): string {
  return Number.isInteger(valeur) ? String(valeur) : valeur.toFixed(1);
}
