import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { ComparaisonSnapshots, CoteComparaison, PlanSnapshot } from '../../core/models';
import { ScoreReadingPanel } from '../../shared/lecture-score';
import { StatusMessage } from '../../shared/status-message';
import { LigneMetrique, construireLignesMetriques } from './comparateur-metrics';
import { errorMessage } from '../../core/error-message';
import { bandeLabel, libelleDate } from '../../core/consigne-wording';
import { DosageDifference, dosageDifferences } from '../../core/dosage';

/**
 * The A/B comparator (issue #70), opened as a panel beside « Versions du plan »
 * (issue #702): the two sides come from the rows ticked there, the older one
 * as the reference (A). A pure read of metrics already measured — it never
 * triggers a solve.
 */
@Component({
  selector: 'app-comparaison-panel',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
    ScoreReadingPanel,
  ],
  templateUrl: './comparaison-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparaisonPanel {
  /** The reference side: a snapshot id as text, or `courant` for the plan in place. */
  readonly base = input.required<string>();
  /** The variant side, same selectors. */
  readonly variante = input.required<string>();
  /** The snapshots the table lists, to name the stale sides. */
  readonly snapshots = input<readonly PlanSnapshot[]>([]);
  readonly closed = output<void>();

  protected readonly columns = ['metrique', 'base', 'variante', 'delta'];
  protected readonly columnsViolations = ['contrainte', 'baseViolations', 'varianteViolations'];

  protected readonly comparaison = signal<ComparaisonSnapshots | null>(null);
  protected readonly chargement = signal(false);
  protected readonly error = signal('');

  protected readonly lignes = computed<LigneMetrique[]>(() => {
    const resultat = this.comparaison();
    return resultat ? construireLignesMetriques(resultat.base.kpi, resultat.variante.kpi) : [];
  });

  /** True when either side had its KPI recomputed from the snapshot content (degraded mode). */
  protected readonly kpiRecalcule = computed(() => {
    const resultat = this.comparaison();
    return !!resultat && (resultat.base.kpiRecalcule || resultat.variante.kpiRecalcule);
  });

  private readonly planningApi = inject(PlanningApi);

  constructor() {
    effect(() => {
      const base = this.base();
      const variante = this.variante();
      untracked(() => void this.comparer(base, variante));
    });
  }

  /** The last request wins: ticking another pair while one loads drops the first answer. */
  private request = 0;

  protected async comparer(base: string, variante: string): Promise<void> {
    const request = ++this.request;
    this.chargement.set(true);
    this.error.set('');
    this.comparaison.set(null);
    try {
      const resultat = await this.planningApi.compareSnapshots(base, variante);
      if (request === this.request) {
        this.comparaison.set(resultat);
      }
    } catch (error) {
      if (request === this.request) {
        this.error.set(errorMessage(error));
      }
    } finally {
      if (request === this.request) {
        this.chargement.set(false);
      }
    }
  }

  /**
   * Labels of the compared sides whose referential has moved since the capture.
   * Read from the comparison actually displayed, not from the pickers: the
   * warning must describe the table on screen, which an untouched selection
   * change would otherwise contradict.
   */
  protected readonly cotesPerimes = computed(() => {
    const resultat = this.comparaison();
    if (!resultat) {
      return [];
    }
    const byId = new Map(this.snapshots().map((snapshot) => [snapshot.id, snapshot]));
    return [resultat.base, resultat.variante]
      .map((cote) => (cote.snapshotId === null ? null : byId.get(cote.snapshotId)))
      .filter((snapshot): snapshot is PlanSnapshot => snapshot !== undefined && snapshot !== null)
      .filter((snapshot) => snapshot.perime)
      .map((snapshot) => snapshot.libelle);
  });

  /** The consignes one side was captured under (issue #4), worded; `null` when that side predates the capture. */
  protected consignesOf(cote: CoteComparaison): string[] | null {
    return (
      cote.consignes?.map(
        (consigne) =>
          `${libelleDate(consigne.date)} · ${bandeLabel(consigne.fermetureDebut, consigne.fermetureFin)} · ${consigne.motif}`,
      ) ?? null
    );
  }

  /**
   * What the two weightings disagree on, rule by rule — shown only when the
   * server says the plans were solved under different dosages, and empty
   * when either dosage is unknown.
   */
  protected readonly dosageDifferences = computed((): DosageDifference[] => {
    const resultat = this.comparaison();
    const base = resultat?.base.kpi.dosage;
    const variante = resultat?.variante.kpi.dosage;
    return resultat?.dosagesDifferents && base && variante ? dosageDifferences(base, variante) : [];
  });

  /** Column header of one side: its label, or the "current plan" wording it has none. */
  protected libelleCote(cote: CoteComparaison): string {
    return cote.libelle ?? $localize`:@@comparateur.cote.courant:Plan actuellement persisté`;
  }

  /**
   * The reading of each side, its heading worded once: each side reads its
   * own plan — its own edition too — never a mix of the two.
   */
  protected readonly readings = computed(() => {
    const comparaison = this.comparaison();
    if (!comparaison) {
      return [];
    }
    return [comparaison.base, comparaison.variante].map((cote) => {
      const libelle = this.libelleCote(cote);
      return {
        heading: $localize`:@@comparateur.lecture.titre:Lecture du score — ${libelle}:cote:`,
        sentences: cote.kpi.lecture ?? [],
      };
    });
  });

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
