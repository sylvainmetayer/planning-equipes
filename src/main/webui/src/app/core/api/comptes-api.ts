// The `/api/comptes/*` endpoints: named accounts and the rights delegated to
// them (ADR 0054). Keycloak owns the credentials — nothing here sets a password
// or a second factor. An account is deactivated, never deleted; a right is
// withdrawn, never deleted.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { Compte, NouveauCompte, NouvelleHabilitation } from '../models';

@Injectable({ providedIn: 'root' })
export class ComptesApi {
  private readonly api = inject(ApiService);

  /** Every account, deactivated ones included, with their whole history of rights. */
  list(): Promise<Compte[]> {
    return this.api.get<Compte[]>('/api/comptes');
  }

  /** An account ahead of the person's first sign-in: 409 when the address already has one. */
  create(nouveau: NouveauCompte): Promise<Compte> {
    return this.api.post<Compte>('/api/comptes', nouveau);
  }

  /**
   * Makes someone an administrator, in the realm where the login flow asks
   * their TOTP: the account created and invited when missing. 409 without
   * Keycloak provisioning.
   */
  inviteAdministrator(nouveau: NouveauCompte): Promise<Compte> {
    return this.api.post<Compte>('/api/comptes/administrateurs', nouveau);
  }

  /** Strips every role of the application, realm ones included. */
  deactivate(compteId: string): Promise<Compte> {
    return this.api.post<Compte>(
      `/api/comptes/${encodeURIComponent(compteId)}/desactivation`,
      null,
    );
  }

  reactivate(compteId: string): Promise<Compte> {
    return this.api.post<Compte>(`/api/comptes/${encodeURIComponent(compteId)}/reactivation`, null);
  }

  grant(compteId: string, habilitation: NouvelleHabilitation): Promise<Compte> {
    return this.api.post<Compte>(
      `/api/comptes/${encodeURIComponent(compteId)}/habilitations`,
      habilitation,
    );
  }

  /** The right stays in the list, dated by `retireeLe`, and opens nothing any more. */
  withdraw(compteId: string, habilitationId: string): Promise<Compte> {
    return this.api.delete<Compte>(
      `/api/comptes/${encodeURIComponent(compteId)}/habilitations/${encodeURIComponent(habilitationId)}`,
    );
  }
}
