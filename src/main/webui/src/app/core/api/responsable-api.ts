// `/api/responsable/*`: the read-only views of a responsable de stand (issue
// #295). The edition travels in the path — a scope the server checks against
// the caller's rights —, never in the `X-Edition-Id` header the administration
// sets.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { EditionResponsable, ResponsableView } from '../models';

@Injectable({ providedIn: 'root' })
export class ResponsableApi {
  private readonly api = inject(ApiService);

  /**
   * The editions where the caller is responsable de stand today, default one
   * first. Rejects with the untouched `HttpErrorResponse`: the page tells a
   * 401 (sign in) from a 403 (no right, or no more).
   */
  editions(): Promise<EditionResponsable[]> {
    return this.api.getPreservingHttpError<EditionResponsable[]>('/api/responsable/editions');
  }

  /** The published plan of the caller's stands on one edition, or of `standId` alone. */
  view(editionId: string, standId: string | null = null): Promise<ResponsableView> {
    const stand = standId ? `?stand=${encodeURIComponent(standId)}` : '';
    return this.api.getPreservingHttpError<ResponsableView>(
      `/api/responsable/editions/${encodeURIComponent(editionId)}${stand}`,
    );
  }
}
