// « Relu et accepté » on a day of the planning: the review state, which the
// lock mechanism never carried. Reading, accepting, withdrawing — and the
// prerequisites the panel reads out before somebody accepts.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  DemandeValidationJournee,
  PrerequisJournee,
  ProgressionValidations,
  ResultatValidationJournee,
  ValidationJournee,
} from '../models';

@Injectable({ providedIn: 'root' })
export class ValidationsApi {
  private readonly api = inject(ApiService);

  list(): Promise<ValidationJournee[]> {
    return this.api.get<ValidationJournee[]>('/api/validations');
  }

  /** How far the reading has got — what the progress banners show. */
  progression(): Promise<ProgressionValidations> {
    return this.api.get<ProgressionValidations>('/api/validations/progression');
  }

  /** What to check before accepting a day; never a refusal, only a read-out. */
  prerequis(jour: string, standId: string | null = null): Promise<PrerequisJournee> {
    const stand = standId ? `&stand=${encodeURIComponent(standId)}` : '';
    return this.api.get<PrerequisJournee>(
      `/api/validations/prerequis?jour=${encodeURIComponent(jour)}${stand}`,
    );
  }

  accept(demande: DemandeValidationJournee): Promise<ResultatValidationJournee> {
    return this.api.post<ResultatValidationJournee>('/api/validations', demande);
  }

  withdraw(id: string): Promise<void> {
    return this.api.delete(`/api/validations/${encodeURIComponent(id)}`);
  }
}
