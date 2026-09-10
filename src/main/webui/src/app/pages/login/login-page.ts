import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { BRANDING } from '../../core/branding';
import { BrandLogo } from '../../shared/brand-logo';

/**
 * Admin login (issue #165): posts the credentials to Quarkus' form
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
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  /** The card names the deployment, not the software: "<produit> — administration". */
  protected readonly productName = inject(BRANDING).productName;

  private readonly http = inject(HttpClient);
  private readonly adminApi = inject(AdminApi);
  private readonly router = inject(Router);

  protected readonly utilisateur = signal('');
  protected readonly motDePasse = signal('');
  protected readonly enCours = signal(false);
  protected readonly echec = signal(false);

  protected async connecter(event: Event): Promise<void> {
    event.preventDefault();
    if (this.enCours() || !this.utilisateur() || !this.motDePasse()) {
      return;
    }
    this.enCours.set(true);
    this.echec.set(false);
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
        this.echec.set(true);
      }
    } catch {
      this.echec.set(true);
    } finally {
      this.enCours.set(false);
    }
  }
}
