// The screen's one promise: it never writes until the operator says so, and it
// posts the file again rather than the report it was shown.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ConfirmService } from '../../shared/confirm-dialog';
import type { AnimateurCsvMapping, ImportCsvLigne, ImportCsvRapport } from '../../core/models';
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
    doublonsProbables: 0,
    rows: [
      {
        line: 2,
        label: 'Amélie Durand',
        animateurId: 'amelie-durand',
        action: 'CREATED',
        reasons: [],
        warnings: [],
        joursIndisponibles: ['2030-07-18'],
        doublonDe: [],
      },
      {
        line: 3,
        label: 'Bruno Lefèvre',
        animateurId: null,
        action: 'REJECTED',
        reasons: ['Date de naissance illisible'],
        warnings: [],
        joursIndisponibles: [],
        doublonDe: [],
      },
    ],
    warnings: ["Jours d'indisponibilité : ajout."],
  };
}

/**
 * Rows 2 and 4 describe one person under two addresses, row 5 lands by its
 * name on a namesake born another day, row 6 by its name on a fiche whose
 * address it replaces; row 3 is nobody's duplicate.
 */
function reportWithDuplicates(): ImportCsvRapport {
  const ligne = (line: number, label: string, doublonDe: ImportCsvLigne['doublonDe']) => ({
    line,
    label,
    animateurId: null,
    action: 'CREATED' as const,
    reasons: [],
    warnings: doublonDe.length > 0 ? ['Probable doublon'] : [],
    joursIndisponibles: [],
    doublonDe,
  });
  return {
    ...rapport(false),
    total: 5,
    accepted: 5,
    rejected: 0,
    created: 3,
    updated: 2,
    doublonsProbables: 4,
    rows: [
      ligne(2, 'Amélie Durand', [{ kind: 'ROW', line: 4, animateurId: null }]),
      ligne(3, 'Bruno Lefèvre', []),
      ligne(4, 'Amélie Durand', [{ kind: 'ROW', line: 2, animateurId: null }]),
      {
        ...ligne(5, 'Jean Martin', [{ kind: 'NAMESAKE', line: null, animateurId: 'A7' }]),
        action: 'UPDATED',
        animateurId: 'A7',
      },
      {
        ...ligne(6, 'Paul Petit', [{ kind: 'NEW_ADDRESS', line: null, animateurId: 'A8' }]),
        action: 'UPDATED',
        animateurId: 'A8',
      },
    ],
  };
}

describe('ImportAnimateursPage', () => {
  const animateursApi = fakeOf<AnimateursApi>({
    analyseCsvImport: () => Promise.resolve(rapport(false)),
    applyCsvImport: () => Promise.resolve(rapport(true)),
    downloadCsvExample: () => Promise.resolve('Téléchargement démarré.'),
  });
  const confirm = fakeOf<ConfirmService>({ ask: () => Promise.resolve(true) });
  const store = fakeOf<ReferenceDataStore>({ reload: () => Promise.resolve(undefined) });
  const notifications = fakeOf<NotificationService>({ notify: () => undefined });
  let page: ImportAnimateursPage;
  let fixture: ComponentFixture<ImportAnimateursPage>;

  beforeEach(() => {
    animateursApi.analyseCsvImport.mockReset();
    animateursApi.applyCsvImport.mockReset();
    animateursApi.downloadCsvExample.mockClear();
    confirm.ask.mockClear();
    store.reload.mockClear();
    notifications.notify.mockClear();
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
    fixture = TestBed.createComponent(ImportAnimateursPage);
    page = fixture.componentInstance;
  });

  /** Loading a file goes through the analysis endpoint only — the one that writes nothing. */
  async function loadFile(apercu: ImportCsvRapport = rapport(false)): Promise<void> {
    animateursApi.analyseCsvImport.mockResolvedValueOnce(apercu);
    page['contenu'].set('prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n');
    page['nomFichier'].set('roster.csv');
    await page['analyser']();
  }

  it('previews through the analysis endpoint and never through the write one', async () => {
    await loadFile();

    expect(animateursApi.analyseCsvImport).toHaveBeenCalledTimes(1);
    expect(animateursApi.applyCsvImport).not.toHaveBeenCalled();
    expect(page['rapport']()?.applied).toBe(false);
  });

  it('sends the file again on import, not the report it was shown', async () => {
    await loadFile();
    animateursApi.applyCsvImport.mockResolvedValueOnce(rapport(true));

    await page['importer']();

    const [body] = animateursApi.applyCsvImport.mock.calls[0];
    expect(body.content).toContain('Amélie;Durand');
    expect(Object.keys(body)).not.toContain('rows');
  });

  it('asks before writing, and writes nothing when the answer is no', async () => {
    await loadFile();
    confirm.ask.mockResolvedValueOnce(false);

    await page['importer']();

    expect(animateursApi.applyCsvImport).not.toHaveBeenCalled();
    expect(page['rapport']()?.applied).toBe(false);
  });

  it('reloads the referential and reports once the write came back applied', async () => {
    await loadFile();
    animateursApi.applyCsvImport.mockResolvedValueOnce(rapport(true));

    await page['importer']();

    expect(page['rapport']()?.applied).toBe(true);
    expect(store.reload).toHaveBeenCalledOnce();
    expect(notifications.notify).toHaveBeenCalledOnce();
  });

  /** « Voir les N lignes importées » opens the list on the fiches written, never on the refused. */
  it('links to the fiches the write created or updated, and to them alone', async () => {
    await loadFile();
    const lien = page['lienLignes'];
    expect(lien()).toBeNull();
    animateursApi.applyCsvImport.mockResolvedValueOnce(rapport(true));

    await page['importer']();

    expect(lien()).toEqual({
      queryParams: { ids: 'amelie-durand' },
      libelle: 'Voir les 1 lignes importées',
    });
  });

  it('re-previews when an option changes, so the report always matches the options', async () => {
    await loadFile();
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
    await loadFile();
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
    await loadFile();
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

  /* ------------------------- Probable duplicates ------------------------- */

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function shownLines(): string[] {
    return Array.from(host().querySelectorAll<HTMLElement>('table.import-rapport tbody tr')).map(
      (tr) => tr.dataset['ligne'] ?? '',
    );
  }

  it('counts the probable duplicates above the table and badges each flagged row', async () => {
    await loadFile(reportWithDuplicates());
    await fixture.whenStable();

    expect(host().querySelector('.import-compteur-doublons')?.textContent).toMatch(
      /Doublons ou homonymes probables\s*:\s*4/,
    );
    const badges = Array.from(host().querySelectorAll('.import-badge-doublon')).map((badge) =>
      badge.textContent?.trim(),
    );
    expect(badges).toHaveLength(4);
    expect(badges[0]).toContain('Doublon probable');
    expect(badges[1]).toContain('Doublon probable');
    expect(badges[2]).toContain('Homonyme ?');
    expect(badges[3]).toContain('Nouvelle adresse ?');
    const fiche = host().querySelector('a[href="/animateurs/A7"]');
    expect(fiche?.getAttribute('target')).toBe('_blank');
    expect(fiche?.textContent).toContain('(nouvelle fenêtre)');
  });

  it('narrows the table to the flagged rows, and forgets the filter with the file', async () => {
    await loadFile(reportWithDuplicates());
    await fixture.whenStable();
    expect(shownLines()).toEqual(['2', '3', '4', '5', '6']);

    host().querySelector<HTMLInputElement>('.import-filtre-doublons input')?.click();
    await fixture.whenStable();

    expect(shownLines()).toEqual(['2', '4', '5', '6']);
    expect(host().querySelector('.import-compteur-doublons')?.textContent).toContain('4');

    // A re-preview of the same file (a column changed) keeps the filter on…
    animateursApi.analyseCsvImport.mockResolvedValueOnce(reportWithDuplicates());
    await page['analyser']();
    await fixture.whenStable();
    expect(shownLines()).toEqual(['2', '4', '5', '6']);

    // …another file starts from the whole table.
    animateursApi.analyseCsvImport.mockResolvedValueOnce(reportWithDuplicates());
    await page['onColle']('prenom;nom\nAmélie;Durand\n');
    await fixture.whenStable();

    expect(shownLines()).toEqual(['2', '3', '4', '5', '6']);
  });

  it('offers no filter while nothing is flagged', async () => {
    await loadFile();
    await fixture.whenStable();

    expect(host().querySelector('.import-filtre-doublons')).toBeNull();
    expect(host().querySelector('.import-badge-doublon')).toBeNull();
  });

  it('takes the operator to the other row of a duplicate', async () => {
    await loadFile(reportWithDuplicates());
    await fixture.whenStable();
    const voir = Array.from(
      host().querySelectorAll<HTMLButtonElement>('.import-doublon-liens button'),
    ).find((bouton) => bouton.textContent?.includes('Voir la ligne 4'));

    voir?.click();

    expect((document.activeElement as HTMLElement | null)?.dataset['ligne']).toBe('4');
  });

  it('repeats the number of flagged rows in the import confirmation', async () => {
    await loadFile(reportWithDuplicates());

    await page['importer']();

    const [data] = confirm.ask.mock.calls[0];
    expect(data.confirmLabel).toBe('Importer quand même');
    expect(await data.detail).toContain('Dont 4 ligne(s) signalée(s)');
  });

  it('confirms an import with no flagged row as before', async () => {
    await loadFile();

    await page['importer']();

    const [data] = confirm.ask.mock.calls[0];
    expect(data.detail).toBeUndefined();
    expect(data.confirmLabel).toBeUndefined();
  });
});
