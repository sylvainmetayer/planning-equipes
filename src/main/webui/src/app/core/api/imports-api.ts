// The referential CSV imports — typologies, emplacements, stands — which all
// answer the same three calls on their own resource. See planning-api.ts for
// why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { CibleImportReferentiel, ImportGrilleDemande, RapportImportReferentiel } from '../models';

@Injectable({ providedIn: 'root' })
export class ImportsApi {
  private readonly api = inject(ApiService);

  /** What the file would do, line by line, without writing any of it. */
  analyse(
    cible: CibleImportReferentiel,
    demande: ImportGrilleDemande,
  ): Promise<RapportImportReferentiel> {
    switch (cible) {
      case 'TYPOLOGIES':
        return this.api.post<RapportImportReferentiel>(
          '/api/typologies/import-csv/analyse',
          demande,
        );
      case 'EMPLACEMENTS':
        return this.api.post<RapportImportReferentiel>(
          '/api/emplacements/import-csv/analyse',
          demande,
        );
      default:
        return this.api.post<RapportImportReferentiel>('/api/stands/import-csv/analyse', demande);
    }
  }

  /** Writes the same file the preview was computed from; the server reads it again. */
  importer(
    cible: CibleImportReferentiel,
    demande: ImportGrilleDemande,
  ): Promise<RapportImportReferentiel> {
    switch (cible) {
      case 'TYPOLOGIES':
        return this.api.post<RapportImportReferentiel>('/api/typologies/import-csv', demande);
      case 'EMPLACEMENTS':
        return this.api.post<RapportImportReferentiel>('/api/emplacements/import-csv', demande);
      default:
        return this.api.post<RapportImportReferentiel>('/api/stands/import-csv', demande);
    }
  }

  telechargerExemple(cible: CibleImportReferentiel): Promise<string> {
    switch (cible) {
      case 'TYPOLOGIES':
        return this.api.downloadGet(
          '/api/typologies/import-csv/exemple',
          'exemple-typologies.csv',
          'text/csv',
        );
      case 'EMPLACEMENTS':
        return this.api.downloadGet(
          '/api/emplacements/import-csv/exemple',
          'exemple-emplacements.csv',
          'text/csv',
        );
      default:
        return this.api.downloadGet(
          '/api/stands/import-csv/exemple',
          'exemple-stands.csv',
          'text/csv',
        );
    }
  }
}
