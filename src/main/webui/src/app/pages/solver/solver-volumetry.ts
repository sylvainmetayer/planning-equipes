import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { ReferenceDataStore } from '../../core/reference-data.store';

/**
 * Volumetry of the problem Timefold is about to explore, recomputed live as
 * `referenceData`'s signals change (a CRUD edit, a sample load, a CSV/SQL
 * import...). `animateurCount`/`posteCount`/`adHocConstraintCount` come from
 * `/api/planning/volumetrie`, built server-side the exact same way an actual
 * solve is (one poste per required seat, not per stand) so they never drift
 * from what the solver logs report. `creneauCount` counts the edition's slots
 * — exactly what the solver consumes.
 */
@Component({
  selector: 'app-solver-volumetry',
  imports: [DecimalPipe, MatCardModule],
  templateUrl: './solver-volumetry.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SolverVolumetry {
  private readonly referenceData = inject(ReferenceDataStore);

  protected readonly animateurCount = computed(() => this.referenceData.scale().animateurCount);
  protected readonly posteCount = computed(() => this.referenceData.scale().posteCount);
  /** Renforts above the declared staffing: a bonus beside the need, never inside it. */
  protected readonly posteOptionnelCount = computed(
    () => this.referenceData.scale().posteOptionnelCount,
  );
  protected readonly adHocConstraintCount = computed(
    () => this.referenceData.scale().contrainteAdHocCount,
  );
  protected readonly creneauCount = computed(() => this.referenceData.creneaux().length);
  /** Hours the seats add up to, stand closures deducted — the same basis as the Heures page. */
  protected readonly hoursToFill = computed(() => this.referenceData.scale().hoursToFill);
  /** Bonus hours the renforts open: a capacity the edition may spend, never owes. */
  protected readonly hoursOptionnelles = computed(
    () => this.referenceData.scale().hoursOptionnelles,
  );
  /** Legal ceiling of what the animateurs may work over the event, unavailable days deducted. */
  protected readonly hoursAvailable = computed(() => this.referenceData.scale().hoursAvailable);

  /**
   * Hours to fill over hours available. A ceiling, not a forecast: competences,
   * rest between shifts and the pause rule all take from the denominator, so a
   * ratio close to 1 is already an infeasible plan. `null` while nothing is
   * offered, so the template says nothing rather than dividing by zero.
   */
  protected readonly fillRatio = computed(() => {
    const available = this.hoursAvailable();
    return available > 0 ? this.hoursToFill() / available : null;
  });

  /**
   * Timefold's own "approximate problem scale": log10 of the search space size,
   * i.e. `entityCount * log10(valueCount)` (valueCount ^ entityCount, not a
   * product of the counts above — a plain product would be off by thousands of
   * orders of magnitude and isn't worth displaying as a number).
   *
   * This is a naive upper bound: it counts every assignment, including the ones
   * no constraint would ever allow (an animateur on several postes of the same
   * créneau, or on a day they are not available). Narrowing it does not help —
   * one-poste-per-créneau exclusivity only removes ~44 orders of magnitude, and
   * even assuming 10 eligible animateurs per poste still leaves 10^2823. Hence
   * the wording in the template: "espace de recherche", not "combinaisons".
   */
  protected readonly problemScale = computed(() => {
    const animateurs = this.animateurCount();
    // Timefold's real entity count: the renforts are entities too, they are
    // only kept out of the figures labelled « à pourvoir » (ADR 0046).
    const postes = this.posteCount() + this.posteOptionnelCount();
    return animateurs > 1 && postes > 0 ? Math.round(postes * Math.log10(animateurs)) : 0;
  });
}
