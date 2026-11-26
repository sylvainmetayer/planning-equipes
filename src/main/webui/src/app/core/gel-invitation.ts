// The invitation to enter the Calcul phase (ADR 0052, 0115), which freezes the
// stands and the timeslots, offered at the milestone where a late edit starts
// to cost: the edition's first completed solve. Publishing needs none, since
// it moves the edition to Publiée on its own. Offered, never imposed — a
// snack bar leading to the phase control, once per edition.
//
// « Once » is remembered in this browser (localStorage, per edition): the
// invitation is a nudge, not state the server must hold, and a second browser
// being offered it again costs one dismissal. Every access is guarded — a
// private window or blocked storage must not break a solve's landing, it only
// means the invitation may come back.

import { Injectable, inject } from '@angular/core';
import { editionScopedKey } from './edition-courante';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';

/** The moment the invitation is offered at. */
export type GelMilestone = 'resolution';

const STORAGE_BASE = 'planning-equipes.gel-invitation';

function storageKey(milestone: GelMilestone): string {
  return editionScopedKey(`${STORAGE_BASE}.${milestone}`);
}

/** Whether the invitation of this milestone was already offered in this edition, on this browser. */
export function alreadyOffered(milestone: GelMilestone): boolean {
  try {
    return localStorage.getItem(storageKey(milestone)) !== null;
  } catch {
    return false;
  }
}

function markOffered(milestone: GelMilestone): void {
  try {
    localStorage.setItem(storageKey(milestone), new Date().toISOString());
  } catch {
    // Storage unavailable: the invitation may be offered again, nothing worse.
  }
}

@Injectable({ providedIn: 'root' })
export class GelInvitation {
  private readonly editions = inject(EditionStore);
  private readonly notifications = inject(NotificationService);

  /**
   * Offers to enter the Calcul phase, unless this milestone was already
   * offered in this edition or the edition is past its preparation. Never
   * throws: it rides on a solve's landing.
   */
  offer(milestone: GelMilestone): void {
    if (alreadyOffered(milestone)) {
      return;
    }
    // Not knowing the phase, the invitation is neither shown nor spent: the
    // next solve landing will offer it again.
    const phase = this.editions.phase();
    if (phase === null) {
      return;
    }
    markOffered(milestone);
    if (phase !== 'PREPARATION') {
      return;
    }
    this.notifications.notify({
      title: $localize`:@@gel.invitation.titre:Passer l'édition en phase Calcul ?`,
      message: $localize`:@@gel.invitation.resolution:Un planning est calculé : une retouche des stands ou des créneaux le rendrait obsolète. La phase Calcul les fige.`,
      variant: 'info',
      timeout: 0,
      lien: {
        route: '/',
        libelle: $localize`:@@gel.invitation.lien:Voir la phase de l'édition`,
      },
    });
  }
}
