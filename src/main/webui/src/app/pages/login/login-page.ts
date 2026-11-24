import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
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
import { signedInWithoutAdminRole } from '../../core/session';
import { BrandLogo } from '../../shared/brand-logo';

/**
 * Admin login (issue #165), with the doors `/api/config` says are open.
 *
 * <p>An OIDC provider, when the operator plugged one in (ADR 0093): one button
 * and a full-page navigation to `/api/auth/oidc/login` — the credentials are
 * the provider's business. The `admin` password form is then the break-glass
 * door, closed by default and shown only when the operator reopened it, and
 * also the plain login without a provider. A visitor signed in but without the
 * `admin` right (the interceptor bounces a 403 here) is told so and offered the
 * one move that changes anything: signing out.</p>
 *
 * <p>The password form: posts the credentials to Quarkus' form
 * authentication endpoint (`/j_security_check`, url-encoded body). A success
 * sets the session cookie and redirects to the session probe (the browser's
 * fetch follows it); a failure answers 401 or lands on the same probe with
 * `authentifie: false` — so the outcome is always read from `/api/auth/me`,
 * never guessed from the login response itself. The espace animateur never
 * goes through here.
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
export class LoginPage implements OnInit {
  /** The card names the deployment, not the software: "<produit> — administration". */
  protected readonly productName = inject(BRANDING).productName;

  private readonly config = inject(APP_CONFIG);
  /** An OIDC provider is plugged in: the page offers its button. */
  protected readonly modeOidc = this.config.authOidc;
  /** The `admin` password form is open: the plain login without a provider, the break-glass door beside one. */
  protected readonly modeSecours = this.config.authSecours;

  private readonly http = inject(HttpClient);
  private readonly adminApi = inject(AdminApi);
  private readonly router = inject(Router);

  protected readonly utilisateur = signal('');
  protected readonly motDePasse = signal('');
  protected readonly enCours = signal(false);
  /** Why the last password attempt failed: wrong credentials, or the door closed (409). */
  protected readonly echec = signal<'identifiants' | 'fermee' | null>(null);
  /** Signed in at the provider, but this application gave the account no `admin` right. */
  protected readonly noRights = signal(false);

  ngOnInit(): void {
    void this.adminApi
      .session()
      .then((statut) => this.noRights.set(signedInWithoutAdminRole(statut)))
      // Anonymous, or the probe is unreachable: the ordinary card is right.
      .catch(() => this.noRights.set(false));
  }

  /** Leaves the application for the authorization server; nothing to await. */
  protected signInWithProvider(): void {
    window.location.assign(this.adminApi.providerSignInUrl);
  }

  /** Ends the session that opens nothing here — at the provider too. */
  protected async deconnecter(): Promise<void> {
    let destination = '/login';
    try {
      destination = (await this.adminApi.logout()).urlDeconnexion ?? destination;
    } finally {
      window.location.assign(destination);
    }
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
