import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionExpireeError } from './api.service';
import { NotificationService } from './notification.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import { ReferenceCrudService } from './reference-crud.service';
import { ReferenceDataStore } from './reference-data.store';
import { ReferenceUsageService } from './reference-usage.service';
import { ConfirmService } from '../shared/confirm-dialog';

class FakeStore {
  reload = vi.fn(async () => undefined);
  save = vi.fn(async (_resource: string, _payload: unknown, _editingId: unknown) => undefined);
  remove = vi.fn(async (_resource: string, _id: unknown) => undefined);
  removeMany = vi.fn(async (_resource: string, ids: readonly (string | number)[]) => ({
    succes: [...ids],
    echecs: [] as { id: string | number; message: string }[]
  }));
  saveMany = vi.fn(async (_resource: string, payloads: readonly { id: string | number }[]) => ({
    succes: payloads.map((payload) => payload.id),
    echecs: [] as { id: string | number; message: string }[]
  }));
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

class FakeUsages {
  phrase = '';
  describe = vi.fn(async (_resource: string, _ids: readonly (string | number)[]) => this.phrase);
}

describe('ReferenceCrudService', () => {
  let service: ReferenceCrudService;
  let store: FakeStore;
  let notifications: FakeNotifications;
  let confirm: FakeConfirm;
  let resolution: FakeResolution;
  let usages: FakeUsages;

  beforeEach(() => {
    store = new FakeStore();
    notifications = new FakeNotifications();
    confirm = new FakeConfirm();
    resolution = new FakeResolution();
    usages = new FakeUsages();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ReferenceCrudService,
        { provide: ReferenceDataStore, useValue: store },
        { provide: NotificationService, useValue: notifications },
        { provide: ConfirmService, useValue: confirm },
        { provide: PlanningResolutionStore, useValue: resolution },
        { provide: ReferenceUsageService, useValue: usages }
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
     * Un 401 signifie « session expirée » : l'intercepteur redirige déjà vers
     * /login, un toast « Échec de la requête (code 401) » par appel en vol ne
     * ferait qu'empiler du bruit technique par-dessus la redirection.
     */
    it('ne notifie pas une session expirée', async () => {
      store.save.mockRejectedValueOnce(new SessionExpireeError());

      const ok = await service.save('stands', { id: 'S1' }, null, 'le stand');

      expect(ok).toBe(false);
      expect(notifications.notify).not.toHaveBeenCalled();
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

    // Le décompte serveur est ce que la ligne du tableau ne montre pas : ni le
    // planning persisté, ni les ajustements manuels, ni les verrous.
    it('affiche dans la confirmation l\'impact chiffré rendu par le serveur', async () => {
      usages.phrase = 'Référencé par 42 affectation(s).';

      await service.remove('stands', 'S1', 'le stand');

      expect(usages.describe).toHaveBeenCalledWith('stands', ['S1']);
      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ message: expect.stringContaining('Référencé par 42 affectation(s).') })
      );
    });

    // Précédent des typologies : la page calcule ses usages côté client et les
    // passe en `detail`. Ce chemin ne doit pas être doublé d'un appel serveur.
    it('laisse le détail fourni par la page primer sur le décompte serveur', async () => {
      usages.phrase = 'décompte serveur';

      await service.remove('typologies', 'T1', 'la typologie', 'détail de la page');

      expect(usages.describe).not.toHaveBeenCalled();
      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ message: expect.stringContaining('détail de la page') })
      );
    });

    // Le décompte informe, il ne verrouille rien : sans phrase à afficher, la
    // confirmation reste posée et la suppression part quand même.
    it('supprime quand même sans décompte à afficher', async () => {
      usages.phrase = '';

      const ok = await service.remove('stands', 'S1', 'le stand');

      expect(ok).toBe(true);
      expect(store.remove).toHaveBeenCalledWith('stands', 'S1');
    });
  });

  describe('removeMany', () => {
    it('supprime toute la sélection après une seule confirmation', async () => {
      const supprimes = await service.removeMany('stands', ['S1', 'S2'], 'stands');

      expect(supprimes).toBe(2);
      expect(confirm.ask).toHaveBeenCalledTimes(1);
      expect(store.removeMany).toHaveBeenCalledWith('stands', ['S1', 'S2']);
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('ne touche à rien quand la confirmation est refusée', async () => {
      confirm.reponse = false;

      expect(await service.removeMany('stands', ['S1'], 'stands')).toBe(0);
      expect(store.removeMany).not.toHaveBeenCalled();
    });

    it('ne demande rien sur une sélection vide', async () => {
      expect(await service.removeMany('stands', [], 'stands')).toBe(0);
      expect(confirm.ask).not.toHaveBeenCalled();
      expect(usages.describe).not.toHaveBeenCalled();
    });

    // Un total agrégé, jamais un détail ligne par ligne : une seule requête
    // porte toute la sélection.
    it('agrège l\'impact de toute la sélection en un seul appel', async () => {
      usages.phrase = 'Référencé par 12 affectation(s).';

      await service.removeMany('stands', ['S1', 'S2'], 'stands');

      expect(usages.describe).toHaveBeenCalledTimes(1);
      expect(usages.describe).toHaveBeenCalledWith('stands', ['S1', 'S2']);
      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ message: expect.stringContaining('Référencé par 12 affectation(s).') })
      );
    });

    // Une ligne refusée par le serveur (typologie encore utilisée, ...) ne doit
    // pas annuler la suppression des autres : le lot continue et le rapport
    // détaille ce qui reste.
    it('rapporte les échecs sans perdre les suppressions réussies', async () => {
      store.removeMany.mockResolvedValueOnce({
        succes: ['S1'],
        echecs: [{ id: 'S2', message: 'encore référencé' }]
      });

      const supprimes = await service.removeMany('stands', ['S1', 'S2'], 'stands');

      expect(supprimes).toBe(1);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: expect.stringContaining('encore référencé') })
      );
    });
  });

  describe('saveMany', () => {
    it('enregistre toute la sélection sans confirmation', async () => {
      const enregistres = await service.saveMany('animateurs', [{ id: 'a' }, { id: 'b' }], 'animateurs');

      expect(enregistres).toBe(2);
      expect(confirm.ask).not.toHaveBeenCalled();
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('retourne 0 et notifie quand le store échoue', async () => {
      store.saveMany.mockRejectedValueOnce(new Error('indisponible'));

      expect(await service.saveMany('animateurs', [{ id: 'a' }], 'animateurs')).toBe(0);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'indisponible' })
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

  describe('reportError', () => {
    it('names a vanished row instead of labelling it "Erreur"', () => {
      service.reportError(new ApiError(404, 'notFound', 'Stand introuvable'));

      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Cette donnée n'existe plus", message: 'Stand introuvable' })
      );
    });

    it('tells a concurrent edit apart from a rejected form', () => {
      service.reportError(new ApiError(409, 'conflict', 'Modifié'));
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Modifiée entre-temps' })
      );

      service.reportError(new ApiError(400, 'invalid', 'Champ manquant'));
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Saisie refusée' })
      );
    });

    it('keeps the generic title for a technical failure and for a plain Error', () => {
      service.reportError(new ApiError(500, 'technical', 'Boum'));
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ title: 'Erreur' }));

      service.reportError(new Error('Boum'));
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ title: 'Erreur' }));
    });
  });
});
