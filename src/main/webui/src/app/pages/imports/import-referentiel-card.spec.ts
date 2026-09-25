// The shared referential import card: what it posts, what it renders of the
// report, and the one thing that must never happen — a write without a preview.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportsApi } from '../../core/api/imports-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { RapportImportReferentiel } from '../../core/models';
import { ImportReferentielCard } from './import-referentiel-card';

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

async function mount(): Promise<ComponentFixture<ImportReferentielCard>> {
  const fixture = TestBed.createComponent(ImportReferentielCard);
  fixture.componentRef.setInput('target', 'STANDS');
  fixture.componentRef.setInput('colonnes', 'Colonnes id, nom, typologies');
  fixture.componentRef.setInput('aide', 'Plusieurs typologies se séparent par « | ».');
  await fixture.whenStable();
  return fixture;
}

describe('ImportReferentielCard', () => {
  const api = { analyse: vi.fn(), importer: vi.fn(), telechargerExemple: vi.fn() };
  const confirm = { ask: vi.fn(async () => true) };
  const notifications = { notify: vi.fn() };
  const store = { reload: vi.fn(async () => undefined) };

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    confirm.ask.mockClear();
    notifications.notify.mockClear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
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
});
