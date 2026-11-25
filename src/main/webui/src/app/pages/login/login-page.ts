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
import { errorMessage } from '../../core/error-message';
import { SignInChallenge } from '../../core/models';
import { signedInWithoutAccess } from '../../core/session';
import { BrandLogo } from '../../shared/brand-logo';
import { StatusMessage } from '../../shared/status-message';

const CHEMIN_SECOURS = '/j_security_check';

/**
 * Admin login (issue #165), with the doors the server says are open.
 *
 * <p>An OIDC provider, when the operator plugged one in (ADR 0093): one button
 * and a full-page navigation to `/api/auth/oidc/login` — the credentials are
 * the provider's business. A visitor signed in but without the `admin` right
 * (the interceptor bounces a 403 here) is told so and offered the one move that
 * changes anything: signing out.</p>
 *
 * <p>One password form for the two other doors (ADR 0094). An **address**
 * (with an arobase) is a local account: the password buys a one-time challenge
 * and, unless the operator turned it off, a code mailed to the account — the
 * second step — and the form mechanism then exchanges the two for the session
 * cookie. Anything else is the configuration's break-glass `admin`, posted
 * straight to `/j_security_check`, closed by default behind a provider. The
 * outcome of either post is read from `/api/auth/me`, never guessed from the
 * response itself. « Mot de passe oublié » mails a single-use link; its answer
 * is the same for every address. The espace animateur never goes through here.</p>
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
    StatusMessage,
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
  /** The `admin` account of the configuration may open a session: said beside a provider only. */
  protected readonly modeSecours = this.config.authSecours;

  private readonly http = inject(HttpClient);
  private readonly adminApi = inject(AdminApi);
  private readonly router = inject(Router);

  protected readonly utilisateur = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  /** Why the last attempt failed: the door closed (409), or the server's own sentence. */
  protected readonly echec = signal<{ fermee: boolean; message: string | null } | null>(null);
  /** Signed in at the provider, but this application gave the account no `admin` right. */
  protected readonly noRights = signal(false);

  /** The password was right and a code is on its way: the challenge waits for it. */
  protected readonly defi = signal<SignInChallenge | null>(null);
  protected readonly code = signal('');

  /** « Mot de passe oublié » panel, and whether its request left. */
  protected readonly oubli = signal(false);
  protected readonly oubliEnvoye = signal(false);

  /** Read when shown, never at import: `$localize` needs the translations `main.ts` loads first. */
  protected get identifiantsIncorrects(): string {
    return $localize`:@@login.echec:Identifiants incorrects.`;
  }

  /** What a failed attempt says: the server's sentence, else the generic one of the step. */
  protected messageEchec(parCode: boolean): string {
    return (
      this.echec()?.message ??
      (parCode
        ? $localize`:@@login.code.echec:Code incorrect ou expiré : recommencez.`
        : this.identifiantsIncorrects)
    );
  }

  ngOnInit(): void {
    void this.adminApi
      .session()
      .then((statut) => this.noRights.set(signedInWithoutAccess(statut)))
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
    if (this.busy() || !this.utilisateur() || !this.password()) {
      return;
    }
    this.busy.set(true);
    this.echec.set(null);
    try {
      if (this.utilisateur().includes('@')) {
        const defi = await this.adminApi.localSignIn(this.utilisateur().trim(), this.password());
        if (defi.codeRequired) {
          this.defi.set(defi);
          this.password.set('');
          return;
        }
        await this.openSession(
          this.adminApi.localFormPath,
          this.utilisateur().trim(),
          defi.challenge,
        );
      } else {
        await this.openSession(CHEMIN_SECOURS, this.utilisateur(), this.password());
      }
    } catch (error) {
      this.echec.set({ fermee: false, message: errorMessage(error) });
    } finally {
      this.busy.set(false);
    }
  }

  /** Second step: the code from the mail completes the challenge the password bought. */
  protected async submitCode(event: Event): Promise<void> {
    event.preventDefault();
    const defi = this.defi();
    if (this.busy() || !defi || !this.code().trim()) {
      return;
    }
    this.busy.set(true);
    this.echec.set(null);
    try {
      await this.openSession(
        this.adminApi.localFormPath,
        this.utilisateur().trim(),
        `${defi.challenge}:${this.code().trim()}`,
      );
    } catch (error) {
      this.echec.set({ fermee: false, message: errorMessage(error) });
    } finally {
      this.busy.set(false);
    }
  }

  /** Abandons the code step: the challenge is single-use, a new password step issues another. */
  protected recommencer(): void {
    this.defi.set(null);
    this.code.set('');
    this.echec.set(null);
  }

  protected async requestLink(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy() || !this.utilisateur().includes('@')) {
      return;
    }
    this.busy.set(true);
    try {
      await this.adminApi.forgotPassword(this.utilisateur().trim());
    } catch {
      // The answer is meant to be the same whatever happened: nothing to tell.
    } finally {
      this.oubliEnvoye.set(true);
      this.busy.set(false);
    }
  }

  /**
   * Posts the form and reads the outcome from the session probe: a refusal and a
   * success are both redirects, only the cookie tells them apart.
   */
  private async openSession(chemin: string, utilisateur: string, secret: string): Promise<void> {
    const corps = new URLSearchParams({ j_username: utilisateur, j_password: secret });
    try {
      await firstValueFrom(
        this.http.post(chemin, corps.toString(), {
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          responseType: 'text',
        }),
      );
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409) {
        this.echec.set({ fermee: true, message: null });
        return;
      }
      if (error instanceof HttpErrorResponse && error.status === 429) {
        throw new Error(
          $localize`:@@login.verrouille:Trop de tentatives de connexion : réessayez dans quelques minutes.`,
          { cause: error },
        );
      }
      this.echec.set({ fermee: false, message: null });
      return;
    }
    const statut = await this.adminApi.session();
    if (statut.authentifie) {
      await this.router.navigateByUrl('/');
    } else {
      this.echec.set({ fermee: false, message: null });
    }
  }
}
