import { Injectable, computed, inject, signal } from '@angular/core';
import { AdminApi } from './api/admin-api';
import { ROLE_ADMIN } from './session';
import { StatutSession } from './models';

/**
 * Who the signed-in person is and with which roles, read once by the shell.
 * It only decides what the interface shows: an organiser is not offered the
 * instance settings, and the backend refuses them anyway (403).
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly api = inject(AdminApi);

  private readonly _statut = signal<StatutSession | null>(null);
  readonly statut = this._statut.asReadonly();

  /**
   * Unknown until read: shown as admin meanwhile, so a slow answer never hides
   * a screen from an administrator (the server decides either way).
   */
  readonly isAdmin = computed(() => {
    const statut = this._statut();
    return !statut || statut.roles.includes(ROLE_ADMIN);
  });

  async load(): Promise<void> {
    try {
      this._statut.set(await this.api.session());
    } catch {
      // Left unknown: the login redirect owns an expired session.
    }
  }
}
