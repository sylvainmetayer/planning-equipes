// The `/api/animateurs/*` endpoints the pages call beyond the CRUD that
// `reference-crud.service.ts` already owns — see planning-api.ts for why the
// paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ConfirmationView, ImportCsvDemande, ImportCsvRapport } from '../models';

@Injectable({ providedIn: 'root' })
export class AnimateursApi {
  private readonly api = inject(ApiService);

  /** A new espace link for one animateur; the old one stops working at once. */
  regenerateToken(animateurId: string): Promise<void> {
    return this.api.post<void>(`/api/animateurs/${encodeURIComponent(animateurId)}/token`, null);
  }

  /** Who confirmed their planning, and when. */
  confirmations(): Promise<ConfirmationView[]> {
    return this.api.get<ConfirmationView[]>('/api/animateurs/confirmations');
  }

  downloadCsvExample(): Promise<string> {
    return this.api.downloadGet(
      '/api/animateurs/import-csv/exemple',
      'exemple-animateurs.csv',
      'text/csv',
    );
  }

  /** What the import would do, without writing anything. */
  analyseCsvImport(demande: ImportCsvDemande): Promise<ImportCsvRapport> {
    return this.api.post<ImportCsvRapport>('/api/animateurs/import-csv/analyse', demande);
  }

  applyCsvImport(demande: ImportCsvDemande): Promise<ImportCsvRapport> {
    return this.api.post<ImportCsvRapport>('/api/animateurs/import-csv', demande);
  }
}
