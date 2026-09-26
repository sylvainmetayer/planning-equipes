import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { JourJService } from './jour-j.service';

class FakeApi {
  get = vi.fn(async (_url: string) => ({}));
  post = vi.fn(async (_url: string, _body: unknown) => ({}));
  delete = vi.fn(async (_url: string) => undefined);
}

describe('JourJService', () => {
  let service: JourJService;
  let api: FakeApi;

  beforeEach(() => {
    api = new FakeApi();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        JourJService,
        { provide: ApiService, useValue: api },
      ],
    });
    service = TestBed.inject(JourJService);
  });

  it('reads the day without arguments, letting the server name today and now', async () => {
    await service.etat();
    expect(api.get).toHaveBeenCalledWith('/api/jour-j');
  });

  it('passes an explicit reference moment through when one is given', async () => {
    await service.etat('2026-07-08', '13:30');
    expect(api.get).toHaveBeenCalledWith('/api/jour-j?date=2026-07-08&heure=13%3A30');
  });

  it('sends a blank reason as null rather than as an empty string', async () => {
    await service.marquerAbsent('A1', '   ');
    expect(api.post).toHaveBeenCalledWith('/api/jour-j/absences', {
      animateurId: 'A1',
      raison: null,
      creneauId: null,
    });

    await service.marquerAbsent('A1', '  Malade  ');
    expect(api.post.mock.calls[1][1]).toEqual({
      animateurId: 'A1',
      raison: 'Malade',
      creneauId: null,
    });
  });

  it('narrows an absence to one timeslot, and settles a report either way', async () => {
    await service.marquerAbsent('A1', '', undefined, undefined, 42);
    expect(api.post.mock.calls[0][1]).toEqual({ animateurId: 'A1', raison: null, creneauId: 42 });

    await service.traiterSignalement(7);
    expect(api.post).toHaveBeenCalledWith('/api/jour-j/signalements/7/traitement', null);
    await service.classerSignalement(7);
    expect(api.post).toHaveBeenCalledWith('/api/jour-j/signalements/7/classement', null);
  });

  it('cancels the whole day, or one timeslot', async () => {
    await service.annulerAbsence('A1');
    expect(api.delete).toHaveBeenCalledWith('/api/jour-j/absences/A1');

    await service.annulerAbsence('A1', undefined, 2);
    expect(api.delete.mock.calls[1][0]).toBe('/api/jour-j/absences/A1?creneauId=2');
  });

  /**
   * The reason this screen has its own suggestion endpoint at all: the repair
   * assistant's own one takes the whole planning as its body, which is a
   * megabyte per seat over the mobile connection this page runs on.
   */
  it('asks for suggestions by seat id, never by uploading a planning', async () => {
    await service.suggestions('P 2');

    expect(api.post).toHaveBeenCalledWith('/api/jour-j/postes/P%202/suggestions', null);
  });

  it('warns the people it names through the targeted publication, and nobody else', async () => {
    await service.prevenir(['A1', 'A2']);

    expect(api.post).toHaveBeenCalledWith('/api/planning/publication', {
      exclusions: [],
      cibles: ['A1', 'A2'],
    });
  });
});
