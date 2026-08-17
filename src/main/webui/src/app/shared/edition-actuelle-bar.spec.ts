import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionActuelleBar } from './edition-actuelle-bar';
import { ApiService } from '../core/api.service';
import { EditionStore } from '../core/edition.store';
import { Edition } from '../core/models';

function edition(id: string, nom: string, defaut = false): Edition {
  return { id, nom, defaut, creeLe: null };
}

describe('EditionActuelleBar', () => {
  let fixture: ComponentFixture<EditionActuelleBar>;
  let store: EditionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(), put: vi.fn() } }
      ]
    });
    store = TestBed.inject(EditionStore);
    fixture = TestBed.createComponent(EditionActuelleBar);
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders nothing until the current edition is known', async () => {
    store.editions.set([edition('A', 'Année 2025')]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.edition-actuelle-bar')).toBeNull();
  });

  it('names the edition this tab is reading', async () => {
    store.editions.set([edition('A', 'Année 2025'), edition('B', 'Année 2026')]);
    store.courant.set(edition('B', 'Année 2026'));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.edition-actuelle-bar')).not.toBeNull();
    expect(text()).toContain('Année 2026');
  });

  it('hides the switcher when there is nothing to switch to', async () => {
    store.editions.set([edition('B', 'Année 2026', true)]);
    store.courant.set(edition('B', 'Année 2026', true));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[mat-menu-trigger-for], [matMenuTriggerFor]')).toBeNull();
    expect(text()).not.toContain('Changer');
  });

  it('delegates switching to the store', async () => {
    store.editions.set([edition('A', 'Année 2025'), edition('B', 'Année 2026')]);
    store.courant.set(edition('B', 'Année 2026'));
    await fixture.whenStable();
    const basculer = vi.spyOn(store, 'basculer').mockImplementation(() => undefined);

    // `basculer` is protected (template-only API); the test drives it through a
    // structural view of the component instead of loosening its visibility.
    (fixture.componentInstance as unknown as { basculer: (e: Edition) => void }).basculer(
      edition('A', 'Année 2025')
    );

    expect(basculer).toHaveBeenCalledWith(edition('A', 'Année 2025'));
  });
});
