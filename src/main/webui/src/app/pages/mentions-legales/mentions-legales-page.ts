import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
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
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatToolbarModule, RouterLink, StatusMessage],
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
    } catch {
      // Le détail technique n'apprend rien au lecteur de cette page — souvent
      // un animateur sur son téléphone, réseau incertain : lui dire quoi faire
      // vaut mieux que lui montrer une erreur d'analyse JSON.
      this.erreur.set(
        $localize`:@@mentions.chargementImpossible:Ces informations n'ont pas pu être chargées. Réessayez dans un instant ; si cela persiste, prévenez l'organisation.`
      );
    }
  }

  protected revenir(): void {
    history.back();
  }
}
