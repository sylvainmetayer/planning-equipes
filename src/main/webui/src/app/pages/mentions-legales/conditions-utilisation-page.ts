import {
  ChangeDetectionStrategy,
  Component,
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
 * Terms of use. Public and outside both shells, like the legal notice and the
 * privacy policy it sits beside.
 *
 * <p>Its purpose is to draw one line and draw it plainly: the application
 * <b>computes proposals</b>, the organisation <b>decides</b>. That distinction
 * is not decoration — it is what keeps the schedule a human decision (and
 * article 22 GDPR out of play), and what states that the duties of an
 * employer, of a data controller and of whoever answers for labour law stay
 * where they are, on the organisation running the event.</p>
 */
@Component({
  selector: 'app-conditions-utilisation-page',
  imports: [
    BrandLogo,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatToolbarModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './conditions-utilisation-page.html',
  styleUrl: '../../../styles/mentions-legales.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConditionsUtilisationPage implements OnInit {
  protected readonly mentions = signal<MentionsLegales | null>(null);
  protected readonly erreur = signal('');

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
