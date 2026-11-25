// The `/api/comptes/*` endpoints: named accounts and the rights delegated to
// them. An account is deactivated, never deleted; a right is withdrawn, never
// deleted. A password is only ever chosen by its owner, through a mailed single-use link.

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

  /** Mails the link to choose a first password (72 h, single use): the admin never sees it. */
  invite(compteId: string): Promise<void> {
    return this.api.post<void>(`/api/comptes/${encodeURIComponent(compteId)}/invitation`, null);
  }

  /** Mails the link to choose a new password (1 h, single use). */
  resetPassword(compteId: string): Promise<void> {
    return this.api.post<void>(
      `/api/comptes/${encodeURIComponent(compteId)}/reinitialisation`,
      null,
    );
  }

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
    return this.api.deleteReturning<Compte>(
      `/api/comptes/${encodeURIComponent(compteId)}/habilitations/${encodeURIComponent(habilitationId)}`,
    );
  }
}
