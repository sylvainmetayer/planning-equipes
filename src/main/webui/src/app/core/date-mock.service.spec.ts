import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { DateMockService } from './date-mock.service';

interface View {
  dateDuJour: string | null;
  modifiable: boolean;
}

class FakeApi {
  view: View = { dateDuJour: null, modifiable: true };
  get = vi.fn(async (_url: string) => this.view);
  put = vi.fn(async (_url: string, body: { dateDuJour: string | null }) => {
    this.view = { ...this.view, dateDuJour: body.dateDuJour };
    return this.view;
  });
}

describe('DateMockService', () => {
  let service: DateMockService;
  let api: FakeApi;

  function create(view: View): void {
    api = new FakeApi();
    api.view = view;
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        DateMockService,
        { provide: ApiService, useValue: api }
      ]
    });
    service = TestBed.inject(DateMockService);
  }

  beforeEach(() => create({ dateDuJour: null, modifiable: true }));

  it('reads the setting from the server, never from the browser', async () => {
    await service.refresh();

    expect(api.get).toHaveBeenCalledWith('/api/debug/date-du-jour');
    expect(service.dateDuJour()).toBe('');
    expect(service.actif()).toBe(false);
  });

  it('reports the mock as active once a date is frozen', async () => {
    create({ dateDuJour: '2026-07-08', modifiable: true });
    await service.refresh();

    expect(service.dateDuJour()).toBe('2026-07-08');
    expect(service.actif()).toBe(true);
  });

  it('saves on change and adopts what the server answers', async () => {
    await service.set('2026-07-08');

    expect(api.put).toHaveBeenCalledWith('/api/debug/date-du-jour', { dateDuJour: '2026-07-08' });
    expect(service.actif()).toBe(true);
  });

  /** The empty field is how the real clock is handed back, so it must reach the server as null. */
  it('sends an empty field as null', async () => {
    await service.set('2026-07-08');
    await service.set('');

    expect(api.put.mock.calls[1][1]).toEqual({ dateDuJour: null });
    expect(service.dateDuJour()).toBe('');
    expect(service.actif()).toBe(false);
  });

  /**
   * `modifiable` only hides the field. The guard is server-side, so a client
   * that believes otherwise still gets refused — this only checks the reading.
   */
  it('carries whether this server would accept a frozen date at all', async () => {
    create({ dateDuJour: null, modifiable: false });
    await service.refresh();

    expect(service.modifiable()).toBe(false);
  });

  it('stays on the safe reading when the server cannot be reached', async () => {
    api = new FakeApi();
    api.get = vi.fn(async () => {
      throw new Error('injoignable');
    });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        DateMockService,
        { provide: ApiService, useValue: api }
      ]
    });
    service = TestBed.inject(DateMockService);

    expect(service.actif()).toBe(false);
    expect(service.modifiable()).toBe(false);
  });
});
