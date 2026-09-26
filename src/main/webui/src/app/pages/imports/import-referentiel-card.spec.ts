// The shared referential import card: what it posts, what it renders of the
// report, and the one thing that must never happen — a write without a preview.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportsApi } from '../../core/api/imports-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { RapportImportReferentiel } from '../../core/models';
import {
  ImportReferentielCard,
  referentialParamsOf,
  referentialRouteOf,
} from './import-referentiel-card';

function rapport(partial: Partial<RapportImportReferentiel> = {}): RapportImportReferentiel {
  return {
    applied: false,
    cible: 'STANDS',
    columns: ['id', 'nom', 'typologies'],
    separator: ';',
    total: 2,
    accepted: 1,
    rejected: 1,
    created: 1,
    updated: 0,
    typologiesCreees: ['INCONNUE'],
    rows: [
      {
        line: 2,
        id: 'S1',
        libelle: 'Stand un',
        action: 'CREE',
        raisons: [],
        details: ['La typologie « INCONNUE » sera créée, son libellé reprenant son identifiant.'],
      },
      {
        line: 3,
        id: null,
        libelle: null,
        action: 'REFUSE',
        raisons: ['La colonne « id » est vide.'],
        details: [],
      },
    ],
    ...partial,
  };
}

async function mount(
  target: RapportImportReferentiel['cible'] = 'STANDS',
): Promise<ComponentFixture<ImportReferentielCard>> {
  const fixture = TestBed.createComponent(ImportReferentielCard);
  fixture.componentRef.setInput('target', target);
  fixture.componentRef.setInput('colonnes', 'Colonnes id, nom, typologies');
  fixture.componentRef.setInput('aide', 'Plusieurs typologies se séparent par « | ».');
  await fixture.whenStable();
  return fixture;
}

describe('ImportReferentielCard', () => {
  const api = { analyse: vi.fn(), importer: vi.fn(), telechargerExemple: vi.fn() };
  const confirm = { ask: vi.fn(async () => true) };
  const notifications = { notify: vi.fn() };
  const store = {
    reload: vi.fn(async () => undefined),
    typologies: () => [],
    emplacements: () => [],
    stands: () => [
      { id: 's-1', code: 'S1', nom: 'Stand un' },
      { id: 's-2', code: null, nom: 'Stand Deux' },
      { id: 's-3', code: 'S3', nom: 'Stand trois' },
    ],
    creneaux: () => [],
  };

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    confirm.ask.mockClear();
    confirm.ask.mockResolvedValue(true);
    notifications.notify.mockClear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ImportsApi, useValue: api },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        { provide: ReferenceDataStore, useValue: store },
      ],
    });
  });

  type Internals = {
    nomFichier: { set: (v: string) => void };
    analyser: () => Promise<void>;
    importer: () => Promise<void>;
    peutImporter: () => boolean;
    onColle: (csv: string) => Promise<void>;
  };

  it('renders each row with its action, its reason and the typologies about to be created', async () => {
    api.analyse.mockResolvedValue(rapport());
    const fixture = await mount();
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('stands.csv');
    await card.analyser();
    await fixture.whenStable();

    const racine = fixture.nativeElement as HTMLElement;
    expect(racine.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(racine.querySelector('.import-ligne-creation')).not.toBeNull();
    expect(racine.querySelector('.import-ligne-rejet')!.textContent).toContain('« id » est vide');
    expect(racine.textContent).toContain('INCONNUE');
    expect(api.analyse.mock.calls[0][0]).toBe('STANDS');
  });

  it('writes only what the preview accepted, and only after a confirmation', async () => {
    api.analyse.mockResolvedValue(rapport());
    api.importer.mockResolvedValue(rapport({ applied: true, rejected: 1, created: 1 }));
    const fixture = await mount();
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('stands.csv');
    await card.analyser();
    await fixture.whenStable();
    expect(card.peutImporter()).toBe(true);

    await card.importer();

    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(api.importer).toHaveBeenCalledOnce();
    expect(store.reload).toHaveBeenCalled();
    expect(notifications.notify).toHaveBeenCalledOnce();
    // Applied: the button closes rather than offering to write twice.
    expect(card.peutImporter()).toBe(false);
  });

  it('refuses to write when the operator says no', async () => {
    api.analyse.mockResolvedValue(rapport());
    confirm.ask.mockResolvedValue(false);
    const fixture = await mount();
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('stands.csv');
    await card.analyser();
    await card.importer();

    expect(api.importer).not.toHaveBeenCalled();
  });

  it('offers nothing to write when every row is refused', async () => {
    api.analyse.mockResolvedValue(rapport({ accepted: 0, created: 0, rejected: 2 }));
    const fixture = await mount();
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('stands.csv');
    await card.analyser();
    await fixture.whenStable();

    expect(card.peutImporter()).toBe(false);
  });

  /**
   * After a write, the rows are one click away: the screen of the referential
   * they fill, narrowed to them — found again by their code, else their name.
   */
  // The locations became the « Lieux » tab of the Stands page: their rows are there.
  it('sends the imported locations to the Lieux tab of the Stands page', () => {
    expect(referentialRouteOf('EMPLACEMENTS')).toBe('/stands');
    expect(referentialParamsOf('EMPLACEMENTS')).toEqual({ onglet: 'lieux' });
    expect(referentialParamsOf('STANDS')).toEqual({});
  });

  it('links to the rows written, on their screen, narrowed to them', async () => {
    api.analyse.mockResolvedValue(rapport());
    const written = rapport({ applied: true, created: 1, updated: 1 });
    written.rows = [
      { ...written.rows[0] },
      {
        line: 4,
        id: 'stand deux',
        libelle: 'Stand deux',
        action: 'MIS_A_JOUR',
        raisons: [],
        details: [],
      },
      { ...written.rows[1] },
    ];
    api.importer.mockResolvedValue(written);
    const fixture = await mount();
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('stands.csv');
    await card.analyser();
    await card.importer();
    await fixture.whenStable();

    const lien = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).find(
      (each) => (each.textContent ?? '').includes('Voir les 2 lignes importées'),
    );
    expect(lien?.getAttribute('href')).toBe('/stands?ids=s-1,s-2');
  });

  /** No list filters the day templates: the link says it opens the whole screen. */
  it('words the link after the referential when its rows cannot be singled out', async () => {
    const modeles = rapport({
      cible: 'JOURNEES_TYPES',
      rows: [
        {
          line: 2,
          id: 'Semaine',
          libelle: '09:00-12:00',
          action: 'CREE',
          raisons: [],
          details: [],
        },
      ],
    });
    api.analyse.mockResolvedValue(modeles);
    api.importer.mockResolvedValue({ ...modeles, applied: true });
    const fixture = await mount('JOURNEES_TYPES');
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('journees.csv');
    await card.analyser();
    await card.importer();
    await fixture.whenStable();

    const lien = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).find(
      (each) => (each.textContent ?? '').includes('Voir les journées types'),
    );
    expect(lien?.getAttribute('href')).toBe('/creneaux');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('lignes importées');
  });

  /** Inside the dialog of the Stands screen, the rows are on the page it closes onto. */
  it('leaves the link out when the host is the referential screen itself', async () => {
    api.analyse.mockResolvedValue(rapport());
    api.importer.mockResolvedValue(rapport({ applied: true, created: 3 }));
    const fixture = await mount();
    fixture.componentRef.setInput('lienReferentiel', false);
    const emitted = vi.fn();
    fixture.componentInstance.imported.subscribe(emitted);
    const card = fixture.componentInstance as unknown as Internals;

    card.nomFichier.set('stands.csv');
    await card.analyser();
    await card.importer();
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('lignes importées');
    expect(emitted).toHaveBeenCalledOnce();
  });

  it('previews pasted cells exactly as it would a file', async () => {
    api.analyse.mockResolvedValue(rapport());
    const fixture = await mount();
    const card = fixture.componentInstance as unknown as Internals;

    await card.onColle('"code";"nom"\n"S1";"Stand un"\n');

    expect(api.analyse).toHaveBeenCalledWith('STANDS', {
      fileName: 'collage.csv',
      content: '"code";"nom"\n"S1";"Stand un"\n',
    });
  });
});
