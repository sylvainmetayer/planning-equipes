import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ApiService } from '../../core/api.service';
import { MentionsLegales } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';

/**
 * Legal notice, shared by both sides of the application and reachable without
 * any credential — an animateur whose access link has expired is precisely the
 * reader who needs to know whom to write to.
 *
 * <p>Two kinds of content meet here. What the software actually does with
 * personal data is written in the page: it is verifiable from the domain model
 * and identical for every deployment. Who publishes and hosts the site, and
 * for how long the data is kept, come from the server's configuration —
 * facts only the operator knows, so the page states what is missing rather
 * than inventing a publisher.</p>
 */
@Component({
  selector: 'app-mentions-legales-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatToolbarModule, StatusMessage],
  templateUrl: './mentions-legales-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MentionsLegalesPage {
  protected readonly mentions = signal<MentionsLegales | null>(null);
  protected readonly erreur = signal('');

  /** True while nothing at all is configured: the page says so once, at the top. */
  protected readonly rienDeRenseigne = computed(() => {
    const mentions = this.mentions();
    return (
      mentions !== null &&
      [
        mentions.editeur,
        mentions.directeurPublication,
        mentions.hebergeur,
        mentions.contact,
        mentions.baseLegale,
        mentions.conservation
      ].every((valeur) => !valeur)
    );
  });

  /**
   * Only offered when there is somewhere to go back to: opened in a new tab
   * from the espace animateur — so the token URL is not lost — there is no
   * history behind this page.
   */
  protected readonly peutRevenir = signal(typeof history !== 'undefined' && history.length > 1);

  private readonly api = inject(ApiService);

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    try {
      this.mentions.set(await this.api.get<MentionsLegales>('/api/mentions-legales'));
    } catch (error) {
      this.erreur.set(error instanceof Error ? error.message : String(error));
    }
  }

  protected revenir(): void {
    history.back();
  }
}
