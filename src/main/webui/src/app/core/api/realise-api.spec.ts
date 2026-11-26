import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { AnalysesApi } from './analyses-api';
import { RealiseApi } from './realise-api';

/** Where the measures of an edition are read and deleted: the paths of 4.0, never the aliases. */
describe('the measures paths', () => {
  const api = { get: vi.fn(), delete: vi.fn(), downloadGet: vi.fn() };

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [RealiseApi, AnalysesApi, { provide: ApiService, useValue: api }],
    });
  });

  it('asks the realised of /api/mesures-edition/realise', async () => {
    const realise = TestBed.inject(RealiseApi);
    await realise.report();
    await realise.detail('2026-07-01', 'S 1');
    await realise.exportCsv();
    await realise.previousEdition();

    expect(api.get.mock.calls.map(([url]) => url)).toEqual([
      '/api/mesures-edition/realise',
      '/api/mesures-edition/realise/detail?jour=2026-07-01&stand=S%201',
      '/api/mesures-edition/realise/precedente',
    ]);
    expect(api.downloadGet).toHaveBeenCalledWith(
      '/api/mesures-edition/realise/export',
      'realise-vs-planifie.csv',
      'text/csv',
    );
  });

  it('lists and deletes the indicators under /api/mesures-edition/indicateurs', async () => {
    const analyses = TestBed.inject(AnalysesApi);
    await analyses.kpiHistory();
    await analyses.deleteKpiEntry(7);

    expect(api.get).toHaveBeenCalledWith('/api/mesures-edition/indicateurs');
    expect(api.delete).toHaveBeenCalledWith('/api/mesures-edition/indicateurs/7');
  });
});
