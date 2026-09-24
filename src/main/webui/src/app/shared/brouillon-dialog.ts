// What the three long admin forms (fiche animateur, stand, consigne) share
// about their draft: the auto-save a second after the last keystroke, the
// banner offering to take an interrupted entry back, and the protected close
// — Escape, the backdrop and « Annuler » ask before throwing a modified form
// away.
//
// The storage is `core/brouillon-formulaire.ts`; what counts as modified and
// what can be restored is each form's own pure module. This file is only the
// glue to a `MatDialogRef`, written once instead of three times.

import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Signal,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogRef } from '@angular/material/dialog';
import { filter } from 'rxjs';
import {
  DraftEnvelope,
  DraftFormType,
  DraftStorage,
  LOCAL_DRAFT_STORAGE,
  SESSION_DRAFT_STORAGE,
  deleteDraft,
  draftKey,
  draftPurgeGeneration,
  purgeOrphanDrafts,
  readDraft,
  writeDraft,
} from '../core/brouillon-formulaire';
import { intlLocale } from '../core/locale';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';

/** How long after the last change the draft is written: often enough to lose little, rarely enough to cost nothing. */
export const DRAFT_WRITE_DELAY_MS = 1000;

export interface FormDraftOptions<T> {
  type: DraftFormType;
  /** The record the form edits, `null` for a creation. */
  recordId: string | null;
  /** The record's `modifieLe` when the form opened — what a found draft is compared with. */
  modifieLe: string | null;
  /** What is persisted, read in a reactive context. */
  state: () => T;
  /** Whether the form differs from what it opened on, read in a reactive context. */
  modified: () => boolean;
  /** Checks what the storage hands back: `null` when the form could not hold it. */
  read: (raw: unknown) => T | null;
  /**
   * The precondition the draft carries, stored on its envelope — the draft's
   * own, which a draft taken back keeps (the record's `modifieLe` at opening
   * otherwise).
   */
  precondition?: (state: T) => string | null;
  /** Pours a draft taken back into the form. */
  apply: (draft: T) => void;
  dialogRef: MatDialogRef<unknown, unknown>;
  /** What the dialog closes with when it is left without saving. */
  cancelResult: unknown;
  /** Overridable by the specs; {@link DRAFT_WRITE_DELAY_MS} otherwise. */
  delayMs?: number;
}

/**
 * One form's draft. Built in the dialog's constructor (it needs the injection
 * context), it arms the auto-save and the close protection on its own; the
 * dialog only calls {@link resume} / {@link ignore} from the banner,
 * {@link complete} after a successful save and {@link close} from its cancel
 * button.
 */
export class FormDraft<T> {
  private readonly confirm = inject(ConfirmService);
  private readonly key: string;
  /**
   * Where the draft lives, decided by the form type and nothing else: a fiche
   * animateur carries an identity, a birth date and an e-mail, and goes to
   * sessionStorage — no caller can send it to localStorage by mistake.
   */
  private readonly storage: DraftStorage | null;
  private readonly _found = signal<DraftEnvelope<T> | null>(null);
  /** The draft found at opening, until it is taken back or ignored — what the banner shows. */
  readonly found = this._found.asReadonly();
  /**
   * The record was written since the draft was taken: the banner says so, and
   * the draft keeps its own `modifieLe`, so saving meets the concurrent-edit
   * dialog rather than overwriting in silence.
   */
  readonly conflict: Signal<boolean>;
  /** Whether the form differs from what it opened on. */
  readonly modified: Signal<boolean>;

  private timer: ReturnType<typeof setTimeout> | null = null;
  private pendingWrite: (() => void) | null = null;
  /** This form wrote a draft: only then may it remove one when it goes back to unmodified. */
  private wrote = false;
  /** Saved or abandoned: nothing is written any more, whatever the signals say. */
  private closed = false;
  /** The purge generation the form opened under: a logout since then closes its draft for good. */
  private readonly generation = draftPurgeGeneration();
  private asking = false;

  constructor(private readonly options: FormDraftOptions<T>) {
    this.key = draftKey(options.type, options.recordId);
    this.storage = inject(
      options.type === 'animateur' ? SESSION_DRAFT_STORAGE : LOCAL_DRAFT_STORAGE,
    );
    this.modified = computed(() => options.modified());
    this.conflict = computed(() => {
      const found = this._found();
      return found !== null && found.modifieLe !== options.modifieLe;
    });
    this._found.set(this.lookUp());
    this.armAutoSave();
    this.armClose();
  }

  /** Takes the found draft back into the form; it is written again as soon as the form is. */
  resume(): void {
    const found = this._found();
    if (found === null) {
      return;
    }
    this._found.set(null);
    this.options.apply(found.draft);
  }

  /** Drops the found draft, from the screen and from the storage. */
  ignore(): void {
    this._found.set(null);
    this.cancelPendingWrite();
    deleteDraft(this.storage, this.key);
    this.wrote = false;
  }

  /** The form was saved: the draft has served its purpose. */
  complete(): void {
    this.closed = true;
    this.cancelPendingWrite();
    deleteDraft(this.storage, this.key);
  }

  /**
   * Leaves the form without saving. An unmodified one closes at once; a
   * modified one asks first, and its draft is dropped only once the user
   * confirmed — answering « no » leaves both the form and the draft as they
   * were.
   */
  async close(): Promise<void> {
    if (this.asking) {
      return;
    }
    if (!untracked(this.modified)) {
      this.closed = true;
      this.cancelPendingWrite();
      this.options.dialogRef.close(this.options.cancelResult);
      return;
    }
    this.asking = true;
    try {
      const discard = await this.confirm.ask({
        title: $localize`:@@brouillon.abandon.titre:Abandonner les modifications ?`,
        message: $localize`:@@brouillon.abandon.message:La saisie non enregistrée de ce formulaire sera perdue.`,
        confirmLabel: $localize`:@@brouillon.abandon.confirmer:Abandonner`,
        cancelLabel: $localize`:@@brouillon.abandon.continuer:Continuer la saisie`,
        danger: true,
      });
      if (discard) {
        this.complete();
        this.options.dialogRef.close(this.options.cancelResult);
      }
    } finally {
      this.asking = false;
    }
  }

  private lookUp(): DraftEnvelope<T> | null {
    const envelope = readDraft<T>(this.storage, this.key);
    if (envelope === null) {
      return null;
    }
    const draft = this.options.read(envelope.draft);
    if (draft === null) {
      // Written by something this form cannot hold: no later opening will either.
      deleteDraft(this.storage, this.key);
      return null;
    }
    return { ...envelope, draft };
  }

  /**
   * Writes the draft a second after the last change, while the form differs
   * from its opening; removes the one this form wrote when it goes back to it.
   * A draft found at opening is never removed by an untouched form — only
   * « Ignorer » does that — and is never overwritten while its banner is on
   * screen: typing before answering the banner would otherwise replace the
   * very entry it offers back. The answer, either one, lets saving resume.
   */
  private armAutoSave(): void {
    const destroyRef = inject(DestroyRef);
    effect(() => {
      const modified = this.modified();
      const state = this.options.state();
      const pendingAnswer = this._found() !== null;
      untracked(() => {
        if (this.purged() || pendingAnswer) {
          return;
        }
        if (modified) {
          this.schedule(() => {
            if (this.purged()) {
              return;
            }
            const precondition = this.options.precondition?.(state) ?? this.options.modifieLe;
            writeDraft(this.storage, this.key, state, precondition);
            this.wrote = true;
          });
        } else if (this.wrote) {
          this.schedule(() => {
            if (this.purged()) {
              return;
            }
            deleteDraft(this.storage, this.key);
            this.wrote = false;
          });
        }
      });
    });
    // Destroyed with a write still pending — a navigation closes every dialog —
    // is exactly the accident the draft is for: the write goes out now.
    destroyRef.onDestroy(() => {
      const pending = this.pendingWrite;
      this.cancelPendingWrite();
      if (!this.purged() && pending) {
        pending();
      }
    });
  }

  /**
   * Whether this form must write nothing any more: saved, abandoned, or its
   * drafts purged by a logout since it opened — the last one closes it for
   * good, so a write racing the purge cannot bring the entry back.
   */
  private purged(): boolean {
    if (!this.closed && draftPurgeGeneration() !== this.generation) {
      this.closed = true;
      this.cancelPendingWrite();
    }
    return this.closed;
  }

  private armClose(): void {
    const dialogRef = this.options.dialogRef;
    // Escape and the backdrop would close the dialog without a word: they go
    // through `close` instead, which asks when there is something to lose.
    dialogRef.disableClose = true;
    dialogRef
      .backdropClick()
      .pipe(takeUntilDestroyed())
      .subscribe(() => void this.close());
    dialogRef
      .keydownEvents()
      .pipe(
        filter((event) => event.key === 'Escape' && !event.defaultPrevented),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        event.preventDefault();
        void this.close();
      });
  }

  private schedule(write: () => void): void {
    this.cancelPendingWrite();
    this.pendingWrite = write;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.pendingWrite = null;
      write();
    }, this.options.delayMs ?? DRAFT_WRITE_DELAY_MS);
  }

  private cancelPendingWrite(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
    }
    this.timer = null;
    this.pendingWrite = null;
  }
}

/**
 * Drops the drafts of records deleted since they were written, and says so
 * once: the entry is gone for good, and the user who typed it should hear it
 * rather than wonder why the banner never came back. Called by a page once
 * its records are loaded; `describe` names the records — by id for an
 * animateur, never by identity, since a notification is copied into a log
 * that outlives the logout (docs/rgpd.md §7).
 */
export function reportOrphanDrafts(
  notifications: NotificationService,
  storage: DraftStorage | null,
  type: DraftFormType,
  exists: (recordId: string) => boolean,
  describe: (ids: string) => string,
): void {
  const orphans = purgeOrphanDrafts(storage, type, exists);
  if (orphans.length === 0) {
    return;
  }
  const records = describe(orphans.join(', '));
  notifications.notify({
    title: $localize`:@@brouillon.orphelin.titre:Saisie non enregistrée ignorée`,
    message: $localize`:@@brouillon.orphelin.message:${records}:fiches: a été supprimé(e) entre-temps : la saisie non enregistrée qui s'y rapportait a été effacée.`,
    variant: 'warning',
    timeout: 8000,
  });
}

/** « 14/07 » and « 18:32 », in the reader's own time zone. */
export function draftMoment(savedAt: string): { day: string; time: string } {
  const date = new Date(savedAt);
  return {
    day: date.toLocaleDateString(intlLocale(), { day: '2-digit', month: '2-digit' }),
    time: date.toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' }),
  };
}

/**
 * The banner of a found draft: when it was written, whether the record moved
 * since, and the two ways out. A proposal, never an imposition — the form
 * stays as it opened until « Reprendre » is pressed.
 */
@Component({
  selector: 'app-draft-banner',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="brouillon-banner" role="status">
      <mat-icon aria-hidden="true">history</mat-icon>
      <div class="brouillon-banner-texte">
        <p>{{ foundText() }}</p>
        @if (conflict()) {
          <p class="brouillon-banner-conflit">{{ conflictText() }}</p>
        }
      </div>
      <div class="brouillon-banner-actions">
        <button matButton type="button" (click)="ignoreDraft.emit()" i18n="@@brouillon.banner.ignorer">Ignorer</button>
        <button matButton="tonal" type="button" (click)="resumeDraft.emit()" i18n="@@brouillon.banner.reprendre">Reprendre</button>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DraftBanner {
  readonly savedAt = input.required<string>();
  readonly conflict = input(false);
  /**
   * What the conflict means for this form. A fiche meets the concurrent-edit
   * dialog on saving; a form without a precondition (the consigne) passes its
   * own sentence.
   */
  readonly conflictMessage = input<string | null>(null);
  readonly resumeDraft = output<void>();
  readonly ignoreDraft = output<void>();

  protected readonly conflictText = computed(
    () =>
      this.conflictMessage() ??
      $localize`:@@brouillon.banner.conflit:La fiche a été modifiée depuis : à l'enregistrement, vous choisirez entre recharger et écraser.`,
  );

  protected readonly foundText = computed(() => {
    const { day, time } = draftMoment(this.savedAt());
    return $localize`:@@brouillon.banner.message:Une saisie non enregistrée du ${day}:jour: à ${time}:heure: a été retrouvée.`;
  });
}
