import { LOCALE_ID, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionActuelleBar } from './edition-actuelle-bar';
import { ApiService } from '../core/api.service';
import { EditionStore } from '../core/edition.store';
import { Edition } from '../core/models';
import { seedStore } from '../core/testing/seed-store';

function edition(id: string, nom: string, active = false): Edition {
  return { id, nom, active, creeLe: null };
}

describe('EditionActuelleBar', () => {
  let fixture: ComponentFixture<EditionActuelleBar>;
  let store: EditionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'fr' },
        // The strip now embeds the groupe de créneaux selector, which fetches
        // its own (here empty) list on creation.
        {
          provide: ApiService,
          useValue: { get: vi.fn().mockResolvedValue([]), put: vi.fn(), post: vi.fn() },
        },
      ],
    });
    store = TestBed.inject(EditionStore);
    fixture = TestBed.createComponent(EditionActuelleBar);
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders nothing until the current edition is known', async () => {
    seedStore(store, 'editions', [edition('A', 'Année 2025')]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.edition-actuelle-bar')).toBeNull();
  });

  it('names the edition this tab is reading', async () => {
    seedStore(store, 'editions', [edition('A', 'Année 2025'), edition('B', 'Année 2026')]);
    seedStore(store, 'courant', edition('B', 'Année 2026'));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.edition-actuelle-bar')).not.toBeNull();
    expect(text()).toContain('Année 2026');
  });

  it('hides the switcher when there is nothing to switch to', async () => {
    seedStore(store, 'editions', [edition('B', 'Année 2026', true)]);
    seedStore(store, 'courant', edition('B', 'Année 2026', true));
    await fixture.whenStable();
    expect(
      fixture.nativeElement.querySelector('[mat-menu-trigger-for], [matMenuTriggerFor]'),
    ).toBeNull();
    expect(text()).not.toContain('Changer');
  });

  it('delegates switching to the store', async () => {
    seedStore(store, 'editions', [edition('A', 'Année 2025'), edition('B', 'Année 2026')]);
    seedStore(store, 'courant', edition('B', 'Année 2026'));
    await fixture.whenStable();
    const basculer = vi.spyOn(store, 'basculer').mockImplementation(() => undefined);

    // `basculer` is protected (template-only API); the test reaches it by name,
    // type-checked, instead of loosening its visibility.
    fixture.componentInstance['basculer'](edition('A', 'Année 2025'));

    expect(basculer).toHaveBeenCalledWith(edition('A', 'Année 2025'));
  });

  it('says when the edition read here is not the active one', async () => {
    seedStore(store, 'editions', [edition('A', 'Année 2025', true), edition('B', 'Année 2026')]);
    seedStore(store, 'courant', edition('B', 'Année 2026'));
    await fixture.whenStable();
    expect(text()).toContain('inactive');

    seedStore(store, 'courant', edition('A', 'Année 2025', true));
    await fixture.whenStable();
    expect(text()).not.toContain('inactive');
  });

  it('shows the banner only when the editions state asks for a decision', async () => {
    seedStore(store, 'editions', [edition('A', 'Année 2025', true)]);
    seedStore(store, 'courant', edition('A', 'Année 2025', true));
    await fixture.whenStable();
    expect(text()).not.toContain('Action requise');

    seedStore(store, 'situations', [
      {
        type: 'ACTIVE_TERMINEE',
        edition: edition('A', 'Année 2025', true),
        premierJour: '2025-07-10',
        dernierJour: '2025-07-12',
      },
    ]);
    await fixture.whenStable();
    expect(text()).toContain('Année 2025 est terminée depuis le 12/07/2025 et reste active.');
    expect(text()).toContain('Désactiver');
    expect(text()).not.toContain('Activer la suivante');
  });

  it('hides a dismissed situation only, and remembers it in the browser', async () => {
    localStorage.clear();
    seedStore(store, 'courant', edition('A', 'Année 2025', true));
    seedStore(store, 'situations', [
      {
        type: 'ACTIVE_TERMINEE',
        edition: edition('A', 'Année 2025', true),
        premierJour: '2025-07-10',
        dernierJour: '2025-07-12',
      },
      {
        type: 'INACTIVE_IMMINENTE',
        edition: edition('B', 'Année 2026'),
        premierJour: '2026-07-10',
        dernierJour: '2026-07-12',
      },
    ]);
    await fixture.whenStable();
    expect(text()).toContain('Activer la suivante');

    fixture.componentInstance['masquer']('ACTIVE_TERMINEE.A');
    await fixture.whenStable();
    expect(text()).not.toContain('reste active');
    expect(text()).toContain('Année 2026 commence le 10/07/2026');
    expect(localStorage.getItem('planning.bandeau-edition-masque.ACTIVE_TERMINEE.A')).toBe('true');

    // Another type on the same edition comes back.
    seedStore(store, 'situations', [
      {
        type: 'INACTIVE_EN_COURS',
        edition: edition('A', 'Année 2025'),
        premierJour: '2025-07-10',
        dernierJour: '2025-07-12',
      },
    ]);
    await fixture.whenStable();
    expect(text()).toContain('est en cours mais inactive');
    localStorage.clear();
  });

  it('names the phase of the edition, and bannerises an unblocked structure', async () => {
    const publiee = { ...edition('A', 'Année 2025', true), phase: 'PUBLIEE' as const };
    seedStore(store, 'courant', publiee);
    await fixture.whenStable();
    expect(text()).toContain('phase Publiée');
    expect(fixture.nativeElement.querySelector('[data-bandeau="structure-debloquee"]')).toBeNull();

    seedStore(store, 'courant', { ...publiee, structureDebloquee: true });
    await fixture.whenStable();
    expect(text()).toContain('Structure débloquée');
    expect(text()).toContain('Refiger');

    // The flag means nothing outside Publiée.
    seedStore(store, 'courant', { ...publiee, phase: 'CALCUL', structureDebloquee: true });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[data-bandeau="structure-debloquee"]')).toBeNull();
  });
});
