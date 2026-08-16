// The editions (`groupe`) the whole referential is partitioned into, and which
// one this browser is working in.
//
// Loaded once in the app shell rather than per page: the "Groupe actuel" strip
// sits in the shell and is shown on every screen. Which group is current is not
// server state — it is this browser's own choice, persisted in localStorage and
// sent as `X-Groupe-Id` (see `groupe-courant.ts`), so two tabs can work on two
// editions at the same time.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Groupe } from './models';
import { clearStoredGroupeId, getStoredGroupeId, setStoredGroupeIdAndReload } from './groupe-courant';

@Injectable({ providedIn: 'root' })
export class GroupeStore {
  readonly groupes = signal<Groupe[]>([]);

  /**
   * What the server says this browser's requests are actually resolved to.
   * Authoritative on purpose: a stored id naming a since-deleted group is
   * silently answered from the default group, and this is how the UI finds out
   * which edition it is really looking at.
   */
  readonly courant = signal<Groupe | null>(null);

  readonly autres = computed(() => this.groupes().filter((groupe) => groupe.id !== this.courant()?.id));

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    const [groupes, courant] = await Promise.all([
      this.api.get<Groupe[]>('/api/groupes'),
      this.api.get<Groupe>('/api/groupes/courant')
    ]);
    this.groupes.set(groupes);
    this.courant.set(courant);
    // The stored choice was answered from another group: drop it, so the next
    // reload doesn't keep sending a header the server ignores anyway.
    const stored = getStoredGroupeId();
    if (stored && stored !== courant.id) {
      clearStoredGroupeId();
    }
  }

  /** Switches edition; every screen is swapped at once by the page reload this triggers. */
  basculer(groupe: Groupe): void {
    setStoredGroupeIdAndReload(groupe.id);
  }
}
