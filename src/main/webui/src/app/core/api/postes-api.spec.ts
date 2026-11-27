import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { PostesApi } from './postes-api';

class FakeApi {
  post = vi.fn((_url: string, _body: unknown) => Promise.resolve({}));
  get = vi.fn((_url: string) => Promise.resolve({}));
}

describe('PostesApi', () => {
  let service: PostesApi;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        PostesApi,
        { provide: ApiService, useValue: api },
      ],
    });
    service = TestBed.inject(PostesApi);
  });

  it('encodes poste and animateur ids used in the URL', async () => {
    await service.applyRepair('poste with space', 'id&with=chars');
    expect(api.post.mock.calls[0][0]).toBe(
      '/api/postes/poste%20with%20space/affectation?animateurId=id%26with%3Dchars',
    );
  });

  it('names the target and the holder of a move, and nothing it was not given', async () => {
    await service.move('poste-1', { posteId: 'poste-2' }, 'a1');
    await service.move('poste-1', { animateurId: 'b1' });

    expect(api.post.mock.calls.map((call) => call[0])).toEqual([
      '/api/postes/poste-1/deplacement?cible=poste-2&occupant=a1',
      '/api/postes/poste-1/deplacement?animateur=b1',
    ]);
  });

  it('empties the seat without an animateur, the holder shown as its precondition', async () => {
    await service.applyRepair('poste-1', null, 'a1');
    await service.applyRepair('poste-1', 'b1', 'a1');
    await service.applyRepair('poste-1', null);

    expect(api.post.mock.calls.map((call) => call[0])).toEqual([
      '/api/postes/poste-1/affectation?occupant=a1',
      '/api/postes/poste-1/affectation?animateurId=b1&occupant=a1',
      '/api/postes/poste-1/affectation?',
    ]);
  });

  it('simulates a change and reads the suggestions with GETs, which a lecteur may call', async () => {
    await service.simulate('poste-1', { posteId: 'poste-2' });
    await service.simulate('poste-1', { animateurId: 'b1' });
    await service.suggestions('poste-1');

    expect(api.get.mock.calls.map((call) => call[0])).toEqual([
      '/api/postes/poste-1/simulation?cible=poste-2',
      '/api/postes/poste-1/simulation?animateur=b1',
      '/api/postes/poste-1/suggestions',
    ]);
    expect(api.post).not.toHaveBeenCalled();
  });
});
