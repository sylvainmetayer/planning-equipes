// The invitation to freeze the stands and the timeslots (ADR 0052), offered at
// the two milestones where a late edit starts to cost: the edition's first
// completed solve, and its first publication. Offered, never imposed — a
// snack bar leading to the freeze switches, once per edition and milestone.
//
// « Once » is remembered in this browser (localStorage, per edition): the
// invitation is a nudge, not state the server must hold, and a second browser
// being offered it again costs one dismissal. Every access is guarded — a
// private window or blocked storage must not break a solve's landing, it only
// means the invitation may come back.

import { Injectable, inject } from '@angular/core';
import { editionScopedKey } from './edition-courante';
import { GelReferentielStore } from './gel-referentiel.store';
import { NotificationService } from './notification.service';

/** The two moments the invitation is offered at. */
export type GelMilestone = 'resolution' | 'publication';

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
  private readonly store = inject(GelReferentielStore);
  private readonly notifications = inject(NotificationService);

  /**
   * Offers to freeze the stands and the timeslots, unless this milestone was
   * already offered in this edition or both are frozen already. Never throws:
   * it rides on a solve's landing and a publication's success.
   */
  async offer(milestone: GelMilestone): Promise<void> {
    if (alreadyOffered(milestone)) {
      return;
    }
    // The store never rejects: a failed read leaves its error set instead. Not
    // knowing what is frozen, the invitation is neither shown nor spent — the
    // next milestone landing will offer it again.
    try {
      await this.store.reload();
    } catch {
      return;
    }
    if (this.store.error()) {
      return;
    }
    markOffered(milestone);
    if (this.store.isFrozen('STANDS') && this.store.isFrozen('CRENEAUX')) {
      return;
    }
    this.notifications.notify({
      title: $localize`:@@gel.invitation.titre:Figer les stands et les créneaux ?`,
      message:
        milestone === 'publication'
          ? $localize`:@@gel.invitation.publication:Le planning est publié : une retouche ferait bouger des plannings envoyés.`
          : $localize`:@@gel.invitation.resolution:Un planning est calculé : une retouche le rendrait obsolète.`,
      variant: 'info',
      timeout: 0,
      lien: {
        route: '/parametres',
        queryParams: { onglet: 'edition' },
        libelle: $localize`:@@gel.invitation.lien:Voir le gel du référentiel`,
      },
    });
  }
}
