// The screen's one promise: it never writes until the operator says so, and it
// posts the file again rather than the report it was shown.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import type { AnimateurCsvMapping, ImportCsvRapport } from '../../core/models';
import { ImportAnimateursPage } from './import-animateurs-page';

/** Reaches the protected members the template binds to. */
type PageInternals = {
  analyser: () => Promise<void>;
  importer: () => Promise<void>;
  changerRemplacerJours: (valeur: boolean) => Promise<void>;
  changerRemplacerAnimateurs: (valeur: boolean) => Promise<void>;
  rapport: () => ImportCsvRapport | null;
  mapping: () => AnimateurCsvMapping | null;
  peutImporter: () => boolean;
  erreur: () => string;
  nomFichier: { set: (valeur: string) => void };
  telechargerExemple: () => Promise<void>;
};

const MAPPING: AnimateurCsvMapping = {
  id: null,
  prenom: 0,
  nom: 1,
  dateNaissance: 2,
  email: null,
  manager: null,
  competences: null,
  souhaits: null,
  joursIndisponibles: null
};

function rapport(applied: boolean): ImportCsvRapport {
  return {
    applied,
    columns: ['prenom', 'nom', 'date de naissance'],
    mapping: MAPPING,
    separator: ';',
    total: 2,
    accepted: 1,
    rejected: 1,
    created: 1,
    updated: 0,
    deleted: 0,
    rows: [
      {
        line: 2,
        label: 'Amélie Durand',
        animateurId: 'amelie-durand',
        action: 'CREATED',
        reasons: [],
        warnings: [],
        joursIndisponibles: ['2030-07-18']
      },
      {
        line: 3,
        label: 'Bruno Lefèvre',
        animateurId: null,
        action: 'REJECTED',
        reasons: ['Date de naissance illisible'],
        warnings: [],
        joursIndisponibles: []
      }
    ],
    warnings: ["Jours d'indisponibilité : ajout."]
  };
}

describe('ImportAnimateursPage', () => {
  const api = { post: vi.fn(), downloadGet: vi.fn(async () => 'Téléchargement démarré.') };
  const confirm = { ask: vi.fn(async () => true) };
  const store = { reload: vi.fn(async () => undefined) };
  const notifications = { notify: vi.fn() };
  let page: PageInternals;

  beforeEach(() => {
    api.post.mockReset();
    api.downloadGet.mockClear();
    confirm.ask.mockClear();
    store.reload.mockClear();
    notifications.notify.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ConfirmService, useValue: confirm },
        { provide: ReferenceDataStore, useValue: store },
        { provide: NotificationService, useValue: notifications }
      ]
    });
    page = TestBed.createComponent(ImportAnimateursPage).componentInstance as unknown as PageInternals;
  });

  /** Loading a file goes through the analysis endpoint only — the one that writes nothing. */
  async function chargerFichier(): Promise<void> {
    api.post.mockResolvedValueOnce(rapport(false));
    const instance = page as unknown as { contenu: { set: (v: string) => void } };
    instance.contenu.set('prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n');
    page.nomFichier.set('roster.csv');
    await page.analyser();
  }

  it('previews through the analysis endpoint and never through the write one', async () => {
    await chargerFichier();

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][0]).toBe('/api/animateurs/import-csv/analyse');
    expect(page.rapport()?.applied).toBe(false);
  });

  it('sends the file again on import, not the report it was shown', async () => {
    await chargerFichier();
    api.post.mockResolvedValueOnce(rapport(true));

    await page.importer();

    const [url, body] = api.post.mock.calls[1];
    expect(url).toBe('/api/animateurs/import-csv');
    expect((body as { content: string }).content).toContain('Amélie;Durand');
    expect((body as { rows?: unknown }).rows).toBeUndefined();
  });

  it('asks before writing, and writes nothing when the answer is no', async () => {
    await chargerFichier();
    confirm.ask.mockResolvedValueOnce(false);

    await page.importer();

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(page.rapport()?.applied).toBe(false);
  });

  it('reloads the referential and reports once the write came back applied', async () => {
    await chargerFichier();
    api.post.mockResolvedValueOnce(rapport(true));

    await page.importer();

    expect(page.rapport()?.applied).toBe(true);
    expect(store.reload).toHaveBeenCalledOnce();
    expect(notifications.notify).toHaveBeenCalledOnce();
  });

  it('re-previews when an option changes, so the report always matches the options', async () => {
    await chargerFichier();
    api.post.mockResolvedValueOnce(rapport(false));

    await page.changerRemplacerJours(true);

    const [, body] = api.post.mock.calls[1];
    expect((body as { replaceJoursIndisponibles: boolean }).replaceJoursIndisponibles).toBe(true);
  });

  /**
   * Two column changes in a row, and the answers come back the wrong way
   * round. The stale one must be dropped: putting its mapping back would undo
   * the choice the operator has just made, and the import would then be posted
   * with it.
   */
  it('keeps the last preview asked for, not an earlier one that lands late', async () => {
    await chargerFichier();
    const mappingA: AnimateurCsvMapping = { ...MAPPING, nom: 1 };
    const mappingB: AnimateurCsvMapping = { ...MAPPING, nom: 2 };
    let repondreA: (rapport: ImportCsvRapport) => void = () => undefined;
    api.post.mockReturnValueOnce(
      new Promise<ImportCsvRapport>((resolve) => {
        repondreA = resolve;
      })
    );
    const analyseA = page.analyser();
    api.post.mockResolvedValueOnce({ ...rapport(false), mapping: mappingB });
    await page.analyser();

    repondreA({ ...rapport(false), mapping: mappingA });
    await analyseA;

    expect(page.mapping()).toEqual(mappingB);
  });

  /** `apply()` refuses this combination outright: the button must say so first. */
  it('refuses the import when a full replacement is asked over a rejected row', async () => {
    await chargerFichier();
    expect(page.peutImporter()).toBe(true);
    api.post.mockResolvedValueOnce(rapport(false));

    await page.changerRemplacerAnimateurs(true);

    expect(page.rapport()?.rejected).toBe(1);
    expect(page.peutImporter()).toBe(false);
  });

  /** A 0-byte CSV: the server has a message for it, so it has to be asked. */
  it('asks the server about an empty file instead of falling silent', async () => {
    api.post.mockRejectedValueOnce(new Error('Le fichier est vide.'));
    page.nomFichier.set('vide.csv');

    await page.analyser();

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(page.erreur()).toContain('vide');
  });

  it('asks nothing while no file has been chosen', async () => {
    await page.analyser();

    expect(api.post).not.toHaveBeenCalled();
  });

  /**
   * The example roster is served by the API, not bundled in `public/`: one
   * file on the classpath, next to the scenario it derives from, and a backend
   * test re-imports that same resource. A copy in the front-end would be a
   * second file to keep true.
   */
  it('downloads the example roster from the API rather than from a bundled copy', async () => {
    await page.telechargerExemple();

    expect(api.downloadGet).toHaveBeenCalledWith(
      '/api/animateurs/import-csv/exemple',
      'festival-realiste-animateurs.csv',
      'text/csv'
    );
    expect(api.post).not.toHaveBeenCalled();
    expect(page.erreur()).toBe('');
  });

  it('shows the refusal and drops the report when the server refuses the file', async () => {
    api.post.mockRejectedValueOnce(new Error("Ce format n'est pas accepté : seul le CSV est lu."));
    const instance = page as unknown as { contenu: { set: (v: string) => void } };
    instance.contenu.set('PK');
    page.nomFichier.set('roster.xlsx');

    await page.analyser();

    expect(page.erreur()).toContain('seul le CSV est lu');
    expect(page.rapport()).toBeNull();
  });
});
