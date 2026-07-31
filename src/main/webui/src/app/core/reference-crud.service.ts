// Shared CRUD plumbing of the reference pages: persistence through the store,
// snack bar feedback and delete confirmation, so each page only owns its form.

import { Injectable, inject } from '@angular/core';
import { NotificationService } from './notification.service';
import { ReferenceDataStore } from './reference-data.store';
import { ConfirmService } from '../shared/confirm-dialog';

@Injectable({ providedIn: 'root' })
export class ReferenceCrudService {
  private readonly store = inject(ReferenceDataStore);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

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
   */
  async save<T extends { id: string }>(
    resource: string,
    payload: T,
    editingId: string | null,
    label: string
  ): Promise<boolean> {
    if (!payload.id) {
      this.notifications.notify({
        title: $localize`:@@crud.idRequired:Un identifiant est requis.`,
        variant: 'error'
      });
      return false;
    }
    try {
      await this.store.save(resource, payload, editingId);
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
  async remove(resource: string, id: string, label: string): Promise<boolean> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@crud.deleteTitle:Supprimer ${label}:label: ${id}:id: ?`,
      message: $localize`:@@crud.deleteMessage:Cette action est irréversible.`,
      confirmLabel: $localize`:@@crud.deleteConfirm:Supprimer`,
      danger: true
    });
    if (!confirmed) {
      return false;
    }
    try {
      await this.store.remove(resource, id);
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

  reportError(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: error instanceof Error ? error.message : String(error),
      variant: 'error'
    });
  }
}
