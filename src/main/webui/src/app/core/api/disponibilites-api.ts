// The `/api/disponibilites/*` endpoints of the admin side of the collecte.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ConfigurationCollecte, DeclarationAdminView } from '../models';

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
    prevenirAnimateurs: boolean;
  }): Promise<ConfigurationCollecte> {
    return this.api.put<ConfigurationCollecte>('/api/disponibilites/configuration', configuration);
  }

  /** `application` or `refus` of one declaration, with the administrator's word. */
  decide(
    declarationId: number | string,
    action: string,
    commentaire: string | null,
  ): Promise<DeclarationAdminView> {
    return this.api.post<DeclarationAdminView>(`/api/disponibilites/${declarationId}/${action}`, {
      commentaire,
    });
  }
}
