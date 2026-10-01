import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { RealiseApi } from '../../core/api/realise-api';
import { errorPrefix } from '../../core/error-message';
import { RealisedLine } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';
import {
  CaseActivee,
  GrilleCase,
  GrilleEntete,
  LigneGrille,
  PlanningGrille,
} from '../planning-grille/planning-grille';
import {
  LigneRealise,
  colonnesSynthese,
  grilleRealise,
  lateReferenceDays,
  libelleIssue,
  unreferencedDays,
} from './realise';
import { heures, pourcentage } from './mesure';

/** The cell opened, as the URL keeps it. */
interface OpenedCell {
  jour: string;
  standId: string;
}

/**
 * « Réalisé vs planifié » : the gap between the plan the animateurs were sent
 * and the plan held, stand by stand over the elapsed days, read from
 * `GET /api/planning/realise`.
 *
 * The grid is the Planning page's shared grid (`pages/planning-grille`), its
 * cells coloured by the gap; a cell opens its shifts under it — the Changements
 * rendering of the Journée, kept to that stand and named, which only this admin
 * screen shows. The page says the realised is *declared*: what the tool
 * recorded, not a presence check-in. `?jour=&stand=` keeps the cell opened.
 */
@Component({
  selector: 'app-realise-page',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    OutputPanel,
    StatusMessage,
    PlanningGrille,
    GrilleEntete,
    GrilleCase,
  ],
  templateUrl: './realise-page.html',
  styleUrl: './realise-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RealisePage {
  private readonly api = inject(RealiseApi);

  private readonly rapportResource = resource({ loader: () => this.api.report() });
  protected readonly rapport = retainedValue(this.rapportResource);
  protected readonly chargement = this.rapportResource.isLoading;
  protected readonly erreur = errorText(this.rapportResource);

  protected readonly grille = computed(() => {
    const rapport = this.rapport();
    return rapport ? grilleRealise(rapport) : null;
  });
  protected readonly syntheses = colonnesSynthese();
  protected readonly unreferenced = computed(() => {
    const rapport = this.rapport();
    return rapport ? unreferencedDays(rapport) : [];
  });
  protected readonly lateReference = computed(() => {
    const rapport = this.rapport();
    return rapport ? lateReferenceDays(rapport) : [];
  });

  private readonly params = currentViewParams();
  protected readonly openedCell = signal<OpenedCell | null>(this.initialCell());

  private readonly detailResource = resource({
    params: () => this.openedCell() ?? undefined,
    loader: ({ params }) => this.api.detail(params.jour, params.standId),
  });
  protected readonly detail = computed(() =>
    this.detailResource.status() === 'error' ? null : this.detailResource.value(),
  );
  protected readonly detailChargement = this.detailResource.isLoading;
  protected readonly detailErreur = errorText(this.detailResource);

  protected readonly exportBusy = signal(false);
  protected readonly output = signal('');

  protected readonly heures = heures;
  protected readonly pourcentage = pourcentage;
  protected readonly libelleIssue = libelleIssue;

  constructor() {
    keepViewInQueryParams(() => ({
      jour: optionalParam(this.openedCell()?.jour),
      stand: optionalParam(this.openedCell()?.standId),
    }));
  }

  private initialCell(): OpenedCell | null {
    const jour = this.params.get('jour');
    const standId = this.params.get('stand');
    return jour && standId ? { jour, standId } : null;
  }

  protected rangee(ligne: LigneGrille): LigneRealise {
    return ligne as LigneRealise;
  }

  protected open(event: CaseActivee<LigneRealise>): void {
    this.openedCell.set({ jour: event.jour.key, standId: event.ligne.id });
  }

  protected close(): void {
    this.openedCell.set(null);
  }

  protected recharger(): void {
    this.rapportResource.reload();
    if (this.openedCell()) {
      this.detailResource.reload();
    }
  }

  /** Who held the seat, then who holds it: names, on this admin screen only. */
  protected titulaires(ligne: RealisedLine): string {
    const before = ligne.seat.avant?.nomAffiche ?? '—';
    const after = ligne.seat.apres?.nomAffiche ?? '—';
    return `${before} → ${after}`;
  }

  protected async exporter(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@realise.exporting:Construction de l'export CSV...`);
    try {
      this.output.set(await this.api.exportCsv());
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}
