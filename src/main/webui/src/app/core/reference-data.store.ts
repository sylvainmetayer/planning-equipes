// Reference data shared by the CRUD editors and by the pages that must refresh
// them after a bulk change (sample load, CSV import, SQL dump replay).

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Animateur, ContrainteAdHoc, Creneau, Emplacement, GroupeCreneau, Stand, TypologieItem, Volumetrie } from './models';

/**
 * Outcome of a bulk delete/save: the entities the server accepted, and one
 * entry per failure. A batch never stops on the first error — deleting ten
 * stands of which one is still referenced must delete the other nine.
 */
export interface BulkResult {
  succes: (string | number)[];
  echecs: { id: string | number; message: string }[];
}

@Injectable({ providedIn: 'root' })
export class ReferenceDataStore {
  readonly typologies = signal<TypologieItem[]>([]);
  readonly creneaux = signal<Creneau[]>([]);
  readonly groupesCreneaux = signal<GroupeCreneau[]>([]);
  readonly animateurs = signal<Animateur[]>([]);
  readonly stands = signal<Stand[]>([]);
  readonly emplacements = signal<Emplacement[]>([]);
  readonly contraintes = signal<ContrainteAdHoc[]>([]);
  /** Real problem scale for the next solve; see {@link Volumetrie}. */
  readonly volumetrie = signal<Volumetrie>({ animateurCount: 0, posteCount: 0, contrainteAdHocCount: 0 });

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    const [typologies, creneaux, groupesCreneaux, animateurs, stands, emplacements, contraintes, volumetrie] =
      await Promise.all([
        this.api.get<TypologieItem[]>('/api/typologies'),
        this.api.get<Creneau[]>('/api/creneaux'),
        this.api.get<GroupeCreneau[]>('/api/groupes-creneaux'),
        this.api.get<Animateur[]>('/api/animateurs'),
        this.api.get<Stand[]>('/api/stands'),
        this.api.get<Emplacement[]>('/api/emplacements'),
        this.api.get<ContrainteAdHoc[]>('/api/contraintes-ad-hoc'),
        this.api.get<Volumetrie>('/api/planning/volumetrie')
      ]);
    this.typologies.set(typologies);
    this.creneaux.set(creneaux);
    this.groupesCreneaux.set(groupesCreneaux);
    this.animateurs.set(animateurs);
    this.stands.set(stands);
    this.emplacements.set(emplacements);
    this.contraintes.set(contraintes);
    this.volumetrie.set(volumetrie);
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
    await this.reload();
  }

  async remove(resource: string, id: string | number): Promise<void> {
    await this.api.delete(`/api/${resource}/${encodeURIComponent(String(id))}`);
    await this.reload();
  }

  /**
   * Deletes several entities, then refreshes every collection once instead of
   * once per entity. The API is keyed by id (there is no bulk endpoint), so the
   * requests are sent one after the other: it keeps the failure attributable to
   * its own id and spares the server a burst of concurrent writes.
   */
  async removeMany(resource: string, ids: readonly (string | number)[]): Promise<BulkResult> {
    return this.runBulk(ids, (id) => this.api.delete(`/api/${resource}/${encodeURIComponent(String(id))}`));
  }

  /** Same batching as {@link removeMany}, for entities already patched by the caller. */
  async saveMany<T extends { id: string | number }>(resource: string, payloads: readonly T[]): Promise<BulkResult> {
    const parId = new Map<string | number, T>(payloads.map((payload) => [payload.id, payload]));
    return this.runBulk([...parId.keys()], (id) =>
      this.api.put(`/api/${resource}/${encodeURIComponent(String(id))}`, parId.get(id))
    );
  }

  private async runBulk(
    ids: readonly (string | number)[],
    action: (id: string | number) => Promise<unknown>
  ): Promise<BulkResult> {
    const result: BulkResult = { succes: [], echecs: [] };
    for (const id of ids) {
      try {
        await action(id);
        result.succes.push(id);
      } catch (error) {
        result.echecs.push({ id, message: error instanceof Error ? error.message : String(error) });
      }
    }
    await this.reload();
    return result;
  }

  /** Activates a timeslot group and deactivates every other one, then refreshes. */
  async activerGroupeCreneau(id: string): Promise<void> {
    await this.api.put(`/api/groupes-creneaux/${encodeURIComponent(id)}/actif`, {});
    await this.reload();
  }
}
