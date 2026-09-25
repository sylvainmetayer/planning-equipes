import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { JoursFeriesService } from './jours-feries.service';

describe('JoursFeriesService', () => {
  const api = { get: vi.fn() };
  let service: JoursFeriesService;

  beforeEach(() => {
    api.get.mockReset();
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: api }] });
    service = TestBed.inject(JoursFeriesService);
  });

  it('reads each year the dates fall in once, and names the holidays', async () => {
    api.get.mockImplementation(async (url: string) =>
      url.includes('debut=2026')
        ? [{ date: '2026-07-14', label: 'Fête nationale' }]
        : [{ date: '2027-01-01', label: "Jour de l'an" }],
    );

    await service.load(['2026-07-13', '2026-07-14', '2027-01-01', null, '2026-0']);
    await service.load(['2026-08-15']);

    expect(api.get.mock.calls.map(([url]) => url)).toEqual([
      '/api/jours-feries?debut=2026-01-01&fin=2026-12-31',
      '/api/jours-feries?debut=2027-01-01&fin=2027-12-31',
    ]);
    expect(service.label('2026-07-14')).toBe('Fête nationale');
    expect(service.label('2027-01-01')).toBe("Jour de l'an");
    expect(service.label('2026-07-13')).toBeNull();
    expect(service.label(null)).toBeNull();
  });

  it('asks again for a year whose read failed', async () => {
    api.get.mockRejectedValueOnce(new Error('offline'));
    await service.load(['2026-07-14']);
    api.get.mockResolvedValueOnce([{ date: '2026-07-14', label: 'Fête nationale' }]);
    await service.load(['2026-07-14']);

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(service.label('2026-07-14')).toBe('Fête nationale');
  });
});
