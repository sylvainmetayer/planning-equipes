// The `/api/disponibilites/*` endpoints of the admin side of the collecte.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ConfigurationCollecte,
  DeclarationAdminView,
  DestinatairesInvitation,
  InvitationReport,
  TeammateRequestView,
  ValidatedCarpool,
} from '../models';

@Injectable({ providedIn: 'root' })
export class DisponibilitesApi {
  private readonly api = inject(ApiService);

  declarations(): Promise<DeclarationAdminView[]> {
    return this.api.get<DeclarationAdminView[]>('/api/disponibilites');
  }

  configuration(): Promise<ConfigurationCollecte> {
    return this.api.get<ConfigurationCollecte>('/api/disponibilites/configuration');
  }

  saveConfiguration(configuration: {
    collecteOuverte: boolean;
    debut: string | null;
    fin: string | null;
    relanceAutomatique: boolean;
    prevenirAnimateurs: boolean;
  }): Promise<ConfigurationCollecte> {
    return this.api.put<ConfigurationCollecte>('/api/disponibilites/configuration', configuration);
  }

  /** Sends the espace links again while the window is open, without changing the window. */
  sendLinks(destinataires: DestinatairesInvitation): Promise<InvitationReport> {
    return this.api.post<InvitationReport>('/api/disponibilites/invitations', { destinataires });
  }

  /** `application` or `refus` of one declaration, with the administrator's word; the type is the route's. */
  decide(
    declarationId: number | string,
    action: 'application' | 'refus',
    commentaire: string | null,
  ): Promise<DeclarationAdminView> {
    return this.api.post<DeclarationAdminView>(`/api/disponibilites/${declarationId}/${action}`, {
      commentaire,
    });
  }

  /** The covoiturage requests sent from the espaces (« Je viens avec… »). */
  carpools(): Promise<TeammateRequestView[]> {
    return this.api.get<TeammateRequestView[]>('/api/disponibilites/coequipiers');
  }

  /** Creates the `ARRIVEE_GROUPEE` exception of one pending covoiturage. */
  validateCarpool(id: string): Promise<ValidatedCarpool> {
    return this.api.post<ValidatedCarpool>(`/api/disponibilites/coequipiers/${id}/validation`, {});
  }

  /** Sets one pending covoiturage aside: the demand is deleted, nobody is mailed. */
  async setCarpoolAside(id: string): Promise<void> {
    await this.api.post<void>(`/api/disponibilites/coequipiers/${id}/ecart`, {});
  }

  /** Cancels the validated grouped arrival of one covoiturage: its exception and demands are deleted. */
  async cancelCarpool(id: string): Promise<void> {
    await this.api.post<void>(`/api/disponibilites/coequipiers/${id}/annulation`, {});
  }
}
