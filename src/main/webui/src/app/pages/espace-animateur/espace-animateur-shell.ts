import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from '../../core/locale';
import { errorMessage } from '../../core/error-message';
import { BrandLogo } from '../../shared/brand-logo';
import { VersionFooter } from '../../shared/version-footer';

/**
 * Standalone layout of the espace animateur (issue #165): a minimal toolbar
 * (no admin navigation, no solver monitor, no polling) above the two tabs —
 * the animateur's planning and their demandes d'échange. The access token in
 * the URL is the whole credential: this shell loads everything from it and
 * the child pages read the shared `EspaceAnimateurService` state.
 */
@Component({
  selector: 'app-espace-animateur-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    BrandLogo,
    VersionFooter,
    FormsModule,
    MatToolbarModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './espace-animateur-shell.html',
  styleUrls: ['../../../styles/espace-animateur.css', '../../../styles/demandes.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceAnimateurShell {
  protected readonly espace = inject(EspaceAnimateurService);
  protected readonly locale: AppLocale = getStoredLocale();

  private readonly route = inject(ActivatedRoute);
  protected readonly jeton = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('jeton'))),
    {
      initialValue: null,
    },
  );

  constructor() {
    const jeton = this.route.snapshot.paramMap.get('jeton');
    if (jeton) {
      void this.espace.charger(jeton);
    }
  }

  /** Language messages resolve once at bootstrap, so switching reloads the page. */
  protected toggleLocale(): void {
    setStoredLocaleAndReload(this.locale === 'fr' ? 'en' : 'fr');
  }

  /* -------- Passwordless access: e-mail code against the valid token ------- */

  /** Masked address the code went to, `null` while none was requested. */
  protected readonly codeEnvoyeA = signal<string | null>(null);
  protected readonly codeSaisi = signal('');
  protected readonly authEnCours = signal(false);
  protected readonly erreurAuth = signal<string | null>(null);

  protected async demanderCode(): Promise<void> {
    this.authEnCours.set(true);
    this.erreurAuth.set(null);
    try {
      this.codeEnvoyeA.set(await this.espace.demanderCode());
      this.codeSaisi.set('');
    } catch (error) {
      this.erreurAuth.set(errorMessage(error));
    } finally {
      this.authEnCours.set(false);
    }
  }

  protected async validerCode(): Promise<void> {
    if (!this.codeSaisi().trim()) {
      return;
    }
    this.authEnCours.set(true);
    this.erreurAuth.set(null);
    try {
      // On success `charger` runs again with the fresh cookie: `authRequise`
      // flips back and the espace renders in place of this screen.
      await this.espace.validerCode(this.codeSaisi().trim());
    } catch (error) {
      this.erreurAuth.set(errorMessage(error));
    } finally {
      this.authEnCours.set(false);
    }
  }
}
