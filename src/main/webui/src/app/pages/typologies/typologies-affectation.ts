import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { LigneTypologie } from '../../core/models';
import { OutputPanel } from '../../shared/output-panel';

/**
 * The plan read by typologie of jeu: who actually holds each game, for how many
 * seats and how many hours, against who the referential vets on it (issue #590).
 *
 * <p>The Typologies screen was a pure referential, and its detail view listed
 * the animateurs <i>vetted</i> on a typologie — never the ones the solver put
 * there. Yet the typologie is the axis the FESTIVAL reasons about its games on, and
 * the one two constraints act on: a ceiling nobody can see is a ceiling nobody
 * can set.</p>
 *
 * <p>Read on demand rather than on load: the screen exists to manage the
 * referential, and an edition with no plan should not pay for an analysis
 * nobody asked for. An edition without a solved plan is a legitimate state,
 * said in words.</p>
 */
@Component({
  selector: 'app-typologies-affectation',
  imports: [
    DecimalPipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    OutputPanel,
  ],
  templateUrl: './typologies-affectation.html',
  styleUrl: './typologies-affectation.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypologiesAffectation {
  /** Narrows the reading to one typologie, for the detail view; empty shows them all. */
  readonly typologieId = signal<string | null>(null);

  protected readonly busy = signal(false);
  protected readonly output = signal('');
  protected readonly rapport = signal<LigneTypologie[] | null>(null);

  protected readonly colonnes = ['typologie', 'affectes', 'postes', 'heures', 'competents'];

  protected readonly lignes = computed(() => {
    const lignes = this.rapport() ?? [];
    const id = this.typologieId();
    return id ? lignes.filter((ligne) => ligne.typologie === id) : lignes;
  });

  /** True once loaded and nobody holds anything: the plan is empty, or not solved yet. */
  protected readonly aucuneAffectation = computed(
    () => this.rapport() !== null && this.lignes().every((ligne) => ligne.postes === 0),
  );

  /**
   * What this card has loaded for one typologie, or `null` while nobody asked
   * for the reading. Read by the detail view of the same page, so the two never
   * tell two stories about the same plan — and so the detail costs nothing
   * until the organiser has pressed « Lire le planning ».
   */
  ligne(typologieId: string): LigneTypologie | null {
    return (this.rapport() ?? []).find((ligne) => ligne.typologie === typologieId) ?? null;
  }

  private readonly planningApi = inject(PlanningApi);

  protected async load(): Promise<void> {
    this.busy.set(true);
    this.output.set('');
    try {
      this.rapport.set((await this.planningApi.typologiesReport()).typologies);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async exporter(): Promise<void> {
    this.busy.set(true);
    try {
      this.output.set(await this.planningApi.exportTypologies());
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }
}
