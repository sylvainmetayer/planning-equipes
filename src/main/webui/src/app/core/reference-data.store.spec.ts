import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { ReferenceDataStore } from './reference-data.store';

/** Answers every collection with an empty list, so `reload()` succeeds. */
class FakeApi {
  get = vi.fn(async (url: string) => (url.endsWith('/volumetrie') ? {} : []));
  // Typed `unknown`: two resources answer `{ <entite>, avertissements }` and
  // the others the bare entity, which is exactly what `save` has to tell apart.
  post = vi.fn(async (): Promise<unknown> => undefined);
  put = vi.fn(async (): Promise<unknown> => undefined);
  delete = vi.fn(async () => undefined);
}

describe('ReferenceDataStore bulk operations', () => {
  let store: ReferenceDataStore;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ReferenceDataStore,
        { provide: ApiService, useValue: api },
      ],
    });
    store = TestBed.inject(ReferenceDataStore);
  });

  /**
   * How many times a collection was re-read. A reload no longer fetches every
   * collection, so the family being written to is what has to be counted — the
   * assertion is still "reloaded once, not once per row".
   */
  function reloadCount(url: string): number {
    return api.get.mock.calls.filter(([called]) => called === url).length;
  }

  it('supprime chaque ligne puis ne recharge qu’une fois', async () => {
    const result = await store.removeMany('stands', ['S1', 'S2']);

    expect(api.delete).toHaveBeenCalledTimes(2);
    expect(api.delete).toHaveBeenCalledWith('/api/stands/S1');
    expect(result.succes).toEqual(['S1', 'S2']);
    expect(reloadCount('/api/stands')).toBe(1);
  });

  it('encode les identifiants dans l’URL', async () => {
    await store.removeMany('emplacements', ['salle des fêtes']);

    expect(api.delete).toHaveBeenCalledWith('/api/emplacements/salle%20des%20f%C3%AAtes');
  });

  // Un refus du serveur sur une ligne ne doit pas interrompre le lot.
  it('poursuit le lot après un échec et le rapporte', async () => {
    api.delete.mockRejectedValueOnce(new Error('encore référencé'));

    const result = await store.removeMany('typologies', ['t1', 't2']);

    expect(api.delete).toHaveBeenCalledTimes(2);
    expect(result.succes).toEqual(['t2']);
    expect(result.echecs).toEqual([{ id: 't1', message: 'encore référencé', concurrente: false }]);
    expect(reloadCount('/api/typologies')).toBe(1);
  });

  it('enregistre chaque ligne par PUT sur son identifiant', async () => {
    const result = await store.saveMany('animateurs', [
      { id: 'alice', manager: true },
      { id: 'bob', manager: true },
    ]);

    expect(api.put).toHaveBeenCalledWith('/api/animateurs/alice', { id: 'alice', manager: true });
    expect(result.succes).toEqual(['alice', 'bob']);
    expect(reloadCount('/api/animateurs')).toBe(1);
  });

  // `/api/animateurs` and `/api/creneaux` answer `{ <entite>, avertissements }`;
  // every other resource still answers the bare entity.
  it('remonte les avertissements portés par la réponse d’écriture', async () => {
    api.post.mockResolvedValueOnce({
      animateur: { id: 'alice' },
      avertissements: [{ type: 'MINEUR_PENDANT_EVENEMENT', message: 'majeur le 2026-07-09' }],
    });

    const { avertissements } = await store.save('animateurs', { id: 'alice' }, null);

    expect(avertissements).toHaveLength(1);
    expect(avertissements[0].type).toBe('MINEUR_PENDANT_EVENEMENT');
  });

  it('rend une liste vide quand la ressource répond l’entité nue', async () => {
    api.put.mockResolvedValueOnce({ id: 't1', nom: 'Ninja' });

    expect((await store.save('typologies', { id: 't1' }, 't1')).avertissements).toEqual([]);
  });

  // Un créneau n'a pas d'identifiant avant son écriture : celui du serveur est
  // le seul que la bulle puisse afficher.
  it('rend l’identifiant généré par le serveur pour une création sans id', async () => {
    api.post.mockResolvedValueOnce({
      creneau: { id: 4242, date: '2026-07-08' },
      avertissements: [],
    });

    expect((await store.save('creneaux', { id: null }, null)).id).toBe(4242);
  });

  it('rend l’identifiant de l’entité nue quand la ressource ne l’enveloppe pas', async () => {
    api.put.mockResolvedValueOnce({ id: 't1', nom: 'Ninja' });

    expect((await store.save('typologies', { id: 't1' }, 't1')).id).toBe('t1');
  });

  it('rend null quand la réponse ne porte aucun identifiant', async () => {
    api.put.mockResolvedValueOnce(undefined);

    expect((await store.save('typologies', { id: 't1' }, 't1')).id).toBeNull();
  });

  it('regroupe les avertissements de tout le lot', async () => {
    api.put
      .mockResolvedValueOnce({
        animateur: { id: 'alice' },
        avertissements: [{ type: 'X', message: 'a' }],
      })
      .mockResolvedValueOnce({
        animateur: { id: 'bob' },
        avertissements: [{ type: 'X', message: 'b' }],
      });

    const result = await store.saveMany('animateurs', [{ id: 'alice' }, { id: 'bob' }]);

    expect(result.avertissements.map((avertissement) => avertissement.message)).toEqual(['a', 'b']);
  });

  describe('rechargement sélectif', () => {
    it('ne recharge que la famille écrite, plus la volumétrie', async () => {
      await store.save('typologies', { id: 't1', nom: 'Ninja' }, 't1');

      const urls = api.get.mock.calls.map(([url]) => url);
      expect(urls).toEqual(expect.arrayContaining(['/api/typologies', '/api/planning/volumetrie']));
      // Renommer une typologie ne doit plus rapatrier 150 animateurs et 65 stands.
      expect(urls).not.toContain('/api/animateurs');
      expect(urls).not.toContain('/api/stands');
      expect(urls).not.toContain('/api/emplacements');
      expect(urls).not.toContain('/api/creneaux');
    });

    it('recharge aussi les contraintes ad hoc, qui peuvent nommer la ligne supprimée', async () => {
      await store.remove('animateurs', 'alice');

      const urls = api.get.mock.calls.map(([url]) => url);
      expect(urls).toContain('/api/animateurs');
      expect(urls).toContain('/api/contraintes-ad-hoc');
      expect(urls).not.toContain('/api/stands');
    });

    it('recharge les stands quand un emplacement bouge, puisqu’ils le désignent', async () => {
      await store.remove('emplacements', 'salle-1');

      const urls = api.get.mock.calls.map(([url]) => url);
      expect(urls).toContain('/api/emplacements');
      expect(urls).toContain('/api/stands');
    });

    it('recharge tout quand aucune famille n’est nommée : un import invalide vraiment tout', async () => {
      await store.reload();

      const urls = api.get.mock.calls.map(([url]) => url);
      for (const url of [
        '/api/typologies',
        '/api/creneaux',
        '/api/animateurs',
        '/api/stands',
        '/api/emplacements',
        '/api/contraintes-ad-hoc',
        '/api/planning/volumetrie',
      ]) {
        expect(urls).toContain(url);
      }
    });

    it('retombe sur un rechargement complet pour une ressource inconnue, quitte à être lent', async () => {
      await store.remove('ressource-inventee', 'x');

      const urls = api.get.mock.calls.map(([url]) => url);
      expect(urls).toContain('/api/animateurs');
      expect(urls).toContain('/api/stands');
      expect(urls).toContain('/api/typologies');
    });
  });
});
