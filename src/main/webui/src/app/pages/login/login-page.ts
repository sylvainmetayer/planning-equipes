import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { APP_CONFIG } from '../../core/app-config';
import { BRANDING } from '../../core/branding';
import { signOut, signedInWithoutAdminRole } from '../../core/session';
import { BrandLogo } from '../../shared/brand-logo';

/**
 * Admin login (issue #165), with the doors `/api/config` says are open.
 *
 * <p><b>Keycloak</b> (`authOidc`, the normal door): one button and a
 * full-page navigation to `/api/auth/oidc/login` — the credentials, and the
 * second factor imposed on administrators, are Keycloak's business.
 * `window.location` rather than the router: the destination is the
 * authorization server, not a route of this application.</p>
 *
 * <p><b>Break-glass account</b> (`authSecours`, closed by default in
 * production): posts the embedded `admin` credentials to Quarkus' form
 * authentication endpoint (`/j_security_check`, url-encoded body). The outcome
 * is read from `/api/auth/me`, never guessed from the login response; a 409
 * means an operator closed that door since the page loaded.</p>
 *
 * <p>A visitor bounced here by a 403 — signed in, but without the `admin`
 * role — is told so, and offered the only move that changes anything:
 * signing out. Offering the sign-in button would loop. The espace animateur
 * never goes through here.</p>
 */
@Component({
  selector: 'app-login-page',
  imports: [
    BrandLogo,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './login-page.html',
  styleUrl: './login-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  /** The card names the deployment, not the software: "<produit> — administration". */
  protected readonly productName = inject(BRANDING).productName;

  private readonly config = inject(APP_CONFIG);
  /** Keycloak sign-in is on: the page offers its button. */
  protected readonly modeOidc = this.config.authOidc;
  /** The break-glass form login is open: the page offers the password card. */
  protected readonly modeSecours = this.config.authSecours;

  private readonly http = inject(HttpClient);
  private readonly adminApi = inject(AdminApi);
  private readonly router = inject(Router);

  protected readonly utilisateur = signal('');
  protected readonly motDePasse = signal('');
  protected readonly enCours = signal(false);
  /** Why the last break-glass attempt failed: wrong credentials, or the door closed (409). */
  protected readonly echec = signal<'identifiants' | 'fermee' | null>(null);

  /** Signed in, without the `admin` role: the card explains instead of offering a login. */
  protected readonly signedInWithoutAccess = signal(false);

  constructor() {
    void this.checkSession();
  }

  /** Who is already signed in, if anyone — the login page itself is public. */
  private async checkSession(): Promise<void> {
    try {
      this.signedInWithoutAccess.set(signedInWithoutAdminRole(await this.adminApi.session()));
    } catch {
      // Anonymous, or the probe is unreachable: the ordinary card is right.
      this.signedInWithoutAccess.set(false);
    }
  }

  /** Leaves the application for the authorization server; nothing to await. */
  protected signInWithKeycloak(): void {
    window.location.assign(this.adminApi.oidcLoginUrl('/'));
  }

  /** Ends the session that opens nothing here — on Keycloak too. */
  protected deconnecter(): Promise<void> {
    return signOut(this.adminApi);
  }

  protected async connecter(event: Event): Promise<void> {
    event.preventDefault();
    if (this.enCours() || !this.utilisateur() || !this.motDePasse()) {
      return;
    }
    this.enCours.set(true);
    this.echec.set(null);
    const corps = new URLSearchParams({
      j_username: this.utilisateur(),
      j_password: this.motDePasse(),
    });
    try {
      await firstValueFrom(
        this.http.post('/j_security_check', corps.toString(), {
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          responseType: 'text',
        }),
      );
      const statut = await this.adminApi.session();
      if (statut.authentifie) {
        await this.router.navigateByUrl('/');
      } else {
        this.echec.set('identifiants');
      }
    } catch (error) {
      this.echec.set(
        error instanceof HttpErrorResponse && error.status === 409 ? 'fermee' : 'identifiants',
      );
    } finally {
      this.enCours.set(false);
    }
  }
}
