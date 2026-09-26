import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { ReferenceDataStore } from '../../core/reference-data.store';

/**
 * Volumetry of the problem the next solve is given, folded under its title
 * (issue #719): read when a solve surprises, not before each one. The
 * « problem scale » it used to print (10^N) said nothing an organiser could
 * act on and is gone. Recomputed live as
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
  protected readonly adHocConstraintCount = computed(
    () => this.referenceData.scale().contrainteAdHocCount,
  );
  protected readonly creneauCount = computed(() => this.referenceData.creneaux().length);
  /** Hours the seats add up to, stand closures deducted — the same basis as the Heures page. */
  protected readonly hoursToFill = computed(() => this.referenceData.scale().hoursToFill);
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
}
