// The screen's one promise: it never writes until the operator says so, and it
// posts the file again rather than the report it was shown.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import type { AnimateurCsvMapping, ImportCsvRapport } from '../../core/models';
import { Fake, fakeOf, provideFake } from '../../core/testing/fake';
import { ImportAnimateursPage } from './import-animateurs-page';

const MAPPING: AnimateurCsvMapping = {
  prenom: 0,
  nom: 1,
  dateNaissance: 2,
  email: null,
  manager: null,
  competences: null,
  souhaits: null,
  joursIndisponibles: null,
  telephone: null,
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
        joursIndisponibles: ['2030-07-18'],
      },
      {
        line: 3,
        label: 'Bruno Lefèvre',
        animateurId: null,
        action: 'REJECTED',
        reasons: ['Date de naissance illisible'],
        warnings: [],
        joursIndisponibles: [],
      },
    ],
    warnings: ["Jours d'indisponibilité : ajout."],
  };
}

describe('ImportAnimateursPage', () => {
  let animateursApi: Fake<AnimateursApi>;
  let confirm: Fake<ConfirmService>;
  let store: Fake<ReferenceDataStore>;
  let notifications: Fake<NotificationService>;
  let page: ImportAnimateursPage;

  beforeEach(() => {
    // Every answer of the two import endpoints is given by the test that expects it.
    animateursApi = fakeOf<AnimateursApi>({
      analyseCsvImport: vi.fn(),
      applyCsvImport: vi.fn(),
      downloadCsvExample: async () => 'Téléchargement démarré.',
    });
    confirm = fakeOf<ConfirmService>({ ask: async () => true });
    store = fakeOf<ReferenceDataStore>({ reload: async () => undefined });
    notifications = fakeOf<NotificationService>({ notify: () => undefined });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideFake(AnimateursApi, animateursApi),
        provideFake(ConfirmService, confirm),
        provideFake(ReferenceDataStore, store),
        provideFake(NotificationService, notifications),
      ],
    });
    page = TestBed.createComponent(ImportAnimateursPage).componentInstance;
  });

  /** Loading a file goes through the analysis endpoint only — the one that writes nothing. */
  async function chargerFichier(): Promise<void> {
    animateursApi.analyseCsvImport.mockResolvedValueOnce(rapport(false));
    page['contenu'].set('prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n');
    page['nomFichier'].set('roster.csv');
    await page['analyser']();
  }

  it('previews through the analysis endpoint and never through the write one', async () => {
    await chargerFichier();

    expect(animateursApi.analyseCsvImport).toHaveBeenCalledTimes(1);
    expect(animateursApi.applyCsvImport).not.toHaveBeenCalled();
    expect(page['rapport']()?.applied).toBe(false);
  });

  it('sends the file again on import, not the report it was shown', async () => {
    await chargerFichier();
    animateursApi.applyCsvImport.mockResolvedValueOnce(rapport(true));

    await page['importer']();

    const [body] = animateursApi.applyCsvImport.mock.calls[0];
    expect(body.content).toContain('Amélie;Durand');
    expect(body).not.toHaveProperty('rows');
  });

  it('asks before writing, and writes nothing when the answer is no', async () => {
    await chargerFichier();
    confirm.ask.mockResolvedValueOnce(false);

    await page['importer']();

    expect(animateursApi.applyCsvImport).not.toHaveBeenCalled();
    expect(page['rapport']()?.applied).toBe(false);
  });

  it('reloads the referential and reports once the write came back applied', async () => {
    await chargerFichier();
    animateursApi.applyCsvImport.mockResolvedValueOnce(rapport(true));

    await page['importer']();

    expect(page['rapport']()?.applied).toBe(true);
    expect(store.reload).toHaveBeenCalledOnce();
    expect(notifications.notify).toHaveBeenCalledOnce();
  });

  /** « Voir les N lignes importées » opens the list on the fiches written, never on the refused. */
  it('links to the fiches the write created or updated, and to them alone', async () => {
    await chargerFichier();
    const link = page['lienLignes'];
    expect(link()).toBeNull();
    animateursApi.applyCsvImport.mockResolvedValueOnce(rapport(true));

    await page['importer']();

    expect(link()).toEqual({
      queryParams: { ids: 'amelie-durand' },
      libelle: 'Voir les 1 lignes importées',
    });
  });

  it('re-previews when an option changes, so the report always matches the options', async () => {
    await chargerFichier();
    animateursApi.analyseCsvImport.mockResolvedValueOnce(rapport(false));

    await page['changerRemplacerJours'](true);

    const [body] = animateursApi.analyseCsvImport.mock.calls[1];
    expect(body.replaceJoursIndisponibles).toBe(true);
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
    animateursApi.analyseCsvImport.mockReturnValueOnce(
      new Promise<ImportCsvRapport>((resolve) => {
        repondreA = resolve;
      }),
    );
    const analyseA = page['analyser']();
    animateursApi.analyseCsvImport.mockResolvedValueOnce({ ...rapport(false), mapping: mappingB });
    await page['analyser']();

    repondreA({ ...rapport(false), mapping: mappingA });
    await analyseA;

    expect(page['mapping']()).toEqual(mappingB);
  });

  /** `apply()` refuses this combination outright: the button must say so first. */
  it('refuses the import when a full replacement is asked over a rejected row', async () => {
    await chargerFichier();
    expect(page['peutImporter']()).toBe(true);
    animateursApi.analyseCsvImport.mockResolvedValueOnce(rapport(false));

    await page['changerRemplacerAnimateurs'](true);

    expect(page['rapport']()?.rejected).toBe(1);
    expect(page['peutImporter']()).toBe(false);
  });

  /** A 0-byte CSV: the server has a message for it, so it has to be asked. */
  it('asks the server about an empty file instead of falling silent', async () => {
    animateursApi.analyseCsvImport.mockRejectedValueOnce(new Error('Le fichier est vide.'));
    page['nomFichier'].set('vide.csv');

    await page['analyser']();

    expect(animateursApi.analyseCsvImport).toHaveBeenCalledTimes(1);
    expect(page['erreur']()).toContain('vide');
  });

  it('asks nothing while no file has been chosen', async () => {
    await page['analyser']();

    expect(animateursApi.analyseCsvImport).not.toHaveBeenCalled();
  });

  /**
   * The example roster is served by the API, not bundled in `public/`: one
   * file on the classpath, next to the scenario it derives from, and a backend
   * test re-imports that same resource. A copy in the front-end would be a
   * second file to keep true.
   */
  it('downloads the example roster from the API rather than from a bundled copy', async () => {
    await page['telechargerExemple']();

    expect(animateursApi.downloadCsvExample).toHaveBeenCalledOnce();
    expect(animateursApi.analyseCsvImport).not.toHaveBeenCalled();
    expect(page['erreur']()).toBe('');
  });

  it('shows the refusal and drops the report when the server refuses the file', async () => {
    animateursApi.analyseCsvImport.mockRejectedValueOnce(
      new Error("Ce format n'est pas accepté : seul le CSV est lu."),
    );
    page['contenu'].set('PK');
    page['nomFichier'].set('roster.xlsx');

    await page['analyser']();

    expect(page['erreur']()).toContain('seul le CSV est lu');
    expect(page['rapport']()).toBeNull();
  });
});
