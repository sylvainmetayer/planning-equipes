// Where the relecture of the edition has got to, and which days are accepted.
//
// Shared rather than per-page because four screens read the same two facts: the
// Journée panel, the progress banners of the Journée, Calendrier and Solveur
// pages. Re-read on demand — server state, never persisted in the browser.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ValidationsApi } from './api/validations-api';
import { errorMessage } from './error-message';
import {
  DemandeValidationJournee,
  ProgressionValidations,
  ResultatValidationJournee,
  ValidationJournee,
} from './models';

@Injectable({ providedIn: 'root' })
export class ValidationsStore {
  private readonly api = inject(ValidationsApi);

  /** `null` until loaded, or when the request failed (see `error`). */
  private readonly _progression = signal<ProgressionValidations | null>(null);
  readonly progression = this._progression.asReadonly();
  private readonly _validations = signal<ValidationJournee[]>([]);
  readonly validations = this._validations.asReadonly();
  private readonly _loading = signal(false);
  readonly loading = this._loading.asReadonly();
  private readonly _error = signal('');
  readonly error = this._error.asReadonly();

  /** The days accepted as a whole, as a set the day views can ask about. */
  readonly acceptedDays = computed(() => new Set(this.progression()?.joursValides ?? []));

  /**
   * The banner's sentence, empty while there is nothing to say — an edition
   * with no timeslot has no days to read, and a banner reading « 0 sur 0 »
   * would be noise on the very screens somebody opens before the grid exists.
   */
  readonly libelle = computed(() => {
    const progression = this.progression();
    if (!progression || progression.journees === 0) {
      return '';
    }
    return $localize`:@@validations.banniere:${progression.journeesValidees}:validees: journée(s) sur ${progression.journees}:total: relues et acceptées.`;
  });

  /** How far along the reading is, 0 to 100; `null` when there is nothing to show. */
  readonly pourcentage = computed(() => {
    const progression = this.progression();
    if (!progression || progression.journees === 0) {
      return null;
    }
    return Math.round((progression.journeesValidees / progression.journees) * 100);
  });

  async reload(): Promise<void> {
    this._loading.set(true);
    try {
      const [progression, validations] = await Promise.all([
        this.api.progression(),
        this.api.list(),
      ]);
      this._progression.set(progression);
      this._validations.set(validations);
      this._error.set('');
    } catch (error) {
      this._error.set(errorMessage(error));
    } finally {
      this._loading.set(false);
    }
  }

  /** Accepts one day, then re-reads: the banner and the panel must not drift. */
  async accept(demande: DemandeValidationJournee): Promise<ResultatValidationJournee> {
    const resultat = await this.api.accept(demande);
    await this.reload();
    return resultat;
  }

  async withdraw(id: string): Promise<void> {
    await this.api.withdraw(id);
    await this.reload();
  }
}
