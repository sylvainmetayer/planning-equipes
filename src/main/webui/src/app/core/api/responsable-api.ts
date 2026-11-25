// The responsable de stand's two reads, and the setting the administration chooses
// what they read with (`/api/reglage-responsables`).

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ReglagesResponsables, ResponsableView } from '../models';

@Injectable({ providedIn: 'root' })
export class ResponsableApi {
  private readonly api = inject(ApiService);

  /** The caller's stands on the active edition, with their published shifts. */
  stands(): Promise<ResponsableView> {
    return this.api.get<ResponsableView>('/api/responsable/stands');
  }

  /** Saves the sheet of one stand: names, or none, exactly as the screen shows them. */
  affichette(standId: string): Promise<string> {
    return this.api.downloadGet(
      `/api/responsable/stands/${encodeURIComponent(standId)}/affichette.pdf`,
      'affichette-stand.pdf',
      'application/pdf',
    );
  }

  reglages(): Promise<ReglagesResponsables> {
    return this.api.get<ReglagesResponsables>('/api/reglage-responsables');
  }

  saveReglages(reglages: ReglagesResponsables): Promise<ReglagesResponsables> {
    return this.api.put<ReglagesResponsables>('/api/reglage-responsables', reglages);
  }
}
