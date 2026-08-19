import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { EspaceAnimateurService } from './espace-animateur.service';
import type { DemandeEchangeView, EspaceAnimateurView } from './models';

function vue(): EspaceAnimateurView {
  return {
    animateurId: 'A1',
    prenom: 'Alice',
    nom: 'Martin',
    planningResoluLe: null,
    groupeCreneauNom: null,
    foireOuverte: true,
    postes: [],
    collegues: []
  };
}

function demande(id: string): DemandeEchangeView {
  return {
    id,
    creneauId: 1,
    date: '2026-07-10',
    heureDebut: '10:00',
    heureFin: '12:00',
    standId: 'S1',
    standNom: 'Stand un',
    demandeurId: 'A1',
    demandeurNom: 'Alice Martin',
    cibleId: 'A2',
    cibleNom: 'Bruno Petit',
    motif: null,
    statut: 'PROPOSEE',
    prevalidationOk: true,
    contraintesViolees: [],
    commentaireAdmin: null,
    horsGroupe: false,
    groupeCreneauNom: null,
    creeLe: '2026-07-01T10:00:00Z',
    decideLe: null
  };
}

class FakeApi {
  getPreservingHttpError = vi.fn(async (_url: string): Promise<unknown> => null);
  post = vi.fn(async (_url: string, _body: unknown): Promise<unknown> => null);
}

describe('EspaceAnimateurService', () => {
  let service: EspaceAnimateurService;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        EspaceAnimateurService,
        { provide: ApiService, useValue: api }
      ]
    });
    service = TestBed.inject(EspaceAnimateurService);
  });

  it('charge la vue et les demandes du jeton, et vide toute erreur passée', async () => {
    api.getPreservingHttpError.mockImplementation(async (url: string) =>
      url.endsWith('/demandes') ? [demande('D1')] : vue()
    );

    await service.charger('jeton-1');

    expect(api.getPreservingHttpError).toHaveBeenCalledWith('/api/espace-animateur/jeton-1');
    expect(api.getPreservingHttpError).toHaveBeenCalledWith('/api/espace-animateur/jeton-1/demandes');
    expect(service.jeton()).toBe('jeton-1');
    expect(service.vue()?.animateurId).toBe('A1');
    expect(service.demandes().map((d) => d.id)).toEqual(['D1']);
    expect(service.erreur()).toBeNull();
    expect(service.chargement()).toBe(false);
  });

  it('un échec de chargement pose le message et remet la vue à zéro', async () => {
    api.getPreservingHttpError.mockImplementation(async (url: string) =>
      url.endsWith('/demandes') ? [demande('D1')] : vue()
    );
    await service.charger('jeton-1');

    api.getPreservingHttpError.mockRejectedValue(new Error('Lien inconnu ou expiré'));
    await service.charger('jeton-perime');

    expect(service.erreur()).toBe('Lien inconnu ou expiré');
    expect(service.vue()).toBeNull();
    expect(service.demandes()).toEqual([]);
    expect(service.chargement()).toBe(false);
  });

  it("un 401 bascule en « authentification requise » plutôt qu'en erreur", async () => {
    api.getPreservingHttpError.mockRejectedValue(new HttpErrorResponse({ status: 401 }));

    await service.charger('jeton-1');

    expect(service.authRequise()).toBe(true);
    expect(service.erreur()).toBeNull();
    expect(service.vue()).toBeNull();
  });

  it("valider le code ouvre la session puis recharge l'espace", async () => {
    service.jeton.set('jeton-1');
    api.getPreservingHttpError.mockImplementation(async (url: string) =>
      url.endsWith('/demandes') ? [demande('D1')] : vue()
    );

    await service.validerCode('123456');

    expect(api.post).toHaveBeenCalledWith('/api/espace-animateur/jeton-1/session', { code: '123456' });
    expect(service.vue()?.animateurId).toBe('A1');
    expect(service.authRequise()).toBe(false);
  });

  it('la soumission poste le lot et insère les demandes stockées en tête de liste', async () => {
    api.getPreservingHttpError.mockImplementation(async (url: string) =>
      url.endsWith('/demandes') ? [demande('ANCIENNE')] : vue()
    );
    await service.charger('jeton-1');
    api.post.mockResolvedValue([demande('NOUVELLE')]);

    const soumises = await service.soumettre([
      { creneauId: 1, standId: 'S1', cibleId: 'A2', motif: null }
    ]);

    expect(api.post).toHaveBeenCalledWith('/api/espace-animateur/jeton-1/demandes', [
      { creneauId: 1, standId: 'S1', cibleId: 'A2', motif: null }
    ]);
    expect(soumises.map((d) => d.id)).toEqual(['NOUVELLE']);
    expect(service.demandes().map((d) => d.id)).toEqual(['NOUVELLE', 'ANCIENNE']);
  });

  it("l'annulation poste puis recharge la liste depuis le serveur", async () => {
    api.getPreservingHttpError.mockImplementation(async (url: string) =>
      url.endsWith('/demandes') ? [demande('D1')] : vue()
    );
    await service.charger('jeton-1');

    const annulee = { ...demande('D1'), statut: 'ANNULEE' as const };
    api.getPreservingHttpError.mockResolvedValue([annulee]);
    await service.annuler('D1');

    expect(api.post).toHaveBeenCalledWith(
      '/api/espace-animateur/jeton-1/demandes/D1/annulation',
      null
    );
    expect(service.demandes()[0].statut).toBe('ANNULEE');
  });

  it('soumettre ou annuler sans espace chargé est un bug appelant : erreur explicite', async () => {
    await expect(
      service.soumettre([{ creneauId: 1, standId: 'S1', cibleId: 'A2', motif: null }])
    ).rejects.toThrow('Espace animateur non chargé');
    await expect(service.annuler('D1')).rejects.toThrow('Espace animateur non chargé');
    expect(api.post).not.toHaveBeenCalled();
  });
});
