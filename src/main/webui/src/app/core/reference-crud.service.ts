// Shared CRUD plumbing of the reference pages: persistence through the store,
// snack bar feedback and delete confirmation, so each page only owns its form.

import { Injectable, inject } from '@angular/core';
import { SessionExpireeError } from './api.service';
import { NotificationService } from './notification.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import { BulkResult, ReferenceDataStore } from './reference-data.store';
import { ConfirmService } from '../shared/confirm-dialog';
import { errorMessage } from './error-message';

/** Failures detailed in the snack bar before it degrades to a plain count. */
const MAX_ECHECS_DETAILLES = 3;

@Injectable({ providedIn: 'root' })
export class ReferenceCrudService {
  private readonly store = inject(ReferenceDataStore);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly resolution = inject(PlanningResolutionStore);

  /** Loads every collection; failures are reported but never thrown to the view. */
  async reload(): Promise<void> {
    try {
      await this.store.reload();
    } catch (error) {
      this.reportError(error);
    }
  }

  /**
   * Creates or updates an entity. `editingId` is null for a creation. Returns
   * true when the entity was persisted, so the page can reset its form.
   * `requireId` defaults to true (every entity but créneaux is keyed by a
   * user-typed natural id); créneaux pass `false` since their id is generated
   * by the server and never entered by the user.
   */
  async save<T extends { id?: string | number | null }>(
    resource: string,
    payload: T,
    editingId: string | number | null,
    label: string,
    options: { requireId?: boolean } = {}
  ): Promise<boolean> {
    const requireId = options.requireId ?? true;
    if (requireId && !payload.id) {
      this.notifications.notify({
        title: $localize`:@@crud.idRequired:Un identifiant est requis.`,
        variant: 'error'
      });
      return false;
    }
    try {
      await this.store.save(resource, payload, editingId);
      this.refreshResolution();
      this.notifications.notify({
        title: editingId
          ? $localize`:@@crud.updated:Modification de ${label}:label: ${payload.id}:id: effectuée.`
          : $localize`:@@crud.created:Création de ${label}:label: ${payload.id}:id: effectuée.`,
        variant: 'success',
        timeout: 4000
      });
      return true;
    } catch (error) {
      this.reportError(error);
      return false;
    }
  }

  /** Asks for a confirmation, then deletes. Returns true when deleted. */
  /**
   * @param detail extra sentence appended to the confirmation, for the entities
   *               whose deletion has consequences the user cannot see from the
   *               row itself (e.g. how many stands reference a typologie)
   */
  async remove(resource: string, id: string | number, label: string, detail = ''): Promise<boolean> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@crud.deleteTitle:Supprimer ${label}:label: ${id}:id: ?`,
      message: detail
        ? $localize`:@@crud.deleteMessageDetail:${detail}:detail: Cette action est irréversible.`
        : $localize`:@@crud.deleteMessage:Cette action est irréversible.`,
      confirmLabel: $localize`:@@crud.deleteConfirm:Supprimer`,
      danger: true
    });
    if (!confirmed) {
      return false;
    }
    try {
      await this.store.remove(resource, id);
      this.refreshResolution();
      this.notifications.notify({
        title: $localize`:@@crud.deleted:Suppression de ${label}:label: ${id}:id: effectuée.`,
        variant: 'success',
        timeout: 4000
      });
      return true;
    } catch (error) {
      this.reportError(error);
      return false;
    }
  }

  /**
   * Deletes a whole selection after a single confirmation, and returns how many
   * rows were actually deleted. A row the server refuses (a typologie still
   * assigned, …) does not cancel the rest of the batch: it is reported next to
   * the successes so the user knows exactly what is left.
   */
  async removeMany(
    resource: string,
    ids: readonly (string | number)[],
    labelPluriel: string
  ): Promise<number> {
    if (ids.length === 0) {
      return 0;
    }
    const count = ids.length;
    const confirmed = await this.confirm.ask({
      title: $localize`:@@crud.deleteManyTitle:Supprimer ${count}:count: ${labelPluriel}:label: ?`,
      message: $localize`:@@crud.deleteMessage:Cette action est irréversible.`,
      confirmLabel: $localize`:@@crud.deleteConfirm:Supprimer`,
      danger: true
    });
    if (!confirmed) {
      return 0;
    }
    try {
      const result = await this.store.removeMany(resource, ids);
      this.refreshResolution();
      this.reportBulk(
        result,
        (nombre) => $localize`:@@crud.deletedMany:Suppression de ${nombre}:count: ${labelPluriel}:label: effectuée.`,
        (nombre) => $localize`:@@crud.deleteManyFailed:${nombre}:count: ${labelPluriel}:label: n'ont pas pu être supprimés.`
      );
      return result.succes.length;
    } catch (error) {
      this.reportError(error);
      return 0;
    }
  }

  /**
   * Persists a whole selection already patched by the caller (bulk edit), and
   * returns how many rows were actually saved. Same all-or-some semantics as
   * {@link removeMany}: one rejected row never rolls back the others.
   */
  async saveMany<T extends { id: string | number }>(
    resource: string,
    payloads: readonly T[],
    labelPluriel: string
  ): Promise<number> {
    if (payloads.length === 0) {
      return 0;
    }
    try {
      const result = await this.store.saveMany(resource, payloads);
      this.refreshResolution();
      this.reportBulk(
        result,
        (nombre) => $localize`:@@crud.updatedMany:Modification de ${nombre}:count: ${labelPluriel}:label: effectuée.`,
        (nombre) => $localize`:@@crud.updateManyFailed:${nombre}:count: ${labelPluriel}:label: n'ont pas pu être modifiés.`
      );
      return result.succes.length;
    } catch (error) {
      this.reportError(error);
      return 0;
    }
  }

  /**
   * One snack bar per batch: a success when everything went through, an error
   * carrying the first failing ids otherwise — a batch of fifty must not open
   * fifty snack bars.
   */
  private reportBulk(
    result: BulkResult,
    successTitle: (count: number) => string,
    failureTitle: (count: number) => string
  ): void {
    if (result.echecs.length === 0) {
      this.notifications.notify({
        title: successTitle(result.succes.length),
        variant: 'success',
        timeout: 4000
      });
      return;
    }
    const details = result.echecs
      .slice(0, MAX_ECHECS_DETAILLES)
      .map((echec) => `${echec.id} : ${echec.message}`)
      .join(' · ');
    const restants = result.echecs.length - MAX_ECHECS_DETAILLES;
    this.notifications.notify({
      title: failureTitle(result.echecs.length),
      message: restants > 0 ? `${details} · ${$localize`:@@crud.bulkMoreErrors:et ${restants}:count: autre(s)`}` : details,
      variant: 'error'
    });
  }

  /**
   * The backend stamps "reference data last edited at" on every write, and the
   * toolbar indicator compares it to the last solve. Without this refresh the
   * warning would only appear on the next full page load — that is, long after
   * the edit that made the persisted planning stale. Fire-and-forget: it is a
   * hint, never a reason to fail the save the user just made.
   */
  private refreshResolution(): void {
    void this.resolution.reload().catch(() => undefined);
  }

  reportError(error: unknown): void {
    // An expired session is not news: the auth interceptor is already
    // redirecting to /login, a toast on top would just be technical noise.
    if (error instanceof SessionExpireeError) {
      return;
    }
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error'
    });
  }
}
