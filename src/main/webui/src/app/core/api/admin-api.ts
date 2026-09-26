// The administrator's plumbing: backups, the SQL dump, the mail checks of the
// Débogage screen, the legal notice, the scheduled notifications, the session.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  Deconnexion,
  EtatSauvegarde,
  ImportSummary,
  MentionsLegales,
  ParametresNotifications,
  StatutSession,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly api = inject(ApiService);

  /* ------------------------------- backups -------------------------------- */

  backups(): Promise<EtatSauvegarde> {
    return this.api.get<EtatSauvegarde>('/api/backups');
  }

  setBackupsActive(active: boolean): Promise<EtatSauvegarde> {
    return this.api.put<EtatSauvegarde>('/api/backups/active', { active });
  }

  /* -------------------------------- database ------------------------------ */

  /** The whole database as SQL; `filename` names the file the browser saves. */
  exportDatabase(filename: string): Promise<string> {
    return this.api.downloadGet('/api/database/export', filename, 'application/sql');
  }

  /** Replays a dump over the whole database, every edition included. */
  importDatabase(sql: string): Promise<ImportSummary> {
    return this.api.postRaw<ImportSummary>('/api/database/import', sql, 'application/sql');
  }

  /* --------------------------------- mail --------------------------------- */

  /** The admin address the notifications go to — `null` when MAIL_ADMIN is not set. */
  mailConfig(): Promise<{ adminEmail: string | null }> {
    return this.api.get<{ adminEmail: string | null }>('/api/debug/mail-config');
  }

  /** Really sends a mail to the admin address, to check the SMTP plumbing. */
  sendTestMail(): Promise<{ adminEmail: string }> {
    return this.api.post<{ adminEmail: string }>('/api/debug/test-mail', {});
  }

  /** Always fails server-side, on purpose: exercises the error reporting. */
  triggerTestException(): Promise<unknown> {
    return this.api.post('/api/debug/test-exception', {});
  }

  /* ---------------------------- legal notice ------------------------------ */

  legalNotice(): Promise<MentionsLegales> {
    return this.api.get<MentionsLegales>('/api/mentions-legales');
  }

  /* ------------------------- scheduled notifications ---------------------- */

  notificationSettings(): Promise<ParametresNotifications> {
    return this.api.get<ParametresNotifications>('/api/parametres-notifications');
  }

  saveNotificationSettings(parametres: ParametresNotifications): Promise<ParametresNotifications> {
    return this.api.put<ParametresNotifications>('/api/parametres-notifications', parametres);
  }

  /* -------------------------------- session ------------------------------- */

  /**
   * Where to send the browser to sign in with Keycloak.
   *
   * <p>A URL and not a call, which is the whole point: the backend is a
   * confidential client, it runs the code flow itself and keeps the tokens
   * server-side. The frontend hands the visitor to that door — a full-page
   * navigation, never an XHR — and learns afterwards, through `/api/auth/me`,
   * who came back. No OIDC library, no token in the browser.</p>
   *
   * @param retour path of this application to land on once signed in. It is
   *   re-checked server-side (an open redirect is refused and lands on `/`):
   *   a login route is exactly the one an open redirect would be worth
   *   attacking, so neither side takes the other's word for it.
   */
  oidcLoginUrl(retour: string): string {
    return `/api/auth/oidc/login?redirect=${encodeURIComponent(retour)}`;
  }

  /**
   * Who the session belongs to, read after a login rather than guessed from
   * the login response — with the raw HTTP failure kept, since a 401 here is
   * the expected answer to a wrong password, not a session to redirect.
   */
  session(): Promise<StatutSession> {
    return this.api.getPreservingHttpError<StatutSession>('/api/auth/me');
  }

  /**
   * Drops the local session and says whether an identity provider session
   * remains to be ended. Under Keycloak it does: dropping our own cookie alone
   * would leave the IdP ready to sign the visitor straight back in.
   */
  logout(): Promise<Deconnexion> {
    return this.api.post<Deconnexion>('/api/auth/logout', null);
  }
}
