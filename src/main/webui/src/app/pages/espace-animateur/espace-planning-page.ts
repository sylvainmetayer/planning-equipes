import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { PosteAnimateurView } from '../../core/models';

interface JourPlanning {
  /** ISO date, `''` for postes without one. */
  date: string;
  postes: PosteAnimateurView[];
  /** True for a festival day without any seat: the card says « Repos » instead of listing shifts. */
  repos: boolean;
}

/**
 * The animateur's own planning (issue #165): their seats from the last
 * persisted solve, one card per day, with the teammates they will actually
 * work alongside — the same content as their PDF, always up to date.
 */
@Component({
  selector: 'app-espace-planning-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './espace-planning-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspacePlanningPage {
  protected readonly espace = inject(EspaceAnimateurService);

  /** Direct download links — the token in the URL is the whole credential. */
  protected readonly lienPdf = computed(() =>
    this.espace.jeton() ? `/api/espace-animateur/${this.espace.jeton()}/planning.pdf` : null
  );
  protected readonly lienIcs = computed(() =>
    this.espace.jeton() ? `/api/espace-animateur/${this.espace.jeton()}/planning.ics` : null
  );

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
    const jours: JourPlanning[] = Array.from(parJour.entries()).map(([date, postes]) => ({
      date,
      postes,
      repos: false
    }));
    // Rest days take their chronological place among the worked ones: a day
    // silently missing reads as an oversight, an explicit « Repos » card as a
    // decision. The server sends none for an animateur without any seat.
    for (const date of this.espace.vue()?.joursRepos ?? []) {
      jours.push({ date, postes: [], repos: true });
    }
    return jours.sort((a, b) => a.date.localeCompare(b.date));
  });
}
