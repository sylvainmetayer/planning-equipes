// The confirmation used by the two actions that destroy data nothing brings
// back: emptying the current edition (`POST /api/planning/reset`) and replaying
// a SQL dump over the whole instance.
//
// `ConfirmService` answers a click, and a click is exactly what a hurried
// administrator gives without reading. Here the dialog asks for a word to be
// typed back — the edition name, or a keyword — so the confirmation cannot be
// given without having read what is about to disappear. This is deliberately
// interface friction, not a server-side control: calling the API directly
// bypasses it, exactly as it already bypasses `ConfirmService` (issue #312).
//
// It reuses `PromptDialog` rather than adding a second input dialog; the
// comparison lives here so it is unit-tested without rendering anything.

import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { NotificationService } from '../core/notification.service';
import { PromptDialog } from './prompt-dialog';

export interface DemandeRecopie {
  title: string;
  /** What is about to happen, and how far it reaches — shown above the field. */
  message: string;
  /** Exact text the administrator has to type back. */
  valeurAttendue: string;
  confirmLabel?: string;
}

/**
 * Exact match, ignoring the whitespace around the input: a name pasted with a
 * trailing space is a transcription, not a refusal. Everything else — a
 * different case, an approximation, an empty field — is a mismatch, because
 * the point is that the word was read.
 */
export function recopieValide(saisi: string | null, attendu: string): boolean {
  return saisi !== null && saisi.trim() === attendu.trim();
}

@Injectable({ providedIn: 'root' })
export class ConfirmationRecopie {
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);

  /** `true` only when the expected text was typed back; cancelling answers `false`. */
  async demander(demande: DemandeRecopie): Promise<boolean> {
    const saisi = await PromptDialog.ask(this.dialog, {
      title: demande.title,
      message: demande.message,
      label: $localize`:@@confirmationRecopie.label:Saisissez « ${demande.valeurAttendue}:attendu: » pour confirmer`,
      confirmLabel: demande.confirmLabel,
      danger: true,
    });
    if (saisi === null) {
      // Cancelled: the user knows they cancelled, saying so would be noise.
      return false;
    }
    if (!recopieValide(saisi, demande.valeurAttendue)) {
      // A wrong entry, on the other hand, looks like a validated action from
      // where the user stands — it has to be told apart from a done one.
      this.notifications.notify({
        title: $localize`:@@confirmationRecopie.mismatch:Saisie incorrecte : rien n'a été fait.`,
        message: $localize`:@@confirmationRecopie.mismatchDetail:Le texte attendu était « ${demande.valeurAttendue}:attendu: ».`,
        variant: 'error',
      });
      return false;
    }
    return true;
  }
}
