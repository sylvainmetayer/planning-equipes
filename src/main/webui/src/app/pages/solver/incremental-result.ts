import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { intlLocale } from '../../core/locale';
import { ChangementAffectation, StatistiquesIncremental } from '../../core/models';

/**
 * What the last incremental re-solve (issue #86) reopened, and which crews
 * came out of it with a different planning. Display only: the page decides
 * when a result is current and hands it over.
 */
@Component({
  selector: 'app-incremental-result',
  imports: [MatCardModule, MatTableModule],
  templateUrl: './incremental-result.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class IncrementalResult {
  readonly stats = input.required<StatistiquesIncremental>();
  readonly changements = input.required<ChangementAffectation[]>();

  protected readonly columns = ['quand', 'stand', 'avant', 'apres'];

  /** An empty crew is a hole in the plan, and must read as one. */
  protected crewLabel(crew: string[]): string {
    return crew.length === 0 ? $localize`:@@solver.incremental.personne:(personne)` : crew.join(', ');
  }

  protected whenLabel(changement: ChangementAffectation): string {
    const day = changement.date
      ? new Date(`${changement.date}T00:00:00`).toLocaleDateString(intlLocale(), {
          weekday: 'short',
          day: 'numeric',
          month: 'short'
        })
      : '';
    const hours = changement.heureDebut && changement.heureFin
      ? `${changement.heureDebut.slice(0, 5)} – ${changement.heureFin.slice(0, 5)}`
      : '';
    return [day, hours].filter((part) => part.length > 0).join(' ');
  }
}
