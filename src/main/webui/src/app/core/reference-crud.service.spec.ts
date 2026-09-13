import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionExpireeError } from './api.service';
import { Avertissement } from './models';
import { NotificationService } from './notification.service';
import { PlanningResolutionStore } from './planning-resolution.store';
import { ReferenceCrudService } from './reference-crud.service';
import { ReferenceDataStore } from './reference-data.store';
import { ReferenceUsageService } from './reference-usage.service';
import { ConfirmData, ConfirmService } from '../shared/confirm-dialog';

class FakeStore {
  reload = vi.fn(async () => undefined);
  save = vi.fn(async (_resource: string, _payload: unknown, _editingId: unknown) => ({
    id: null as string | number | null,
    avertissements: [] as Avertissement[],
  }));
  remove = vi.fn(async (_resource: string, _id: unknown) => undefined);
  removeMany = vi.fn(async (_resource: string, ids: readonly (string | number)[]) => ({
    succes: [...ids],
    echecs: [] as { id: string | number; message: string }[],
    avertissements: [] as Avertissement[],
  }));
  saveMany = vi.fn(async (_resource: string, payloads: readonly { id: string | number }[]) => ({
    succes: payloads.map((payload) => payload.id),
    echecs: [] as { id: string | number; message: string }[],
    avertissements: [] as Avertissement[],
  }));
}

class FakeNotifications {
  notify = vi.fn((_notification: unknown) => undefined);
}

class FakeConfirm {
  reponse = true;
  /** `null` is the dismissal (Escape, backdrop): neither confirm nor cancel. */
  reponseTroisEtats: boolean | null = null;
  ask = vi.fn(async (_options: ConfirmData) => this.reponse);
  askThreeWay = vi.fn(async (_options: ConfirmData) => this.reponseTroisEtats);

  /** The data of the nth dialog opened, for the assertions on its detail. */
  demande(index = 0): ConfirmData {
    return this.ask.mock.calls[index][0];
  }

  /** Same, for the three-way dialog. */
  demandeTroisEtats(index = 0): ConfirmData {
    return this.askThreeWay.mock.calls[index][0];
  }
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
        { provide: ReferenceUsageService, useValue: usages },
      ],
    });
    service = TestBed.inject(ReferenceCrudService);
  });

  describe('save', () => {
    it('persiste et signale une création', async () => {
      const ok = await service.save('stands', { id: 'S1' }, null, 'le stand');

      expect(ok).toBe(true);
      expect(store.save).toHaveBeenCalledWith('stands', { id: 'S1' }, null);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'success' }),
      );
    });

    // Un avertissement n'est pas un refus : la ligne est écrite, `save` rend
    // toujours true, et le message reste affiché (timeout 0) parce qu'il nomme
    // des dates que l'utilisateur doit aller vérifier ailleurs.
    it('signale un avertissement sans faire échouer la sauvegarde', async () => {
      store.save.mockResolvedValueOnce({
        id: 'A1',
        avertissements: [{ type: 'MINEUR_PENDANT_EVENEMENT', message: 'majeur le 2026-07-09' }],
      });

      const ok = await service.save('animateurs', { id: 'A1' }, null, "l'animateur");

      expect(ok).toBe(true);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: 'warning',
          timeout: 0,
          message: expect.stringContaining('majeur le 2026-07-09'),
        }),
      );
      expect(notifications.notify).toHaveBeenCalledTimes(1);
    });

    // « Voir la fiche » mène à l'écran de la ressource : son nom d'API, sauf
    // pour les ajustements, dont la route ne porte pas le même nom.
    it.each([
      ['animateurs', 'A1', '/animateurs'],
      ['contraintes-ad-hoc', 'AH1', '/ad-hoc-constraints'],
    ])('relie l’avertissement de %s à la fiche sur son écran', async (resource, id, route) => {
      store.save.mockResolvedValueOnce({
        id,
        avertissements: [{ type: 'AFFECTATION_FORCEE_JOUR_INDISPONIBLE', message: 'indisponible' }],
      });

      await service.save(resource, { id }, null, 'Fiche');

      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({
          lien: expect.objectContaining({ route, queryParams: { edit: id } }),
        }),
      );
    });

    it('détaille au plus trois avertissements puis compte le reste', async () => {
      store.save.mockResolvedValueOnce({
        id: 'A1',
        avertissements: ['un', 'deux', 'trois', 'quatre'].map((message) => ({
          type: 'INDISPONIBILITE_HORS_EVENEMENT' as const,
          message,
        })),
      });

      await service.save('animateurs', { id: 'A1' }, null, "l'animateur");

      const notification = notifications.notify.mock.calls[0][0] as { message: string };
      expect(notification.message).toContain('un');
      expect(notification.message).toContain('trois');
      expect(notification.message).not.toContain('quatre');
      expect(notification.message).toContain('1');
    });

    // Toutes les entités sauf les créneaux sont clés par un identifiant saisi
    // par l'utilisateur : sans lui, la sauvegarde n'a pas de sens et ne doit
    // pas atteindre le serveur.
    it('refuse un identifiant manquant sans appeler le store', async () => {
      const ok = await service.save('stands', { id: '' }, null, 'le stand');

      expect(ok).toBe(false);
      expect(store.save).not.toHaveBeenCalled();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error' }),
      );
    });

    // Les créneaux passent requireId: false, leur id étant généré côté serveur.
    it('accepte un identifiant absent quand requireId vaut false', async () => {
      const ok = await service.save('creneaux', { id: null }, null, 'le créneau', {
        requireId: false,
      });

      expect(ok).toBe(true);
      expect(store.save).toHaveBeenCalled();
    });

    // Le payload d'une création de créneau n'a pas d'id : interpoler le sien
    // affichait « Créneau undefined » dans une bulle qui reste à l'écran.
    it('affiche l’identifiant rendu par le serveur, pas celui du payload absent', async () => {
      store.save.mockResolvedValueOnce({
        id: 4242,
        avertissements: [{ type: 'CRENEAU_DEBORDE_OUVERTURE_STANDS', message: 'déborde' }],
      });

      await service.save('creneaux', { id: null }, null, 'Créneau', { requireId: false });

      const notification = notifications.notify.mock.calls[0][0] as { title: string };
      expect(notification.title).toContain('4242');
      expect(notification.title).not.toContain('undefined');
    });

    it('affiche aussi l’identifiant du serveur sur la bulle de succès', async () => {
      store.save.mockResolvedValueOnce({ id: 4242, avertissements: [] });

      await service.save('creneaux', { id: null }, null, 'Créneau', { requireId: false });

      const notification = notifications.notify.mock.calls[0][0] as { title: string };
      expect(notification.title).toContain('4242');
      expect(notification.title).not.toContain('undefined');
    });

    /**
     * Toute notification est écrite dans un journal `localStorage` de 200
     * entrées qui survit à la déconnexion. Une phrase disant qu'une personne
     * nommée est mineure n'a rien à y faire (docs/rgpd.md §7) : la bulle la
     * montre, le journal ne la garde pas.
     */
    it('garde hors du journal la phrase qui dit qu’une personne est mineure', async () => {
      store.save.mockResolvedValueOnce({
        id: 'A1',
        avertissements: [
          { type: 'MINEUR_PENDANT_EVENEMENT', message: "L'animateur A1 est mineur" },
          { type: 'INDISPONIBILITE_HORS_EVENEMENT', message: 'Indisponibilité le 2027-08-15' },
        ],
      });

      await service.save('animateurs', { id: 'A1' }, null, "l'animateur");

      const notification = notifications.notify.mock.calls[0][0] as {
        message: string;
        messageJournal: string;
      };
      expect(notification.message).toContain('est mineur');
      expect(notification.messageJournal).not.toContain('est mineur');
      expect(notification.messageJournal).toContain('2027-08-15');
    });

    // Issue #362 : le 409 « modification concurrente » n'est pas un échec
    // comme les autres, il a une réponse. Écraser renvoie le même payload sans
    // sa précondition ; recharger ne réécrit rien, rafraîchit le store et rend
    // true pour que le formulaire se ferme sur la version de l'autre session.
    describe('modification concurrente', () => {
      const conflit = () =>
        new ApiError(409, 'conflict', 'Modifiée par une autre session', 'MODIFICATION_CONCURRENTE');

      it('propose d’écraser, puis renvoie le payload sans précondition', async () => {
        store.save.mockRejectedValueOnce(conflit());
        confirm.reponseTroisEtats = true;

        const ok = await service.save(
          'stands',
          { id: 'S1', modifieLe: '2026-09-06T10:00:00Z' },
          'S1',
          'le stand',
        );

        expect(ok).toBe(true);
        expect(confirm.demandeTroisEtats()).toEqual(
          expect.objectContaining({
            confirmLabel: 'Écraser quand même',
            cancelLabel: 'Recharger',
            danger: true,
          }),
        );
        expect(store.save).toHaveBeenCalledTimes(2);
        expect(store.save).toHaveBeenLastCalledWith('stands', { id: 'S1', modifieLe: null }, 'S1');
        expect(notifications.notify).toHaveBeenCalledWith(
          expect.objectContaining({ variant: 'success' }),
        );
      });

      it('recharge sans réécrire quand l’utilisateur le choisit, et laisse le formulaire se fermer', async () => {
        store.save.mockRejectedValueOnce(conflit());
        confirm.reponseTroisEtats = false;

        const ok = await service.save(
          'stands',
          { id: 'S1', modifieLe: '2026-09-06T10:00:00Z' },
          'S1',
          'le stand',
        );

        expect(ok).toBe(true);
        expect(store.save).toHaveBeenCalledTimes(1);
        expect(store.reload).toHaveBeenCalled();
        expect(notifications.notify).toHaveBeenCalledWith(
          expect.objectContaining({
            variant: 'warning',
            title: expect.stringContaining('rechargée'),
          }),
        );
        expect(notifications.notify).not.toHaveBeenCalledWith(
          expect.objectContaining({ variant: 'success' }),
        );
      });

      // Écarter le dialogue (Échap, clic à côté) n'est ni écraser ni
      // recharger : c'est le seul dialogue de l'application dont le bouton
      // d'annulation détruit la saisie, donc le geste « je ne décide pas » ne
      // doit rien détruire — le formulaire reste ouvert, tel qu'il est.
      it('ne perd rien quand l’utilisateur écarte le dialogue sans choisir', async () => {
        store.save.mockRejectedValueOnce(conflit());
        confirm.reponseTroisEtats = null;

        const ok = await service.save(
          'stands',
          { id: 'S1', modifieLe: '2026-09-06T10:00:00Z' },
          'S1',
          'le stand',
        );

        expect(ok).toBe(false);
        expect(store.save).toHaveBeenCalledTimes(1);
        expect(store.reload).not.toHaveBeenCalled();
        expect(notifications.notify).toHaveBeenCalledWith(
          expect.objectContaining({ variant: 'error' }),
        );
      });

      it('date le conflit dans le fuseau du lecteur, jamais dans celui du serveur', async () => {
        store.save.mockRejectedValueOnce(
          new ApiError(
            409,
            'conflict',
            'Ce stand a été modifié',
            'MODIFICATION_CONCURRENTE',
            '2026-09-06T15:34:00Z',
          ),
        );
        confirm.reponseTroisEtats = null;

        await service.save(
          'stands',
          { id: 'S1', modifieLe: '2026-09-06T10:00:00Z' },
          'S1',
          'le stand',
        );

        expect(confirm.demandeTroisEtats().message).toContain(
          new Date('2026-09-06T15:34:00Z').toLocaleString('fr-FR'),
        );
      });

      it('laisse un 409 sans code suivre le chemin d’erreur ordinaire', async () => {
        store.save.mockRejectedValueOnce(new ApiError(409, 'conflict', 'Solveur occupé'));

        const ok = await service.save('stands', { id: 'S1' }, 'S1', 'le stand');

        expect(ok).toBe(false);
        expect(confirm.ask).not.toHaveBeenCalled();
        expect(notifications.notify).toHaveBeenCalledWith(
          expect.objectContaining({ variant: 'error' }),
        );
      });
    });

    it('retourne false et notifie quand le store échoue', async () => {
      store.save.mockRejectedValueOnce(new Error('conflit'));

      const ok = await service.save('stands', { id: 'S1' }, null, 'le stand');

      expect(ok).toBe(false);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'conflit' }),
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
        expect.objectContaining({ variant: 'error' }),
      );
    });
  });

  describe('remove', () => {
    it('supprime après confirmation', async () => {
      const ok = await service.remove('stands', 'S1', 'le stand');

      expect(ok).toBe(true);
      expect(store.remove).toHaveBeenCalledWith('stands', 'S1');
    });

    it("n'appelle pas le store quand la confirmation est refusée", async () => {
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
        expect.objectContaining({ variant: 'error', message: 'référencé ailleurs' }),
      );
    });

    // Le décompte serveur est ce que la ligne du tableau ne montre pas : ni le
    // planning persisté, ni les ajustements manuels, ni les verrous.
    it("affiche dans la confirmation l'impact chiffré rendu par le serveur", async () => {
      usages.phrase = 'Référencé par 42 affectation(s).';

      await service.remove('stands', 'S1', 'le stand');

      expect(usages.describe).toHaveBeenCalledWith('stands', ['S1']);
      await expect(confirm.demande().detail).resolves.toBe('Référencé par 42 affectation(s).');
    });

    /**
     * La modale doit s'ouvrir sur le clic, pas après l'aller-retour : sinon le
     * bouton reste actif alors que rien n'est affiché, et un double-clic empile
     * deux dialogues puis deux suppressions — la seconde échouant sur une ligne
     * que la première a déjà retirée.
     */
    it('ouvre la confirmation sans attendre la réponse du décompte', async () => {
      let repondre: (phrase: string) => void = () => undefined;
      usages.describe.mockReturnValueOnce(
        new Promise<string>((resolve) => {
          repondre = resolve;
        }),
      );

      const suppression = service.remove('stands', 'S1', 'le stand');

      expect(confirm.ask).toHaveBeenCalledTimes(1);
      repondre('Référencé par 3 affectation(s).');
      await expect(suppression).resolves.toBe(true);
    });

    // Précédent des typologies : la page calcule ses usages côté client et les
    // passe en `detail`. Ce chemin ne doit pas être doublé d'un appel serveur.
    it('laisse le détail fourni par la page primer sur le décompte serveur', async () => {
      usages.phrase = 'décompte serveur';

      await service.remove('typologies', 'T1', 'la typologie', 'détail de la page');

      expect(usages.describe).not.toHaveBeenCalled();
      await expect(confirm.demande().detail).resolves.toBe('détail de la page');
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
      const removed = await service.removeMany('stands', ['S1', 'S2'], 'stands');

      expect(removed).toBe(2);
      expect(confirm.ask).toHaveBeenCalledTimes(1);
      expect(store.removeMany).toHaveBeenCalledWith('stands', ['S1', 'S2']);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'success' }),
      );
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
    it("agrège l'impact de toute la sélection en un seul appel", async () => {
      usages.phrase = 'Référencé par 12 affectation(s).';

      await service.removeMany('stands', ['S1', 'S2'], 'stands');

      expect(usages.describe).toHaveBeenCalledTimes(1);
      expect(usages.describe).toHaveBeenCalledWith('stands', ['S1', 'S2']);
      await expect(confirm.demande().detail).resolves.toBe('Référencé par 12 affectation(s).');
    });

    // Une ligne refusée par le serveur (typologie encore utilisée, ...) ne doit
    // pas annuler la suppression des autres : le lot continue et le rapport
    // détaille ce qui reste.
    it('rapporte les échecs sans perdre les suppressions réussies', async () => {
      store.removeMany.mockResolvedValueOnce({
        succes: ['S1'],
        echecs: [{ id: 'S2', message: 'encore référencé' }],
        avertissements: [],
      });

      const removed = await service.removeMany('stands', ['S1', 'S2'], 'stands');

      expect(removed).toBe(1);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: 'error',
          message: expect.stringContaining('encore référencé'),
        }),
      );
    });
  });

  describe('saveMany', () => {
    it('enregistre toute la sélection sans confirmation', async () => {
      const enregistres = await service.saveMany(
        'animateurs',
        [{ id: 'a' }, { id: 'b' }],
        'animateurs',
      );

      expect(enregistres).toBe(2);
      expect(confirm.ask).not.toHaveBeenCalled();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'success' }),
      );
    });

    // Un lot de cinquante lignes doit ouvrir une seule bulle, pas cinquante.
    it('regroupe les avertissements du lot en une seule bulle', async () => {
      store.saveMany.mockResolvedValueOnce({
        succes: ['a', 'b'],
        echecs: [],
        avertissements: [
          { type: 'MINEUR_PENDANT_EVENEMENT', message: 'a est mineur' },
          { type: 'MINEUR_PENDANT_EVENEMENT', message: 'b est mineur' },
        ],
      });

      const enregistres = await service.saveMany(
        'animateurs',
        [{ id: 'a' }, { id: 'b' }],
        'animateurs',
      );

      expect(enregistres).toBe(2);
      expect(notifications.notify).toHaveBeenCalledTimes(1);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'warning', timeout: 0 }),
      );
    });

    /**
     * Une ligne refusée (un solve tient le solveur) ne doit pas emporter les
     * avertissements des lignes réellement écrites : la bulle est prise par le
     * refus, alors le reste part au journal des notifications.
     */
    it('conserve les avertissements des lignes écrites quand une ligne échoue', async () => {
      store.saveMany.mockResolvedValueOnce({
        succes: ['a'],
        echecs: [{ id: 'b', message: 'résolution en cours' }],
        avertissements: [
          { type: 'INDISPONIBILITE_HORS_EVENEMENT', message: 'Indisponibilité le 2027-08-15' },
          { type: 'MINEUR_PENDANT_EVENEMENT', message: "L'animateur a est mineur" },
        ],
      });

      await service.saveMany('animateurs', [{ id: 'a' }, { id: 'b' }], 'animateurs');

      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: 'error',
          message: expect.stringContaining('résolution en cours'),
        }),
      );
      const journal = notifications.notify.mock.calls[1][0] as { message: string; silent: boolean };
      expect(journal.silent).toBe(true);
      expect(journal.message).toContain('2027-08-15');
      expect(journal.message).not.toContain('est mineur');
    });

    it('retourne 0 et notifie quand le store échoue', async () => {
      store.saveMany.mockRejectedValueOnce(new Error('indisponible'));

      expect(await service.saveMany('animateurs', [{ id: 'a' }], 'animateurs')).toBe(0);
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'indisponible' }),
      );
    });
  });

  describe('reload', () => {
    it("rapporte l'erreur sans la propager à la vue", async () => {
      store.reload.mockRejectedValueOnce(new Error('indisponible'));

      await expect(service.reload()).resolves.toBeUndefined();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'error', message: 'indisponible' }),
      );
    });
  });

  describe('reportError', () => {
    it('names a vanished row instead of labelling it "Erreur"', () => {
      service.reportError(new ApiError(404, 'notFound', 'Stand introuvable'));

      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({
          title: "Cette donnée n'existe plus",
          message: 'Stand introuvable',
        }),
      );
    });

    it('tells a concurrent edit apart from a rejected form', () => {
      service.reportError(new ApiError(409, 'conflict', 'Modifié'));
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Modifiée entre-temps' }),
      );

      service.reportError(new ApiError(400, 'invalid', 'Champ manquant'));
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Saisie refusée' }),
      );
    });

    it('keeps the generic title for a technical failure and for a plain Error', () => {
      service.reportError(new ApiError(500, 'technical', 'Boum'));
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Erreur' }),
      );

      service.reportError(new Error('Boum'));
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Erreur' }),
      );
    });
  });
});
