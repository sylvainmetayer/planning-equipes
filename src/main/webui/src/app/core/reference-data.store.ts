// Reference data shared by the CRUD editors and by the pages that must refresh
// them after a bulk change (sample load, CSV import, SQL dump replay).

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Animateur, ContrainteAdHoc, Creneau, Emplacement, Stand, TypologieItem, Volumetrie } from './models';
import { errorMessage } from './error-message';

/**
 * Outcome of a bulk delete/save: the entities the server accepted, and one
 * entry per failure. A batch never stops on the first error — deleting ten
 * stands of which one is still referenced must delete the other nine.
 */
export interface BulkResult {
  succes: (string | number)[];
  echecs: { id: string | number; message: string }[];
}

/** The six collections this store holds, as reload targets. */
export type ReferenceFamily =
  | 'typologies'
  | 'creneaux'
  | 'animateurs'
  | 'stands'
  | 'emplacements'
  | 'contraintes';

const ALL_FAMILIES: readonly ReferenceFamily[] = [
  'typologies',
  'creneaux',
  'animateurs',
  'stands',
  'emplacements',
  'contraintes'
];

/**
 * What a write to `/api/<resource>` can invalidate, beyond the volumetrie
 * (which every write can change and which is therefore always re-read).
 *
 * <p>Reloading all seven collections after every single write meant that
 * renaming ONE typologie re-fetched 150 animateurs and 65 stands on the real
 * dataset, plus a server-side volumetrie computation — a cost that grows with
 * the referential and shows up as an unattributable "the app is slow".</p>
 *
 * <p>The lists are deliberately generous rather than minimal: a créneau, an
 * animateur or a stand can be named by an ad hoc constraint, and an
 * emplacement is pointed at by stands. An unknown resource falls back to a
 * full reload, so adding an endpoint without touching this table stays
 * correct — merely slow.</p>
 */
const FAMILIES_INVALIDATED_BY: Readonly<Record<string, readonly ReferenceFamily[]>> = {
  typologies: ['typologies'],
  creneaux: ['creneaux', 'contraintes'],
  animateurs: ['animateurs', 'contraintes'],
  stands: ['stands', 'contraintes'],
  emplacements: ['emplacements', 'stands'],
  'contraintes-ad-hoc': ['contraintes']
};

@Injectable({ providedIn: 'root' })
export class ReferenceDataStore {
  readonly typologies = signal<TypologieItem[]>([]);
  readonly creneaux = signal<Creneau[]>([]);
  readonly animateurs = signal<Animateur[]>([]);
  readonly stands = signal<Stand[]>([]);
  readonly emplacements = signal<Emplacement[]>([]);
  readonly contraintes = signal<ContrainteAdHoc[]>([]);
  /** Real problem scale for the next solve; see {@link Volumetrie}. */
  readonly volumetrie = signal<Volumetrie>({ animateurCount: 0, posteCount: 0, contrainteAdHocCount: 0 });

  private readonly api = inject(ApiService);

  /**
   * Re-reads the given families, or all of them when none is named — imports
   * and dump replays legitimately invalidate everything. The volumetrie is
   * always re-read: any write can change the real problem size.
   */
  async reload(families: readonly ReferenceFamily[] = ALL_FAMILIES): Promise<void> {
    const wanted = new Set(families);
    await Promise.all([
      this.reloadIf(wanted, 'typologies', '/api/typologies', this.typologies),
      this.reloadIf(wanted, 'creneaux', '/api/creneaux', this.creneaux),
      this.reloadIf(wanted, 'animateurs', '/api/animateurs', this.animateurs),
      this.reloadIf(wanted, 'stands', '/api/stands', this.stands),
      this.reloadIf(wanted, 'emplacements', '/api/emplacements', this.emplacements),
      this.reloadIf(wanted, 'contraintes', '/api/contraintes-ad-hoc', this.contraintes),
      this.api.get<Volumetrie>('/api/planning/volumetrie').then((volumetrie) => this.volumetrie.set(volumetrie))
    ]);
  }

  private async reloadIf<T>(
    wanted: ReadonlySet<ReferenceFamily>,
    family: ReferenceFamily,
    url: string,
    cible: { set(value: T[]): void }
  ): Promise<void> {
    if (!wanted.has(family)) {
      return;
    }
    cible.set(await this.api.get<T[]>(url));
  }

  /** What a write to this resource invalidates; everything when it is not listed. */
  private familiesFor(resource: string): readonly ReferenceFamily[] {
    return FAMILIES_INVALIDATED_BY[resource] ?? ALL_FAMILIES;
  }

  /** Creates or updates an entity, then refreshes every collection. */
  async save<T extends { id?: string | number | null }>(
    resource: string,
    payload: T,
    editingId: string | number | null
  ): Promise<void> {
    if (editingId !== null && editingId !== undefined) {
      await this.api.put(`/api/${resource}/${encodeURIComponent(String(editingId))}`, payload);
    } else {
      await this.api.post(`/api/${resource}`, payload);
    }
    await this.reload(this.familiesFor(resource));
  }

  async remove(resource: string, id: string | number): Promise<void> {
    await this.api.delete(`/api/${resource}/${encodeURIComponent(String(id))}`);
    await this.reload(this.familiesFor(resource));
  }

  /**
   * Deletes several entities, then refreshes every collection once instead of
   * once per entity. The API is keyed by id (there is no bulk endpoint), so the
   * requests are sent one after the other: it keeps the failure attributable to
   * its own id and spares the server a burst of concurrent writes.
   */
  async removeMany(resource: string, ids: readonly (string | number)[]): Promise<BulkResult> {
    return this.runBulk(resource, ids, (id) =>
      this.api.delete(`/api/${resource}/${encodeURIComponent(String(id))}`)
    );
  }

  /** Same batching as {@link removeMany}, for entities already patched by the caller. */
  async saveMany<T extends { id: string | number }>(resource: string, payloads: readonly T[]): Promise<BulkResult> {
    const parId = new Map<string | number, T>(payloads.map((payload) => [payload.id, payload]));
    return this.runBulk(resource, [...parId.keys()], (id) =>
      this.api.put(`/api/${resource}/${encodeURIComponent(String(id))}`, parId.get(id))
    );
  }

  private async runBulk(
    resource: string,
    ids: readonly (string | number)[],
    action: (id: string | number) => Promise<unknown>
  ): Promise<BulkResult> {
    const result: BulkResult = { succes: [], echecs: [] };
    for (const id of ids) {
      try {
        await action(id);
        result.succes.push(id);
      } catch (error) {
        result.echecs.push({ id, message: errorMessage(error) });
      }
    }
    await this.reload(this.familiesFor(resource));
    return result;
  }

}
