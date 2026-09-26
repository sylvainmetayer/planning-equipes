// Shared CRUD plumbing of the reference pages: persistence through the store,
// snack bar feedback and delete confirmation, so each page only owns its form.

import { Injectable, Injector, inject } from '@angular/core';
import { ApiError, SessionExpireeError } from './api.service';
import { Avertissement, estJournalisable } from './models';
import { NotificationService } from './notification.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import { BulkResult, RecordId, ReferenceDataStore, SaveResult } from './reference-data.store';
import { ReferenceUsageService } from './reference-usage.service';
import { ConfirmService } from '../shared/confirm-dialog';
import { errorMessage, isFrozenReferential } from './error-message';
import { GelReferentielStore } from './gel-referentiel.store';
import { intlLocale } from './locale';

/** « (dernière écriture le 06/09/2026 à 17:34) », in the reader's own time zone. */
function quand(modifieLe: string | null): string {
  if (!modifieLe) {
    return '';
  }
  const moment = new Date(modifieLe).toLocaleString(intlLocale());
  return ' ' + $localize`:@@crud.concurrent.moment:(dernière écriture le ${moment}:moment:)`;
}

/** Failures detailed in the snack bar before it degrades to a plain count. */
const MAX_ECHECS_DETAILLES = 3;

/**
 * Sentinel id {@link ReferenceCrudService.persist} answers when the user
 * chose to reload rather than overwrite: nothing was written, the caller has
 * nothing to announce and the form can close over the refreshed store.
 */
const RECHARGE = '\u0000recharge';

/**
 * Warnings spelled out in the snack bar before it says "and n others". Same
 * ceiling as the failures, for the same reason: past three sentences nobody
 * reads the fourth.
 */
const MAX_AVERTISSEMENTS_DETAILLES = 3;

/**
 * The screen of a resource whose route is not its API name. The manual
 * adjustments are `contraintes-ad-hoc` on the wire and `/ad-hoc-constraints`
 * in the router: « Voir la fiche » on their warning led to a page that does
 * not exist.
 */
const ROUTE_PAR_RESSOURCE: Readonly<Record<string, string>> = {
  'contraintes-ad-hoc': '/ad-hoc-constraints',
};

/**
 * What the confirmation and the notifications call an entity instead of its
 * id, which the server draws per edition and names nothing to a reader.
 */
export interface EntityName {
  /** A stand's name, a typologie's label, an animateur's « Prénom Nom ». */
  text: string;
  /**
   * The name is personal data (an animateur's identity): the confirmation
   * shows it, but a notification names the id instead — every one is copied
   * into a `localStorage` log that outlives the logout (`docs/rgpd.md` §7).
   */
  personal?: boolean;
}

/** Options of {@link ReferenceCrudService.remove}. */
export interface RemoveOptions {
  /**
   * Extra sentence appended to the confirmation, for the entities whose
   * deletion has consequences the user cannot see from the row itself (e.g.
   * how many stands reference a typologie). Left out, the counters of the
   * entity are asked to the server instead — see {@link ReferenceUsageService}.
   */
  detail?: string;
  /** What to call the entity; the id when absent or blank. */
  name?: EntityName;
}

@Injectable({ providedIn: 'root' })
export class ReferenceCrudService {
  private readonly store = inject(ReferenceDataStore);
  private readonly injector = inject(Injector);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly usages = inject(ReferenceUsageService);

  /**
   * Loads every collection; failures are reported but never thrown to the
   * view. Answers whether the store now holds what the server has — a caller
   * about to reason on an absence (a draft whose fiche is gone) must not do it
   * on the empty lists of a failed load.
   */
  async reload(): Promise<boolean> {
    try {
      await this.store.reload();
      return true;
    } catch (error) {
      this.reportError(error);
      return false;
    }
  }

  /**
   * Creates or updates an entity. `editingId` is null for a creation. Returns
   * true when the form can close: the entity was persisted — or, after a
   * concurrent-edit conflict, the user chose to reload instead, and the store
   * now holds the other session's version (see {@link resoudreConflit}).
   * No entity is keyed by an id the user types: on a creation the server
   * draws it, so an empty `id` is dropped from the payload rather than sent.
   * `name` is what the notification calls the entity; the id when absent.
   */
  async save<T extends { id?: RecordId | null }>(
    resource: string,
    payload: T,
    editingId: RecordId | null,
    label: string,
    name?: EntityName,
  ): Promise<boolean> {
    try {
      const { id, avertissements } = await this.persist(
        resource,
        withoutBlankId(payload),
        editingId,
      );
      if (id === RECHARGE) {
        return true;
      }
      this.refreshResolution();
      // The id the server wrote, not the one that was sent: every entity is
      // created without one, and echoing the payload printed "Créneau
      // undefined" in a snack bar that stays until it is dismissed.
      const identifiant = id ?? payload.id ?? editingId ?? '';
      if (avertissements.length > 0) {
        const count = avertissements.length;
        // Written all the same — the entity is in the list behind the snack
        // bar. No timeout: a warning nobody had time to read is a warning that
        // was not given, and this one names dates the user has to go and check.
        this.notifications.notify({
          ...namedTitles(
            identifiant,
            name,
            (shown) =>
              $localize`:@@crud.savedWithWarnings:Enregistrement de ${label}:label: « ${shown}:nom: » effectué — ${count}:count: point(s) à vérifier.`,
          ),
          message: detailler(avertissements),
          messageJournal: detailler(avertissements.filter(estJournalisable)),
          variant: 'warning',
          timeout: 0,
          // The dates to check live on the record just saved: one click
          // reopens it, instead of a search through the list behind the snack.
          lien: {
            route: ROUTE_PAR_RESSOURCE[resource] ?? `/${resource}`,
            queryParams: { edit: String(identifiant) },
            libelle: $localize`:@@crud.voirFiche:Voir la fiche`,
          },
        });
        return true;
      }
      this.notifications.notify({
        ...namedTitles(identifiant, name, (shown) =>
          editingId
            ? $localize`:@@crud.updated:Modification de ${label}:label: « ${shown}:nom: » effectuée.`
            : $localize`:@@crud.created:Création de ${label}:label: « ${shown}:nom: » effectuée.`,
        ),
        variant: 'success',
        timeout: 4000,
      });
      return true;
    } catch (error) {
      this.reportError(error);
      return false;
    }
  }

  /**
   * Writes, and turns the one refusal that has an answer into that answer.
   *
   * A 409 carrying `MODIFICATION_CONCURRENTE` (issue #362) means another
   * session wrote the row after this form loaded it. Silently overwriting is
   * what the server just refused; silently failing would lose the user's
   * typing to a red banner. So the user chooses: reload — the store is
   * refreshed with the other session's version and the form closes, nothing of
   * theirs is written — or overwrite — the same payload is sent again without
   * its precondition, which is how a client says it knows.
   *
   * <p>Dismissing (Escape, the backdrop) is neither: it keeps the form open
   * with everything the user typed. This is the one dialog of the application
   * whose cancel button performs a destructive action, so the gesture that
   * means "I did not decide" must not perform it — hence
   * {@link ConfirmService.askThreeWay}.</p>
   *
   * Any other failure propagates to the caller's snack bar as before.
   */
  private async persist<T extends { id?: RecordId | null }>(
    resource: string,
    payload: T,
    editingId: RecordId | null,
  ): Promise<SaveResult> {
    try {
      return await this.store.save(resource, payload, editingId);
    } catch (error) {
      if (!(error instanceof ApiError) || !error.modificationConcurrente) {
        throw error;
      }
      const choix = await this.confirm.askThreeWay({
        title: $localize`:@@crud.error.conflit:Modifiée entre-temps`,
        message: error.message + quand(error.modifieLe),
        confirmLabel: $localize`:@@crud.concurrent.overwrite:Écraser quand même`,
        cancelLabel: $localize`:@@crud.concurrent.reload:Recharger`,
        danger: true,
      });
      if (choix === true) {
        return this.store.save(resource, { ...payload, modifieLe: null }, editingId);
      }
      if (choix === null) {
        // Dismissed: nothing written, nothing reloaded, the form stays as typed.
        throw error;
      }
      await this.store.reload();
      this.notifications.notify({
        title: $localize`:@@crud.concurrent.reloaded:Fiche rechargée, vos modifications n'ont pas été enregistrées.`,
        message: $localize`:@@crud.concurrent.reloadedHint:Rouvrez-la pour les reporter sur la version actuelle.`,
        variant: 'warning',
        timeout: 8000,
      });
      return { id: RECHARGE, avertissements: [] };
    }
  }

  /**
   * Asks for a confirmation, then deletes. Returns true when deleted.
   *
   * The dialog opens on the click, and the impact figures land in it when the
   * server answers — never the other way round: awaiting the count first left
   * the delete button live with nothing on screen, and a double click then
   * stacked two dialogs and two deletions.
   *
   * The confirmation always shows `name`, personal or not: a dialog is never
   * logged. Only the notification that follows is, hence {@link namedTitles}.
   */
  async remove(
    resource: string,
    id: string | number,
    label: string,
    { detail = '', name }: RemoveOptions = {},
  ): Promise<boolean> {
    const shown = shownName(id, name);
    const confirmed = await this.confirm.ask({
      title: $localize`:@@crud.deleteTitle:Supprimer ${label}:label: « ${shown}:nom: » ?`,
      message: $localize`:@@crud.deleteMessage:Cette action est irréversible.`,
      detail: detail ? Promise.resolve(detail) : this.usages.describe(resource, [id]),
      confirmLabel: $localize`:@@crud.deleteConfirm:Supprimer`,
      danger: true,
    });
    if (!confirmed) {
      return false;
    }
    try {
      await this.store.remove(resource, id);
      this.refreshResolution();
      this.notifications.notify({
        ...namedTitles(
          id,
          name,
          (named) =>
            $localize`:@@crud.deleted:Suppression de ${label}:label: « ${named}:nom: » effectuée.`,
        ),
        variant: 'success',
        timeout: 4000,
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
    labelPluriel: string,
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
      danger: true,
    });
    if (!confirmed) {
      return 0;
    }
    try {
      const result = await this.store.removeMany(resource, ids);
      this.refreshResolution();
      this.reportBulk(
        result,
        (count) =>
          $localize`:@@crud.deletedMany:Suppression de ${count}:count: ${labelPluriel}:label: effectuée.`,
        (count) =>
          $localize`:@@crud.deleteManyFailed:${count}:count: ${labelPluriel}:label: n'ont pas pu être supprimés.`,
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
    labelPluriel: string,
  ): Promise<number> {
    if (payloads.length === 0) {
      return 0;
    }
    try {
      const result = await this.store.saveMany(resource, payloads);
      this.refreshResolution();
      this.reportBulk(
        result,
        (count) =>
          $localize`:@@crud.updatedMany:Modification de ${count}:count: ${labelPluriel}:label: effectuée.`,
        (count) =>
          $localize`:@@crud.updateManyFailed:${count}:count: ${labelPluriel}:label: n'ont pas pu être modifiés.`,
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
    failureTitle: (count: number) => string,
  ): void {
    if (result.echecs.length === 0 && result.avertissements.length > 0) {
      // Every row went through, and some of them raised something: one snack
      // bar for the batch, kept open, exactly as a single save does.
      this.notifications.notify({
        title: $localize`:@@crud.bulkSavedWithWarnings:${successTitle(result.succes.length)}:saved: ${result.avertissements.length}:count: point(s) à vérifier.`,
        message: detailler(result.avertissements),
        messageJournal: detailler(result.avertissements.filter(estJournalisable)),
        variant: 'warning',
        timeout: 0,
      });
      return;
    }
    if (result.echecs.length === 0) {
      this.notifications.notify({
        title: successTitle(result.succes.length),
        variant: 'success',
        timeout: 4000,
      });
      return;
    }
    const details = result.echecs
      .slice(0, MAX_ECHECS_DETAILLES)
      .map((echec) => `${echec.id} : ${echec.message}`)
      .join(' · ');
    const restants = result.echecs.length - MAX_ECHECS_DETAILLES;
    // A row refused as stale cannot be retried from here: the dialog still
    // holds the values it opened with, so saving again re-sends the same
    // out-of-date stamp and fails identically (issue #362). The selection has
    // just been reloaded, so the way out is to reopen it.
    const concurrentes = result.echecs.filter((echec) => echec.concurrente).length;
    const suite =
      concurrentes > 0
        ? ' ' +
          $localize`:@@crud.bulkConcurrent:${concurrentes}:count: ligne(s) modifiées par une autre session : la liste a été rechargée, refaites la sélection.`
        : '';
    this.notifications.notify({
      title: failureTitle(result.echecs.length),
      message:
        (restants > 0
          ? details + ' · ' + $localize`:@@crud.bulkMoreErrors:et ${restants}:count: autre(s)`
          : details) + suite,
      variant: 'error',
      timeout: concurrentes > 0 ? 0 : undefined,
    });
    // A partly failed batch still wrote most of its rows, and what they raised
    // is not cancelled by the one row the server refused. The snack bar is
    // taken by the refusal — Material shows one at a time, a second `open()`
    // would hide it — so the warnings go to the recent messages of the home page instead of
    // being dropped.
    if (result.avertissements.length > 0) {
      this.notifications.notify({
        title: $localize`:@@crud.bulkPartialWarnings:${result.avertissements.length}:count: point(s) à vérifier sur les lignes enregistrées.`,
        message: detailler(result.avertissements.filter(estJournalisable)),
        variant: 'warning',
        silent: true,
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
    if (isFrozenReferential(error)) {
      // Another session froze the family since this screen loaded its
      // padlocks: re-read them, so the form says it before the next click.
      // Resolved lazily — only this refusal needs the store.
      void this.injector.get(GelReferentielStore).reload();
    }
    this.notifications.notify({
      title: titreErreur(error),
      message: errorMessage(error),
      variant: 'error',
    });
  }
}

/** What the screen calls the entity: its name, or its id when it has none. */
function shownName(id: RecordId, name: EntityName | undefined): string {
  return name?.text.trim() || String(id);
}

/**
 * The notification's title naming the entity — by its id when that name is
 * personal: a write-time notification never names a person (AGENTS.md), since
 * the browser copies every one into a `localStorage` log that outlives the
 * logout (docs/rgpd.md §7). Only the confirmation, which is never logged,
 * shows the person's name.
 */
function namedTitles(
  id: RecordId,
  name: EntityName | undefined,
  title: (shown: string) => string,
): { title: string } {
  return { title: title(name?.personal ? String(id) : shownName(id, name)) };
}

/** The payload without its `id` when it carries none worth sending. */
function withoutBlankId<T extends { id?: string | number | null }>(payload: T): T {
  if (payload.id !== '' && payload.id !== null) {
    return payload;
  }
  const { id: _blank, ...rest } = payload;
  return rest as T;
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
  return restants > 0
    ? detail + ' · ' + $localize`:@@crud.moreWarnings:et ${restants}:count: autre(s)`
    : detail;
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
