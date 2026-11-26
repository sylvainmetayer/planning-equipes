// The state of a day of the planning — à relire, relue, figée (ADR 0121) —
// and what the relecture reads around it: progression, prerequisites and the
// readings a cold start keeps. The `/api/validations` writes are aliases kept
// until 5.0; nothing here calls them.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  DayState,
  DayStateView,
  KeptReading,
  PrerequisJournee,
  ProgressionValidations,
} from '../models';

@Injectable({ providedIn: 'root' })
export class ValidationsApi {
  private readonly api = inject(ApiService);

  /** Every day of the grid and its state. */
  etats(): Promise<DayStateView[]> {
    return this.api.get<DayStateView[]>('/api/etats-journee');
  }

  /** How far the reading has got — what the progress banners show. */
  progression(): Promise<ProgressionValidations> {
    return this.api.get<ProgressionValidations>('/api/validations/progression');
  }

  /** The readings « Recommencer de zéro » would keep — the past and the `JOUR`-locked days. */
  keptByColdStart(): Promise<KeptReading[]> {
    return this.api.get<KeptReading[]>('/api/validations/a-froid');
  }

  /** What to check before accepting a day; never a refusal, only a read-out. */
  prerequis(jour: string): Promise<PrerequisJournee> {
    return this.api.get<PrerequisJournee>(
      `/api/validations/prerequis?jour=${encodeURIComponent(jour)}`,
    );
  }

  /** Moves one day; only `FIGEE` lays the day lock down. */
  setEtat(jour: string, etat: DayState, commentaire: string | null): Promise<DayStateView> {
    return this.api.put<DayStateView>(`/api/etats-journee/${encodeURIComponent(jour)}`, {
      etat,
      commentaire,
    });
  }
}
