import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { ReferenceUsageService, phraseUsages } from './reference-usage.service';

class FakeApi {
  reponse: unknown = { affectations: 0, contraintesAdHoc: 0, verrouillages: 0 };
  get = vi.fn(async (_url: string) => this.reponse);
}

describe('ReferenceUsageService', () => {
  let service: ReferenceUsageService;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [ReferenceUsageService, { provide: ApiService, useValue: api }]
    });
    service = TestBed.inject(ReferenceUsageService);
  });

  it("interroge le compteur du référentiel avec l'identifiant en paramètre", async () => {
    api.reponse = { affectations: 42, contraintesAdHoc: 3, verrouillages: 1 };

    const phrase = await service.describe('stands', ['S1']);

    expect(api.get).toHaveBeenCalledWith('/api/stands/usages?id=S1');
    expect(phrase).toContain('42');
    expect(phrase).toContain('3');
    expect(phrase).toContain('1');
  });

  // Un total agrégé pour toute la sélection : une requête, pas N.
  it('porte toute la sélection dans une seule requête', async () => {
    await service.describe('creneaux', [1, 2, 3]);

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('/api/creneaux/usages?id=1&id=2&id=3');
  });

  /**
   * Une « tout sélectionner » sur une grille entière ferait déborder la ligne
   * de requête : on découpe plutôt que de tronquer, et le total reste exact.
   */
  it('découpe une très longue sélection et additionne les compteurs', async () => {
    api.reponse = { affectations: 5, contraintesAdHoc: 1, verrouillages: 0 };
    const ids = Array.from({ length: 900 }, (_, index) => index + 1);

    const phrase = await service.describe('creneaux', ids);

    expect(api.get.mock.calls.length).toBeGreaterThan(1);
    const appels = api.get.mock.calls.length;
    expect(phrase).toContain(String(5 * appels));
    expect(phrase).toContain(String(appels));
  });

  /**
   * Le budget porte sur la longueur encodée, pas sur un nombre d'identifiants :
   * ceux des stands et des animateurs sont du texte libre (VARCHAR(64)), donc
   * cent d'entre eux pèsent dix caractères ou six cents. Compter les lignes
   * laissait passer des URL que le serveur refuse, et le décompte disparaissait
   * en silence sur les grandes sélections.
   */
  it('borne chaque requête sur la longueur, pas sur le nombre d\'identifiants', async () => {
    const ids = Array.from({ length: 60 }, (_, index) => `S${index}-${'x'.repeat(60)}`);

    await service.describe('stands', ids);

    expect(api.get.mock.calls.length).toBeGreaterThan(1);
    for (const [url] of api.get.mock.calls) {
      expect(url.length).toBeLessThanOrEqual(2100);
    }
  });

  // Aucun identifiant ne doit disparaître d'un découpage : le total mentirait.
  it('envoie chaque identifiant exactement une fois', async () => {
    const ids = Array.from({ length: 300 }, (_, index) => `S${index}`);

    await service.describe('stands', ids);

    const envoyes = api.get.mock.calls.flatMap(([url]) =>
      [...new URLSearchParams(url.slice(url.indexOf('?') + 1)).getAll('id')]
    );
    expect(envoyes).toEqual(ids);
  });

  it("encode les identifiants qui contiennent des caractères d'URL", async () => {
    await service.describe('stands', ['A&B']);

    expect(api.get).toHaveBeenCalledWith('/api/stands/usages?id=A%26B');
  });

  // Les typologies calculent leurs usages côté page : rien à demander ici.
  it("n'appelle pas le serveur pour un référentiel sans compteur", async () => {
    expect(await service.describe('typologies', ['T1'])).toBe('');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('ne demande rien sur une sélection vide', async () => {
    expect(await service.describe('stands', [])).toBe('');
    expect(api.get).not.toHaveBeenCalled();
  });

  /**
   * Le décompte informe, il ne bloque pas : un serveur qui ne répond pas ne
   * doit pas empêcher la confirmation de s'ouvrir ni la suppression de partir.
   */
  it("reste muet quand le décompte échoue, sans propager l'erreur", async () => {
    api.get.mockRejectedValueOnce(new Error('injoignable'));

    await expect(service.describe('stands', ['S1'])).resolves.toBe('');
  });

  describe('phraseUsages', () => {
    it("dit explicitement qu'aucune référence n'existe", () => {
      const phrase = phraseUsages({ affectations: 0, contraintesAdHoc: 0, verrouillages: 0 });

      expect(phrase).not.toBe('');
      expect(phrase).not.toContain('0');
    });

    it('énonce les trois compteurs, y compris ceux restés à zéro', () => {
      const phrase = phraseUsages({ affectations: 7, contraintesAdHoc: 0, verrouillages: 2 });

      expect(phrase).toContain('7');
      expect(phrase).toContain('0');
      expect(phrase).toContain('2');
    });
  });
});
