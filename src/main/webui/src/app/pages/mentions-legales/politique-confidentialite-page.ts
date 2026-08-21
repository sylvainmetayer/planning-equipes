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
 * Privacy policy: what the application does with personal data, who it reaches,
 * and what a person can demand. Public and outside both shells, like the legal
 * notice it complements — an animateur reading it is the data subject, and the
 * reader with an expired access link is exactly the one who needs it.
 *
 * <p>The split follows the usual one: the legal notice answers "who publishes
 * and hosts this site", this page answers "what happens to my data". Both read
 * the same `/api/mentions-legales` payload, since the controller and the
 * contact address are deployment facts rather than page content.</p>
 */
@Component({
  selector: 'app-politique-confidentialite-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatToolbarModule, RouterLink, StatusMessage],
  templateUrl: './politique-confidentialite-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PolitiqueConfidentialitePage {
  protected readonly mentions = signal<MentionsLegales | null>(null);
  protected readonly erreur = signal('');

  /**
   * The data controller, falling back to the publisher. "Éditeur" belongs to
   * the LCEN and "responsable de traitement" to the GDPR: usually the same
   * body, not necessarily — so the deployment may name it separately.
   */
  protected readonly responsable = computed(() => {
    const mentions = this.mentions();
    return mentions ? mentions.responsableTraitement || mentions.editeur : '';
  });

  /** Absent when the page was opened in a new tab from the espace animateur. */
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
