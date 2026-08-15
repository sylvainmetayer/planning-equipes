import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from './notification.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import { ReferenceCrudService } from './reference-crud.service';
import { ReferenceDataStore } from './reference-data.store';
import { ConfirmService } from '../shared/confirm-dialog';

class FakeStore {
  reload = vi.fn(async () => undefined);
  save = vi.fn(async (_resource: string, _payload: unknown, _editingId: unknown) => undefined);
  remove = vi.fn(async (_resource: string, _id: unknown) => undefined);
}

class FakeNotifications {
  notify = vi.fn((_notification: unknown) => undefined);
}

class FakeConfirm {
  reponse = true;
  ask = vi.fn(async (_options: unknown) => this.reponse);
}

class FakeResolution {
  reload = vi.fn(async () => undefined);
}

describe('ReferenceCrudService', () => {
  let service: ReferenceCrudService;
  let store: FakeStore;
  let notifications: FakeNotifications;
  let confirm: FakeConfirm;
  let resolution: FakeResolution;

  beforeEach(() => {
    store = new FakeStore();
    notifications = new FakeNotifications();
    confirm = new FakeConfirm();
    resolution = new FakeResolution();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ReferenceCrudService,
        { provide: ReferenceDataStore, useValue: store },
        { provide: NotificationService, useValue: notifications },
        { provide: ConfirmService, useValue: confirm },
        { provide: PlanningResolutionStore, useValue: resolution }
      ]
    });
    service = TestBed.inject(ReferenceCrudService);
  });

  describe('save', () => {
    it('persiste et signale une création', async () => {
      const ok = await service.save('stands', { id: 'S1' }, null, 'le stand');

      expect(ok).toBe(true);
      expect(store.save).toHaveBeenCalledWith('stands', { id: 'S1' }, null);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'success' })
      );
    });

    // Toutes les entités sauf les créneaux sont clés par un identifiant saisi
    // par l'utilisateur : sans lui, la sauvegarde n'a pas de sens et ne doit
    // pas atteindre le serveur.
    it('refuse un identifiant manquant sans appeler le store', async () => {
      const ok = await service.save('stands', { id: '' }, null, 'le stand');

      expect(ok).toBe(false);
      expect(store.save).not.toHaveBeenCalled();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error' })
      );
    });

    // Les créneaux passent requireId: false, leur id étant généré côté serveur.
    it('accepte un identifiant absent quand requireId vaut false', async () => {
      const ok = await service.save('creneaux', { id: null }, null, 'le créneau', { requireId: false });

      expect(ok).toBe(true);
      expect(store.save).toHaveBeenCalled();
    });

    it('retourne false et notifie quand le store échoue', async () => {
      store.save.mockRejectedValueOnce(new Error('conflit'));

      const ok = await service.save('stands', { id: 'S1' }, null, 'le stand');

      expect(ok).toBe(false);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'conflit' })
      );
    });

    /**
     * Le rafraîchissement de l'indicateur « données modifiées depuis la
     * dernière résolution » est un simple indice : son échec ne doit jamais
     * faire échouer l'enregistrement que l'utilisateur vient de réussir.
     */
    it('reste un succès même si le rafraîchissement de résolution échoue', async () => {
      resolution.reload.mockRejectedValueOnce(new Error('réseau'));

      const ok = await service.save('stands', { id: 'S1' }, null, 'le stand');

      expect(ok).toBe(true);
      expect(notifications.notify).not.toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error' })
      );
    });
  });

  describe('remove', () => {
    it('supprime après confirmation', async () => {
      const ok = await service.remove('stands', 'S1', 'le stand');

      expect(ok).toBe(true);
      expect(store.remove).toHaveBeenCalledWith('stands', 'S1');
    });

    it('n\'appelle pas le store quand la confirmation est refusée', async () => {
      confirm.reponse = false;

      const ok = await service.remove('stands', 'S1', 'le stand');

      expect(ok).toBe(false);
      expect(store.remove).not.toHaveBeenCalled();
      expect(notifications.notify).not.toHaveBeenCalled();
    });

    it('retourne false et notifie quand la suppression échoue', async () => {
      store.remove.mockRejectedValueOnce(new Error('référencé ailleurs'));

      const ok = await service.remove('stands', 'S1', 'le stand');

      expect(ok).toBe(false);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'référencé ailleurs' })
      );
    });
  });

  describe('reload', () => {
    it('rapporte l\'erreur sans la propager à la vue', async () => {
      store.reload.mockRejectedValueOnce(new Error('indisponible'));

      await expect(service.reload()).resolves.toBeUndefined();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'indisponible' })
      );
    });
  });
});
