// The referential CSV imports — typologies, emplacements, stands, the timeslot
// grid and the day templates — which all answer the same three calls on their
// own resource. See planning-api.ts for why the paths live here and not in the
// pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ReferentielImportTarget, ImportGrilleDemande, RapportImportReferentiel } from '../models';

@Injectable({ providedIn: 'root' })
export class ImportsApi {
  private readonly api = inject(ApiService);

  /** What the file would do, line by line, without writing any of it. */
  analyse(
    cible: ReferentielImportTarget,
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
      case 'CRENEAUX':
        return this.api.post<RapportImportReferentiel>('/api/creneaux/import-csv/analyse', demande);
      case 'JOURNEES_TYPES':
        return this.api.post<RapportImportReferentiel>(
          '/api/journees-types/import-csv/analyse',
          demande,
        );
      default:
        return this.api.post<RapportImportReferentiel>('/api/stands/import-csv/analyse', demande);
    }
  }

  /** Writes the same file the preview was computed from; the server reads it again. */
  importer(
    cible: ReferentielImportTarget,
    demande: ImportGrilleDemande,
  ): Promise<RapportImportReferentiel> {
    switch (cible) {
      case 'TYPOLOGIES':
        return this.api.post<RapportImportReferentiel>('/api/typologies/import-csv', demande);
      case 'EMPLACEMENTS':
        return this.api.post<RapportImportReferentiel>('/api/emplacements/import-csv', demande);
      case 'CRENEAUX':
        return this.api.post<RapportImportReferentiel>('/api/creneaux/import-csv', demande);
      case 'JOURNEES_TYPES':
        return this.api.post<RapportImportReferentiel>('/api/journees-types/import-csv', demande);
      default:
        return this.api.post<RapportImportReferentiel>('/api/stands/import-csv', demande);
    }
  }

  telechargerExemple(cible: ReferentielImportTarget): Promise<string> {
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
      case 'CRENEAUX':
        return this.api.downloadGet(
          '/api/creneaux/import-csv/exemple',
          'exemple-creneaux.csv',
          'text/csv',
        );
      case 'JOURNEES_TYPES':
        return this.api.downloadGet(
          '/api/journees-types/import-csv/exemple',
          'exemple-journees-types.csv',
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

/** Which referentials the export writes, and what the archive is called. */
export const CIBLES_EXPORT_CSV = [
  'TYPOLOGIES',
  'EMPLACEMENTS',
  'STANDS',
  'CRENEAUX',
  'JOURNEES_TYPES',
  'ANIMATEURS',
] as const;
