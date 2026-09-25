import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
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
import { ScoreReadingPanel } from '../../shared/lecture-score';
import { StatusMessage } from '../../shared/status-message';
import { LigneMetrique, construireLignesMetriques } from './comparateur-metrics';
import { errorMessage } from '../../core/error-message';
import { bandeLabel, libelleDate } from '../../core/consigne-wording';
import { DosageDifference, dosageDifferences } from '../../core/dosage';

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
    ScoreReadingPanel,
  ],
  templateUrl: './comparateur-page.html',
  styleUrl: './comparateur-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparateurPage implements OnInit {
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

  ngOnInit(): void {
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

  /**
   * Label of a snapshot in the picker: what it is, in which edition, when, and
   * whether it is still current (issue #170). A comparison is a decision aid,
   * so a side computed before its referential moved has to say so — here it
   * changes nothing about what may be compared, only about what the numbers
   * mean.
   */
  protected libelle(snapshot: PlanSnapshot): string {
    const date = snapshot.creeLe ? new Date(snapshot.creeLe).toLocaleString(intlLocale()) : '';
    const edition = snapshot.editionNom ?? snapshot.editionId;
    const fraicheur = snapshot.perime ? $localize`:@@comparateur.option.perime:périmé` : '';
    return [snapshot.libelle, edition, date, fraicheur]
      .filter((part) => part.length > 0)
      .join(' — ');
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
    const byId = new Map(this.instantanes().map((snapshot) => [snapshot.id, snapshot]));
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
