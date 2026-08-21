import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { MentionsLegales } from '../../core/models';
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
 * where they are, on the organisation running the festival.</p>
 */
@Component({
  selector: 'app-conditions-utilisation-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatToolbarModule, RouterLink, StatusMessage],
  templateUrl: './conditions-utilisation-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ConditionsUtilisationPage {
  protected readonly mentions = signal<MentionsLegales | null>(null);
  protected readonly erreur = signal('');

  /** Absent when the page was opened in a new tab from the espace animateur. */
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
