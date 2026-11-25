import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AdminApi } from './api/admin-api';
import { StatutSession } from './models';
import { ROLE_ADMIN, lectureSeule, signedInWithoutAccess } from './session';

/**
 * Who the signed-in person is and with which roles, read once by the shell.
 * It only decides what the interface shows: an organiser is not offered the
 * instance settings, and the backend refuses them anyway (403).
 *
 * Nothing is assumed before the answer: {@link isAdmin} is false until the
 * session is read, and what must not be asked of the server on behalf of an
 * organiser awaits {@link ready} first.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);

  private readonly _statut = signal<StatutSession | null>(null);
  readonly statut = this._statut.asReadonly();

  private readonly _known = signal(false);
  /** Whether the session was read (successfully or not) at least once. */
  readonly known = this._known.asReadonly();

  readonly isAdmin = computed(() => this._statut()?.roles.includes(ROLE_ADMIN) ?? false);

  /** A lecteur only: the interface hides what changes something (the server refuses it anyway). */
  readonly lectureSeule = computed(() => lectureSeule(this._statut()));

  private reading: Promise<void> | null = null;

  /** Resolves once the session was read; the read is made once, then shared. */
  ready(): Promise<void> {
    this.reading ??= this.read();
    return this.reading;
  }

  /** Reads the session again (a right of one's own was withdrawn, say). */
  refresh(): Promise<void> {
    this.reading = this.read();
    return this.reading;
  }

  private async read(): Promise<void> {
    try {
      const statut = await this.api.session();
      this._statut.set(statut);
      // Signed in at the provider, but no right opens anything here: the login
      // page explains it, instead of every panel failing one by one.
      if (signedInWithoutAccess(statut)) {
        void this.router.navigateByUrl('/login');
      }
    } catch {
      // Left unknown: the 401 redirect owns an expired session.
    } finally {
      this._known.set(true);
    }
  }
}
