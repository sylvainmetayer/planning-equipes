// The stand grid import page: the preview is a pure read, the import posts
// the very same body and asks first, and the report says what each column
// and each row became.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StandsApi } from '../../core/api/stands-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ImportGrilleRapport, Stand } from '../../core/models';
import { ImportGrilleStandsPage } from './import-grille-stands-page';

type PageInternals = {
  analyser: () => Promise<void>;
  importer: () => Promise<void>;
  rapport: () => ImportGrilleRapport | null;
  peutImporter: () => boolean;
  erreur: () => string;
  nomFichier: { set: (valeur: string) => void };
};

function rapport(applied: boolean): ImportGrilleRapport {
  return {
    applied,
    separator: ';',
    columns: [
      {
        index: 1,
        label: '2026-07-08 10:00-12:00',
        date: '2026-07-08',
        heureDebut: '10:00',
        heureFin: '12:00',
        creneauId: 1,
        creneaux: 2,
        reason: null,
      },
      {
        index: 2,
        label: '2026-07-08 montage',
        date: '2026-07-08',
        heureDebut: null,
        heureFin: null,
        creneauId: null,
        creneaux: 0,
        reason: 'En-tête illisible',
      },
    ],
    creneauxAbsents: ['2026-07-09 10:00-12:00'],
    total: 2,
    accepted: 1,
    rejected: 1,
    rows: [
      {
        line: 3,
        label: 'BOURSE',
        standId: 'S7',
        action: 'UPDATED',
        reasons: [],
        cellulesOuvertes: 1,
        regles: 1,
        exceptions: 0,
        effectifMin: 2,
        effectifMax: 2,
      },
      {
        line: 4,
        label: 'Inconnu',
        standId: null,
        action: 'REJECTED',
        reasons: ['Aucun stand « Inconnu »'],
        cellulesOuvertes: 0,
        regles: 0,
        exceptions: 0,
        effectifMin: null,
        effectifMax: null,
      },
    ],
    warnings: ['1 créneau(x) sans colonne.'],
  };
}

describe('ImportGrilleStandsPage', () => {
  const standsApi = {
    analyseGridImport: vi.fn(),
    applyGridImport: vi.fn(),
    downloadGridExample: vi.fn(async () => 'ok'),
  };
  const confirm = { ask: vi.fn(async () => true) };
  const store = {
    reload: vi.fn(async () => undefined),
    stands: signal([{ id: 'S7', nom: 'Bourse aux jeux' } as Stand]),
  };
  const notifications = { notify: vi.fn() };
  let fixture: ComponentFixture<ImportGrilleStandsPage>;
  let page: PageInternals;

  beforeEach(async () => {
    standsApi.analyseGridImport.mockReset();
    standsApi.applyGridImport.mockReset();
    confirm.ask.mockReset();
    confirm.ask.mockResolvedValue(true);
    store.reload.mockClear();
    notifications.notify.mockClear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: StandsApi, useValue: standsApi },
        { provide: ConfirmService, useValue: confirm },
        { provide: ReferenceDataStore, useValue: store },
        { provide: NotificationService, useValue: notifications },
      ],
    });
    fixture = TestBed.createComponent(ImportGrilleStandsPage);
    page = fixture.componentInstance as unknown as PageInternals;
    await fixture.whenStable();
    // The stands are read once on entry, for their names; the tests below
    // count the reload an import triggers.
    expect(store.reload).toHaveBeenCalledExactlyOnceWith(['stands']);
    store.reload.mockClear();
  });

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function chargerEtAnalyser(): Promise<void> {
    standsApi.analyseGridImport.mockResolvedValueOnce(rapport(false));
    (fixture.componentInstance as unknown as { contenu: { set(v: string): void } }).contenu.set(
      'stand;2026-07-08\n;10:00-12:00\nBOURSE;2\n',
    );
    page.nomFichier.set('grille.csv');
    await page.analyser();
    await fixture.whenStable();
  }

  it('previews through the analysis endpoint and shows columns, rows and warnings', async () => {
    await chargerEtAnalyser();

    expect(standsApi.analyseGridImport).toHaveBeenCalledOnce();
    expect(standsApi.applyGridImport).not.toHaveBeenCalled();
    expect(
      (standsApi.analyseGridImport.mock.calls[0] as unknown as [{ fileName: string }])[0].fileName,
    ).toBe('grille.csv');
    const text = racine().textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('1 colonne(s) reconnue(s)');
    expect(text).toContain('2026-07-08 montage');
    expect(text).toContain('En-tête illisible');
    expect(text).toContain('1 créneau(x) sans colonne.');
    expect(racine().querySelectorAll('tbody tr')).toHaveLength(2);
    expect(racine().querySelector('tr[data-ligne="3"]')!.textContent).toContain('1 règle(s)');
    // The stand the row matched, by name — never its generated id.
    expect(racine().querySelector('tr[data-ligne="3"]')!.textContent).toContain(
      '(Bourse aux jeux)',
    );
    expect(racine().querySelector('tr[data-ligne="3"]')!.textContent).not.toContain('S7');
    expect(racine().querySelector('tr[data-ligne="4"]')!.textContent).toContain(
      'Aucun stand « Inconnu »',
    );
    expect(page.peutImporter()).toBe(true);
  });

  it('imports the same body after a confirmation, reloads the store and reports', async () => {
    await chargerEtAnalyser();
    standsApi.applyGridImport.mockResolvedValueOnce(rapport(true));

    await page.importer();
    await fixture.whenStable();

    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(standsApi.applyGridImport).toHaveBeenCalledOnce();
    const [corps] = standsApi.applyGridImport.mock.calls[0] as unknown as [unknown];
    expect(corps).toEqual((standsApi.analyseGridImport.mock.calls[0] as unknown as [unknown])[0]);
    expect(store.reload).toHaveBeenCalledOnce();
    expect(notifications.notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'success' }),
    );
    expect(page.rapport()?.applied).toBe(true);
    // Applied: the import button is gone, nothing to re-import.
    expect(page.peutImporter()).toBe(false);
  });

  it('writes nothing when the confirmation is refused', async () => {
    await chargerEtAnalyser();
    confirm.ask.mockResolvedValue(false);

    await page.importer();

    expect(standsApi.applyGridImport).not.toHaveBeenCalled();
    expect(store.reload).not.toHaveBeenCalled();
  });

  it('shows the server refusal in place and keeps the import off', async () => {
    standsApi.analyseGridImport.mockRejectedValueOnce(new Error("L'édition n'a aucun créneau"));
    page.nomFichier.set('grille.csv');
    (fixture.componentInstance as unknown as { contenu: { set(v: string): void } }).contenu.set(
      'x',
    );
    await page.analyser();
    await fixture.whenStable();

    expect(page.erreur()).toContain('aucun créneau');
    expect(page.rapport()).toBeNull();
    expect(page.peutImporter()).toBe(false);
  });
});
