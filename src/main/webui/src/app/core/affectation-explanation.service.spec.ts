import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { AffectationExplanationService } from './affectation-explanation.service';
import type { PlanningEvenement } from './models';

function planning(): PlanningEvenement {
  return {
    animateurs: [],
    postes: [],
    score: { hardScore: 0, mediumScore: 0, softScore: -3 },
  };
}

class FakeApi {
  post = vi.fn(async (_url: string, _body: unknown) => ({}));
}

describe('AffectationExplanationService', () => {
  let service: AffectationExplanationService;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        AffectationExplanationService,
        { provide: ApiService, useValue: api },
      ],
    });
    service = TestBed.inject(AffectationExplanationService);
  });

  it('posts the planning without its score field, to avoid crashing the server-side deserializer', async () => {
    await service.explique(planning(), 'poste-1');

    expect(api.post).toHaveBeenCalledOnce();
    const [url, body] = api.post.mock.calls[0];
    expect(url).toBe('/api/postes/poste-1/explication');
    expect(body).not.toHaveProperty('score');
    expect(body).toHaveProperty('animateurs');
    expect(body).toHaveProperty('postes');
  });

  it('encodes poste and animateur ids used in the URL', async () => {
    await service.explique(planning(), 'poste with space');
    expect(api.post.mock.calls[0][0]).toBe('/api/postes/poste%20with%20space/explication');

    await service.appliquerReparation('poste-1', 'id&with=chars');
    expect(api.post.mock.calls[1][0]).toBe(
      '/api/postes/poste-1/affectation?animateurId=id%26with%3Dchars',
    );
  });
});
