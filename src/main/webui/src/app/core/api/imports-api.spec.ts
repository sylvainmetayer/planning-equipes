// Each referential is a resource of its own, so the one thing that can break
// silently here is a target routed to the wrong address: the card would then
// preview one referential and write another.

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { ReferentielImportTarget, ImportGrilleDemande } from '../models';
import { CIBLES_EXPORT_CSV, ImportsApi } from './imports-api';

describe('ImportsApi', () => {
  const api = { post: vi.fn(), downloadGet: vi.fn() };
  const request: ImportGrilleDemande = { fileName: 'f.csv', content: 'a;b\n1;2\n' };
  let imports: ImportsApi;

  const TARGETS: ReferentielImportTarget[] = [
    'TYPOLOGIES',
    'EMPLACEMENTS',
    'STANDS',
    'CRENEAUX',
    'JOURNEES_TYPES',
  ];

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [ImportsApi, { provide: ApiService, useValue: api }],
    });
    imports = TestBed.inject(ImportsApi);
  });

  it('previews each referential on its own resource', async () => {
    for (const target of TARGETS) {
      await imports.analyse(target, request);
    }

    expect(api.post.mock.calls.map(([url]) => url)).toEqual([
      '/api/typologies/import-csv/analyse',
      '/api/emplacements/import-csv/analyse',
      '/api/stands/import-csv/analyse',
      '/api/creneaux/import-csv/analyse',
      '/api/journees-types/import-csv/analyse',
    ]);
    expect(api.post.mock.calls.every(([, body]) => body === request)).toBe(true);
  });

  it('writes to the same resource it previewed, one path shorter', async () => {
    for (const target of TARGETS) {
      await imports.importer(target, request);
    }

    expect(api.post.mock.calls.map(([url]) => url)).toEqual([
      '/api/typologies/import-csv',
      '/api/emplacements/import-csv',
      '/api/stands/import-csv',
      '/api/creneaux/import-csv',
      '/api/journees-types/import-csv',
    ]);
  });

  it('downloads an example named after the referential it shows', async () => {
    for (const target of TARGETS) {
      await imports.telechargerExemple(target);
    }

    expect(api.downloadGet.mock.calls.map(([url, fileName]) => [url, fileName])).toEqual([
      ['/api/typologies/import-csv/exemple', 'exemple-typologies.csv'],
      ['/api/emplacements/import-csv/exemple', 'exemple-emplacements.csv'],
      ['/api/stands/import-csv/exemple', 'exemple-stands.csv'],
      ['/api/creneaux/import-csv/exemple', 'exemple-creneaux.csv'],
      ['/api/journees-types/import-csv/exemple', 'exemple-journees-types.csv'],
    ]);
  });

  /**
   * The archive and the import tabs must offer the same referentials, minus the
   * animateurs — whose import has a screen of its own, with a column mapping
   * the shared card knows nothing about.
   */
  it('exports every referential an import tab reads back, plus the animateurs', () => {
    expect([...CIBLES_EXPORT_CSV]).toEqual([...TARGETS, 'ANIMATEURS']);
  });
});
