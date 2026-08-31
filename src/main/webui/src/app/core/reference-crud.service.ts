// Shared CRUD plumbing of the reference pages: persistence through the store,
// snack bar feedback and delete confirmation, so each page only owns its form.

import { Injectable, inject } from '@angular/core';
import { ApiError, SessionExpireeError } from './api.service';
import { Avertissement, estJournalisable } from './models';
import { NotificationService } from './notification.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import { BulkResult, ReferenceDataStore } from './reference-data.store';
import { ReferenceUsageService } from './reference-usage.service';
import { ConfirmService } from '../shared/confirm-dialog';
import { errorMessage } from './error-message';

/** Failures detailed in the snack bar before it degrades to a plain count. */
const MAX_ECHECS_DETAILLES = 3;

/**
 * Warnings spelled out in the snack bar before it says "and n others". Same
 * ceiling as the failures, for the same reason: past three sentences nobody
 * reads the fourth.
 */
const MAX_AVERTISSEMENTS_DETAILLES = 3;

@Injectable({ providedIn: 'root' })
export class ReferenceCrudService {
  private readonly store = inject(ReferenceDataStore);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly usages = inject(ReferenceUsageService);

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
      const { id, avertissements } = await this.store.save(resource, payload, editingId);
      this.refreshResolution();
      // The id the server wrote, not the one that was sent: a créneau is
      // created without one, and echoing the payload printed "Créneau
      // undefined" in a snack bar that stays until it is dismissed.
      const identifiant = id ?? payload.id ?? editingId ?? '';
      if (avertissements.length > 0) {
        // Written all the same — the entity is in the list behind the snack
        // bar. No timeout: a warning nobody had time to read is a warning that
        // was not given, and this one names dates the user has to go and check.
        this.notifications.notify({
          title: $localize`:@@crud.savedWithWarnings:Enregistrement de ${label}:label: ${identifiant}:id: effectué — ${avertissements.length}:count: point(s) à vérifier.`,
          message: detailler(avertissements),
          messageJournal: detailler(avertissements.filter(estJournalisable)),
          variant: 'warning',
          timeout: 0
        });
        return true;
      }
      this.notifications.notify({
        title: editingId
          ? $localize`:@@crud.updated:Modification de ${label}:label: ${identifiant}:id: effectuée.`
          : $localize`:@@crud.created:Création de ${label}:label: ${identifiant}:id: effectuée.`,
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
   * The dialog opens on the click, and the impact figures land in it when the
   * server answers — never the other way round: awaiting the count first left
   * the delete button live with nothing on screen, and a double click then
   * stacked two dialogs and two deletions.
   *
   * @param detail extra sentence appended to the confirmation, for the entities
   *               whose deletion has consequences the user cannot see from the
   *               row itself (e.g. how many stands reference a typologie).
   *               Left empty, the counters of the entity are asked to the
   *               server instead — see {@link ReferenceUsageService}.
   */
  async remove(resource: string, id: string | number, label: string, detail = ''): Promise<boolean> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@crud.deleteTitle:Supprimer ${label}:label: ${id}:id: ?`,
      message: $localize`:@@crud.deleteMessage:Cette action est irréversible.`,
      detail: detail ? Promise.resolve(detail) : this.usages.describe(resource, [id]),
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
      // One aggregated total for the whole selection, not one line per row: a
      // list of fifty impact sentences would say less than their sum.
      detail: this.usages.describe(resource, ids),
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
    if (result.echecs.length === 0 && result.avertissements.length > 0) {
      // Every row went through, and some of them raised something: one snack
      // bar for the batch, kept open, exactly as a single save does.
      this.notifications.notify({
        title: $localize`:@@crud.bulkSavedWithWarnings:${successTitle(result.succes.length)}:saved: ${result.avertissements.length}:count: point(s) à vérifier.`,
        message: detailler(result.avertissements),
        messageJournal: detailler(result.avertissements.filter(estJournalisable)),
        variant: 'warning',
        timeout: 0
      });
      return;
    }
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
    // A partly failed batch still wrote most of its rows, and what they raised
    // is not cancelled by the one row the server refused. The snack bar is
    // taken by the refusal — Material shows one at a time, a second `open()`
    // would hide it — so the warnings go to the Notifications page instead of
    // being dropped.
    if (result.avertissements.length > 0) {
      this.notifications.notify({
        title: $localize`:@@crud.bulkPartialWarnings:${result.avertissements.length}:count: point(s) à vérifier sur les lignes enregistrées.`,
        message: detailler(result.avertissements.filter(estJournalisable)),
        variant: 'warning',
        silent: true
      });
    }
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
      title: titreErreur(error),
      message: errorMessage(error),
      variant: 'error'
    });
  }
}

/**
 * The warning sentences as one line, capped so a batch cannot fill the screen.
 * The messages come from the server already naming their dates and entities:
 * nothing is rebuilt here, and nothing is translated — the backend speaks the
 * same French as its refusals do.
 */
function detailler(avertissements: readonly Avertissement[]): string {
  const detail = avertissements
    .slice(0, MAX_AVERTISSEMENTS_DETAILLES)
    .map((avertissement) => avertissement.message)
    .join(' · ');
  const restants = avertissements.length - MAX_AVERTISSEMENTS_DETAILLES;
  return restants > 0 ? `${detail} · ${$localize`:@@crud.moreWarnings:et ${restants}:count: autre(s)`}` : detail;
}

/**
 * Names the refusal instead of labelling everything "Erreur". The three cases
 * the backend distinguishes by status say genuinely different things to the
 * operator: a row someone else already deleted is not a typo in the form, and
 * neither is a concurrent edit.
 */
function titreErreur(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return $localize`:@@crud.error:Erreur`;
  }
  switch (error.kind) {
    case 'notFound':
      return $localize`:@@crud.error.introuvable:Cette donnée n'existe plus`;
    case 'conflict':
      return $localize`:@@crud.error.conflit:Modifiée entre-temps`;
    case 'invalid':
      return $localize`:@@crud.error.invalide:Saisie refusée`;
    default:
      return $localize`:@@crud.error:Erreur`;
  }
}
