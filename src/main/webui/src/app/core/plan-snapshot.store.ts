// Plan snapshots (issue #138): the saved plans of the current edition, shared
// between the management screen and the mismatch banner's "restore" action.

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService, toError } from './api.service';
import { PlanSnapshot, RestaurationSnapshot } from './models';

/** Raised when a restore is refused because the referential has moved on. */
export class ReferencesManquantesError extends Error {
  constructor(
    message: string,
    readonly references: string[]
  ) {
    super(message);
    this.name = 'ReferencesManquantesError';
  }
}

@Injectable({ providedIn: 'root' })
export class PlanSnapshotStore {
  readonly snapshots = signal<PlanSnapshot[]>([]);
  readonly chargement = signal(false);

  /** Most recent snapshot per groupe de créneaux, for the mismatch banner. */
  readonly parGroupe = computed(() => {
    const parGroupe = new Map<string, PlanSnapshot>();
    // `snapshots` comes back newest first, so the first one seen for a group wins.
    for (const snapshot of this.snapshots()) {
      if (snapshot.groupeCreneauId && !parGroupe.has(snapshot.groupeCreneauId)) {
        parGroupe.set(snapshot.groupeCreneauId, snapshot);
      }
    }
    return parGroupe;
  });

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    this.chargement.set(true);
    try {
      this.snapshots.set(await this.api.get<PlanSnapshot[]>('/api/planning/snapshots'));
    } finally {
      this.chargement.set(false);
    }
  }

  async capturer(libelle: string): Promise<void> {
    await this.api.post<PlanSnapshot>('/api/planning/snapshots', { libelle });
    await this.reload();
  }

  async supprimer(id: number): Promise<void> {
    await this.api.delete(`/api/planning/snapshots/${id}`);
    await this.reload();
  }

  /**
   * Puts a snapshot back. Throws {@link ReferencesManquantesError} when the
   * server refuses because ids named by the snapshot no longer exist — nothing
   * was written in that case.
   */
  async restaurer(id: number): Promise<RestaurationSnapshot> {
    try {
      // The raw HttpErrorResponse, not the flattened Error: the 409 body
      // carries the ids the snapshot names and the referential has lost, and
      // that list is the whole point of the message shown to the user.
      return await this.api.postPreservingHttpError<RestaurationSnapshot>(
        `/api/planning/snapshots/${id}/restore`,
        {}
      );
    } catch (error) {
      const references = referencesManquantes(error);
      if (references) {
        throw new ReferencesManquantesError(messageErreur(error), references);
      }
      throw toError(error);
    }
  }
}

function referencesManquantes(error: unknown): string[] | null {
  const body = corpsErreur(error);
  return body && Array.isArray(body.referencesManquantes)
    ? body.referencesManquantes.filter((reference): reference is string => typeof reference === 'string')
    : null;
}

function messageErreur(error: unknown): string {
  return corpsErreur(error)?.message ?? toError(error).message;
}

function corpsErreur(error: unknown): { message?: string; referencesManquantes?: unknown[] } | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const body: unknown = error.error;
  return body && typeof body === 'object' ? (body as { message?: string; referencesManquantes?: unknown[] }) : null;
}
