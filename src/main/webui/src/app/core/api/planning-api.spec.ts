import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { PlanningEvenement } from '../models';
import { PlanningApi } from './planning-api';

/**
 * The one place the `/api/planning/*` paths are written, so the one place
 * they are asserted: the pages' specs check what they asked for, this one
 * checks where it went.
 */
describe('PlanningApi', () => {
  const api = { get: vi.fn(), post: vi.fn(), downloadGet: vi.fn(), downloadPost: vi.fn() };
  const PLANNING = { postes: [] } as unknown as PlanningEvenement;
  let planning: PlanningApi;

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    TestBed.configureTestingModule({
      providers: [PlanningApi, { provide: ApiService, useValue: api }],
    });
    planning = TestBed.inject(PlanningApi);
  });

  it('reads the persisted plan, the scenario names and the publication preview at their paths', async () => {
    await planning.persistedCount();
    await planning.scenarioNames();
    await planning.publicationPreview();
    await planning.comparableSnapshots();

    expect(api.get.mock.calls.map(([url]) => url)).toEqual([
      '/api/planning/persisted/count',
      '/api/planning/scenarios',
      '/api/planning/publication',
      '/api/planning/snapshots/comparables',
    ]);
  });

  it('sends both sides of a comparison as query parameters', async () => {
    await planning.compareSnapshots('courant', '8');

    expect(api.get).toHaveBeenCalledWith('/api/planning/snapshots/compare?base=courant&variante=8');
  });

  it('posts the planning it was given for the hours report, and nothing for a publication or a reset', async () => {
    await planning.hoursReport(PLANNING);
    await planning.publish();
    await planning.reset();

    expect(api.post).toHaveBeenNthCalledWith(1, '/api/planning/hours', PLANNING);
    expect(api.post).toHaveBeenNthCalledWith(2, '/api/planning/publication', null);
    expect(api.post).toHaveBeenNthCalledWith(3, '/api/planning/reset', {});
  });

  it('names the file and the content type of every download', async () => {
    await planning.exportScenario();
    await planning.exportGlobalPdf();
    await planning.exportBundle(PLANNING);
    await planning.exportHours(PLANNING);

    expect(api.downloadGet).toHaveBeenNthCalledWith(
      1,
      '/api/planning/export-scenario',
      'scenario.yaml',
      'application/x-yaml',
    );
    expect(api.downloadGet).toHaveBeenNthCalledWith(
      2,
      '/api/planning/export/pdf/global',
      'planning-global.pdf',
      'application/pdf',
    );
    expect(api.downloadPost).toHaveBeenNthCalledWith(
      1,
      '/api/planning/export/bundle/all',
      'planning.zip',
      PLANNING,
      'application/zip',
    );
    expect(api.downloadPost).toHaveBeenNthCalledWith(
      2,
      '/api/planning/hours/export',
      'heures-planning.csv',
      PLANNING,
      'text/csv',
    );
  });

  // The folded sheet is the same archive route, asked for by its format: the
  // organisation prints one page per person instead of five.
  it('asks the folded-sheet layout by its format parameter', async () => {
    await planning.exportFeuilles(PLANNING);

    expect(api.downloadPost).toHaveBeenCalledWith(
      '/api/planning/export/pdf/all?format=feuille',
      'planning-feuilles.zip',
      PLANNING,
      'application/zip',
    );
  });

  // An animateur id is free text: it travels encoded, or a slash in it would change the route.
  it('encodes the animateur id in the per-animateur export and send', async () => {
    await planning.exportForAnimateur('pdf', 'a/1', 'planning.pdf', PLANNING, 'application/pdf');
    await planning.sendToAnimateur('a/1');

    expect(api.downloadPost).toHaveBeenCalledWith(
      '/api/planning/export/pdf/animateur/a%2F1',
      'planning.pdf',
      PLANNING,
      'application/pdf',
    );
    expect(api.post).toHaveBeenCalledWith('/api/planning/envoi/animateur/a%2F1', null);
  });
});
