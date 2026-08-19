import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { PosteAnimateurView } from '../../core/models';

interface JourPlanning {
  /** ISO date, `''` for postes without one. */
  date: string;
  postes: PosteAnimateurView[];
}

/**
 * The animateur's own planning (issue #165): their seats from the last
 * persisted solve, one card per day, with the teammates they will actually
 * work alongside — the same content as their PDF, always up to date.
 */
@Component({
  selector: 'app-espace-planning-page',
  imports: [DatePipe, MatCardModule, MatIconModule],
  templateUrl: './espace-planning-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspacePlanningPage {
  protected readonly espace = inject(EspaceAnimateurService);

  protected readonly jours = computed<JourPlanning[]>(() => {
    const parJour = new Map<string, PosteAnimateurView[]>();
    for (const poste of this.espace.vue()?.postes ?? []) {
      const date = poste.date ?? '';
      const existants = parJour.get(date);
      if (existants) {
        existants.push(poste);
      } else {
        parJour.set(date, [poste]);
      }
    }
    return Array.from(parJour.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, postes]) => ({ date, postes }));
  });
}
