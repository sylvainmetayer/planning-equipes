// The administrator's plumbing: backups, the SQL dump, the mail checks of the
// Débogage page, the legal notice, the scheduled notifications, the session.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
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
   * Who the session belongs to, read after a login rather than guessed from
   * the login response — with the raw HTTP failure kept, since a 401 here is
   * the expected answer to a wrong password, not a session to redirect.
   */
  session(): Promise<StatutSession> {
    return this.api.getPreservingHttpError<StatutSession>('/api/auth/me');
  }

  logout(): Promise<unknown> {
    return this.api.post('/api/auth/logout', null);
  }
}
