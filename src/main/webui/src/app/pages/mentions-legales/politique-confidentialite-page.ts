import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { MentionsLegales } from '../../core/models';
import { BrandLogo } from '../../shared/brand-logo';
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
  imports: [
    BrandLogo,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatToolbarModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './politique-confidentialite-page.html',
  styleUrl: '../../../styles/mentions-legales.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PolitiqueConfidentialitePage implements OnInit {
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

  /**
   * Whether this deployment runs any third-party tool worth a paragraph. The
   * section describing them is written for the deployment that has them: on
   * one that has neither, it announced two processings — including a transfer
   * outside the EU — that do not take place, which is the fastest way to lose
   * the reader's trust in the sentences they cannot check themselves.
   */
  protected readonly outilsTiers = computed(() => {
    const mentions = this.mentions();
    return !!mentions && (mentions.mesureAudience || mentions.suiviErreurs);
  });

  /**
   * Whether both of them run. The heading, the sentence that refers to them and
   * the objection paragraph are all written in the plural: hanging them on
   * `outilsTiers` instead would announce "Mesure d'audience et suivi technique"
   * above a list holding the error-monitoring bullet alone — the same unbacked
   * claim, one tool later.
   */
  protected readonly deuxOutils = computed(() => {
    const mentions = this.mentions();
    return !!mentions && mentions.mesureAudience && mentions.suiviErreurs;
  });

  /** Absent when the page was opened in a new tab from the espace animateur. */
  protected readonly peutRevenir = signal(typeof history !== 'undefined' && history.length > 1);

  private readonly adminApi = inject(AdminApi);

  ngOnInit(): void {
    void this.charger();
  }

  private async charger(): Promise<void> {
    try {
      this.mentions.set(await this.adminApi.legalNotice());
    } catch {
      // Le détail technique n'apprend rien au lecteur de cette page — souvent
      // un animateur sur son téléphone, réseau incertain : lui dire quoi faire
      // vaut mieux que lui montrer une erreur d'analyse JSON.
      this.erreur.set(
        $localize`:@@mentions.chargementImpossible:Ces informations n'ont pas pu être chargées. Réessayez dans un instant ; si cela persiste, prévenez l'organisation.`,
      );
    }
  }

  protected revenir(): void {
    history.back();
  }
}
