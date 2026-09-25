// The wall display of the control room: the admin side (the links, managed
// from Paramètres) and the public read a link opens, whose token is the only
// credential.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  AffichageMuralLink,
  AffichageMuralLinkRequest,
  AffichageMuralView,
  CreatedAffichageMuralLink,
  QrCodeView,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AffichageMuralApi {
  private readonly api = inject(ApiService);

  list(): Promise<AffichageMuralLink[]> {
    return this.api.get<AffichageMuralLink[]>('/api/affichage-mural');
  }

  create(request: AffichageMuralLinkRequest): Promise<CreatedAffichageMuralLink> {
    return this.api.post<CreatedAffichageMuralLink>('/api/affichage-mural', request);
  }

  revoke(id: number): Promise<void> {
    return this.api.delete(`/api/affichage-mural/${id}`);
  }

  qrCode(link: string): Promise<QrCodeView> {
    return this.api.post<QrCodeView>('/api/affichage-mural/qr-code', { link });
  }

  /**
   * The wall view a token opens. Rejects with the untouched HTTP error, so the
   * screen tells a dead link (404) from a network cut it rides out.
   */
  view(jeton: string): Promise<AffichageMuralView> {
    return this.api.getPreservingHttpError<AffichageMuralView>(
      `/api/mural/${encodeURIComponent(jeton)}`,
    );
  }
}
