// The administrator's plumbing: backups, the mail checks and the
// version history of the Débogage page, the legal notice, the scheduled notifications, the
// organisation's contact, the session.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ApplicationVersion,
  ContactOrganisation,
  EtatSauvegarde,
  MentionsLegales,
  ParametresNotifications,
  Deconnexion,
  SignInChallenge,
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

  /** The application versions that opened this database, the latest first. */
  applicationVersions(): Promise<ApplicationVersion[]> {
    return this.api.get<ApplicationVersion[]>('/api/debug/versions');
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

  /** Who the animateurs of this edition call: shown in their espace. */
  organisationContact(): Promise<ContactOrganisation> {
    return this.api.get<ContactOrganisation>('/api/parametres-contact');
  }

  /** The contact as the server kept it: trimmed, a blank half stored as `null`. */
  saveOrganisationContact(contact: ContactOrganisation): Promise<ContactOrganisation> {
    return this.api.put<ContactOrganisation>('/api/parametres-contact', contact);
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

  /** The route that hands the browser over to the identity provider; a navigation, not a call. */
  readonly providerSignInUrl = '/api/auth/oidc/login';

  /**
   * The password step of a local sign-in. Rejects with the server's sentence
   * (« Adresse ou mot de passe incorrect », or the wait of a lockout): the
   * answer is the same whatever the cause.
   */
  localSignIn(email: string, password: string): Promise<SignInChallenge> {
    return this.api.post<SignInChallenge>('/api/auth/connexion', { email, password });
  }

  /** « Mot de passe oublié » — answers the same whether the address has an account or not. */
  forgotPassword(email: string): Promise<void> {
    return this.api.post<void>('/api/auth/mot-de-passe/oubli', { email });
  }

  /** Chooses a password with the single-use link of an invitation or a reset. */
  choosePassword(token: string, password: string): Promise<void> {
    return this.api.post<void>('/api/auth/mot-de-passe', { token, password });
  }

  /** Where the form post of a local account goes (the mechanism of the admin's, on a path of its own). */
  readonly localFormPath = '/api/auth/connexion/j_security_check';

  /** Drops the local cookie; the answer names the provider's logout route, if any. */
  logout(): Promise<Deconnexion> {
    return this.api.post<Deconnexion>('/api/auth/logout', null);
  }
}
