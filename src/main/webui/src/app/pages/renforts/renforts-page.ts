import { DecimalPipe, PercentPipe } from '@angular/common';
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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { libelleJour } from '../../core/horaire-stand';
import { LigneRenfort } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { TableFilter } from '../../shared/table-filter';
import {
  TriRenforts,
  cellForDay,
  heuresInutilisees,
  lignesAffichees,
  bonusShare,
  readTri,
  tauxEmploi,
} from './renforts';

/**
 * « Renforts » (issue #505, ADR 0046): where the bonus hours are, so a budget
 * that shrinks knows what to cut.
 *
 * <p>Every other screen reads the renforts as a bonus and nothing more — an
 * icon on the day calendar, « + 1 optionnel » on the Ouvertures grid, « 2/3 +1 »
 * on the PDF. None of them adds them up, and adding them up is the whole
 * question here: three stands may hold two thirds of the margin, and half of it
 * may never have been staffed.</p>
 *
 * <p>Read-only on purpose. The lever is a stand's {@code effectifMax}, and it
 * stays where it already lives: each row links to its fiche, so the guard
 * « min ≤ max » and the concurrency stamp keep a single home.</p>
 */
@Component({
  selector: 'app-renforts-page',
  imports: [
    DecimalPipe,
    PercentPipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    RouterLink,
    TableFilter,
  ],
  templateUrl: './renforts-page.html',
  styleUrl: './renforts-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like a partial.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RenfortsPage {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly rapport = resource({
    loader: () => this.analysesApi.renforts(),
  });

  // Both read back from the URL, not only written to it: a shared link opens
  // on the reading it names, and a refresh keeps it (ADR 0012).
  protected readonly filtre = signal(this.route.snapshot.queryParamMap.get('q') ?? '');
  protected readonly tri = signal<TriRenforts>(
    readTri(this.route.snapshot.queryParamMap.get('tri')),
  );

  constructor() {
    // View state in the URL (ADR 0012): a refresh keeps the reading, and the
    // link is shareable. This page owns `tri` and `q`, and nothing else.
    keepViewInQueryParams(() => ({
      tri: this.tri() === 'ouvertes' ? null : this.tri(),
      q: this.filtre() || null,
    }));
  }

  protected readonly erreur = errorText(this.rapport);

  protected readonly jours = computed(() => this.rapport.value()?.jours ?? []);

  protected readonly lignes = computed(() =>
    lignesAffichees(this.rapport.value(), this.filtre(), this.tri()),
  );

  /** Total of the rows on screen, so a filtered reading adds up to what it shows. */
  protected readonly totalOuvertes = computed(() =>
    this.lignes().reduce((somme, ligne) => somme + ligne.heuresOuvertes, 0),
  );

  protected readonly totalPourvues = computed(() =>
    this.lignes().reduce((somme, ligne) => somme + ligne.heuresPourvues, 0),
  );

  protected readonly tauxEmploi = computed(() => tauxEmploi(this.rapport.value()));

  protected readonly bonusShare = computed(() => bonusShare(this.rapport.value()));

  protected readonly planEnregistre = computed(() => this.rapport.value()?.planEnregistre ?? false);

  protected readonly message = computed(() => this.rapport.value()?.message ?? null);

  protected inutilisees(ligne: LigneRenfort): number {
    return heuresInutilisees(ligne);
  }

  protected cellule(
    ligne: LigneRenfort,
    date: string,
  ): { ouvertes: number; pourvues: number } | null {
    return cellForDay(ligne, date);
  }

  protected jourCourt(date: string): string {
    return libelleJour(date);
  }

  /** What a day cell holds, spelled out for a reader who cannot see the two stacked figures. */
  protected celluleInfobulle(cellule: { ouvertes: number; pourvues: number }): string {
    return $localize`:@@renforts.celluleInfobulle:${cellule.pourvues}:pourvues: h pourvues sur ${cellule.ouvertes}:ouvertes: h ouvertes`;
  }

  protected changerTri(tri: TriRenforts): void {
    this.tri.set(tri);
  }
}
