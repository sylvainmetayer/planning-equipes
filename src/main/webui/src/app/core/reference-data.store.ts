// Reference data shared by the CRUD editors and by the pages that must refresh
// them after a bulk change (sample load, CSV import, SQL dump replay).

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Animateur, ContrainteAdHoc, Creneau, Emplacement, Stand, TypologieItem } from './models';

@Injectable({ providedIn: 'root' })
export class ReferenceDataStore {
  readonly typologies = signal<TypologieItem[]>([]);
  readonly creneaux = signal<Creneau[]>([]);
  readonly animateurs = signal<Animateur[]>([]);
  readonly stands = signal<Stand[]>([]);
  readonly emplacements = signal<Emplacement[]>([]);
  readonly contraintes = signal<ContrainteAdHoc[]>([]);

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    const [typologies, creneaux, animateurs, stands, emplacements, contraintes] = await Promise.all([
      this.api.get<TypologieItem[]>('/api/typologies'),
      this.api.get<Creneau[]>('/api/creneaux'),
      this.api.get<Animateur[]>('/api/animateurs'),
      this.api.get<Stand[]>('/api/stands'),
      this.api.get<Emplacement[]>('/api/emplacements'),
      this.api.get<ContrainteAdHoc[]>('/api/contraintes-ad-hoc')
    ]);
    this.typologies.set(typologies);
    this.creneaux.set(creneaux);
    this.animateurs.set(animateurs);
    this.stands.set(stands);
    this.emplacements.set(emplacements);
    this.contraintes.set(contraintes);
  }

  /** Creates or updates an entity, then refreshes every collection. */
  async save<T extends { id: string }>(resource: string, payload: T, editingId: string | null): Promise<void> {
    if (editingId) {
      await this.api.put(`/api/${resource}/${encodeURIComponent(editingId)}`, payload);
    } else {
      await this.api.post(`/api/${resource}`, payload);
    }
    await this.reload();
  }

  async remove(resource: string, id: string): Promise<void> {
    await this.api.delete(`/api/${resource}/${encodeURIComponent(id)}`);
    await this.reload();
  }
}
