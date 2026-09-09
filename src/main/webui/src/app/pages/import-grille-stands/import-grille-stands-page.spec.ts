// The stand grid import page: the preview is a pure read, the import posts
// the very same body and asks first, and the report says what each column
// and each row became.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ImportGrilleRapport } from '../../core/models';
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
      { index: 1, label: '2026-07-08 10:00-12:00', date: '2026-07-08', heureDebut: '10:00', heureFin: '12:00', creneauId: 1, creneaux: 2, reason: null },
      { index: 2, label: '2026-07-08 montage', date: '2026-07-08', heureDebut: null, heureFin: null, creneauId: null, creneaux: 0, reason: 'En-tête illisible' }
    ],
    creneauxAbsents: ['2026-07-09 10:00-12:00'],
    total: 2,
    accepted: 1,
    rejected: 1,
    rows: [
      { line: 3, label: 'BOURSE', standId: 'BOURSE', action: 'UPDATED', reasons: [], cellulesOuvertes: 1, regles: 1, exceptions: 0, effectifMin: 2, effectifMax: 2 },
      { line: 4, label: 'Inconnu', standId: null, action: 'REJECTED', reasons: ['Aucun stand « Inconnu »'], cellulesOuvertes: 0, regles: 0, exceptions: 0, effectifMin: null, effectifMax: null }
    ],
    warnings: ['1 créneau(x) sans colonne.']
  };
}

describe('ImportGrilleStandsPage', () => {
  const api = { post: vi.fn(), downloadGet: vi.fn(async () => 'ok') };
  const confirm = { ask: vi.fn(async () => true) };
  const store = { reload: vi.fn(async () => undefined) };
  const notifications = { notify: vi.fn() };
  let fixture: ComponentFixture<ImportGrilleStandsPage>;
  let page: PageInternals;

  beforeEach(async () => {
    api.post.mockReset();
    confirm.ask.mockReset();
    confirm.ask.mockResolvedValue(true);
    store.reload.mockClear();
    notifications.notify.mockClear();
    TestBed.resetTestingModule();
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
    fixture = TestBed.createComponent(ImportGrilleStandsPage);
    page = fixture.componentInstance as unknown as PageInternals;
    await fixture.whenStable();
  });

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function chargerEtAnalyser(): Promise<void> {
    api.post.mockResolvedValueOnce(rapport(false));
    (fixture.componentInstance as unknown as { contenu: { set(v: string): void } }).contenu.set('stand;2026-07-08\n;10:00-12:00\nBOURSE;2\n');
    page.nomFichier.set('grille.csv');
    await page.analyser();
    await fixture.whenStable();
  }

  it('previews through the analysis endpoint and shows columns, rows and warnings', async () => {
    await chargerEtAnalyser();

    expect(api.post).toHaveBeenCalledOnce();
    expect((api.post.mock.calls[0] as unknown as [string, { fileName: string }])[0]).toBe('/api/stands/import-grille/analyse');
    expect((api.post.mock.calls[0] as unknown as [string, { fileName: string }])[1].fileName).toBe('grille.csv');
    const text = racine().textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('1 colonne(s) reconnue(s)');
    expect(text).toContain('2026-07-08 montage');
    expect(text).toContain('En-tête illisible');
    expect(text).toContain('1 créneau(x) sans colonne.');
    expect(racine().querySelectorAll('tbody tr')).toHaveLength(2);
    expect(racine().querySelector('tr[data-ligne="3"]')!.textContent).toContain('1 règle(s)');
    expect(racine().querySelector('tr[data-ligne="4"]')!.textContent).toContain('Aucun stand « Inconnu »');
    expect(page.peutImporter()).toBe(true);
  });

  it('imports the same body after a confirmation, reloads the store and reports', async () => {
    await chargerEtAnalyser();
    api.post.mockResolvedValueOnce(rapport(true));

    await page.importer();
    await fixture.whenStable();

    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(api.post).toHaveBeenCalledTimes(2);
    const [url, corps] = api.post.mock.calls[1] as unknown as [string, unknown];
    expect(url).toBe('/api/stands/import-grille');
    expect(corps).toEqual((api.post.mock.calls[0] as unknown as [string, unknown])[1]);
    expect(store.reload).toHaveBeenCalledOnce();
    expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    expect(page.rapport()?.applied).toBe(true);
    // Applied: the import button is gone, nothing to re-import.
    expect(page.peutImporter()).toBe(false);
  });

  it('writes nothing when the confirmation is refused', async () => {
    await chargerEtAnalyser();
    confirm.ask.mockResolvedValue(false);

    await page.importer();

    expect(api.post).toHaveBeenCalledOnce();
    expect(store.reload).not.toHaveBeenCalled();
  });

  it('shows the server refusal in place and keeps the import off', async () => {
    api.post.mockRejectedValueOnce(new Error("L'édition n'a aucun créneau"));
    page.nomFichier.set('grille.csv');
    (fixture.componentInstance as unknown as { contenu: { set(v: string): void } }).contenu.set('x');
    await page.analyser();
    await fixture.whenStable();

    expect(page.erreur()).toContain('aucun créneau');
    expect(page.rapport()).toBeNull();
    expect(page.peutImporter()).toBe(false);
  });
});
