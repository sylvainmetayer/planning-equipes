import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GroupeActuelBar } from './groupe-actuel-bar';
import { ApiService } from '../core/api.service';
import { GroupeStore } from '../core/groupe.store';
import { Groupe } from '../core/models';

function groupe(id: string, nom: string, defaut = false): Groupe {
  return { id, nom, defaut, creeLe: null };
}

describe('GroupeActuelBar', () => {
  let fixture: ComponentFixture<GroupeActuelBar>;
  let store: GroupeStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(), put: vi.fn() } }
      ]
    });
    store = TestBed.inject(GroupeStore);
    fixture = TestBed.createComponent(GroupeActuelBar);
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders nothing until the current group is known', async () => {
    store.groupes.set([groupe('A', 'Année 2025')]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.groupe-actuel-bar')).toBeNull();
  });

  it('names the group this tab is reading', async () => {
    store.groupes.set([groupe('A', 'Année 2025'), groupe('B', 'Année 2026')]);
    store.courant.set(groupe('B', 'Année 2026'));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.groupe-actuel-bar')).not.toBeNull();
    expect(text()).toContain('Année 2026');
  });

  it('hides the switcher when there is nothing to switch to', async () => {
    store.groupes.set([groupe('B', 'Année 2026', true)]);
    store.courant.set(groupe('B', 'Année 2026', true));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[mat-menu-trigger-for], [matMenuTriggerFor]')).toBeNull();
    expect(text()).not.toContain('Changer');
  });

  it('delegates switching to the store', async () => {
    store.groupes.set([groupe('A', 'Année 2025'), groupe('B', 'Année 2026')]);
    store.courant.set(groupe('B', 'Année 2026'));
    await fixture.whenStable();
    const basculer = vi.spyOn(store, 'basculer').mockImplementation(() => undefined);

    // `basculer` is protected (template-only API); the test drives it through a
    // structural view of the component instead of loosening its visibility.
    (fixture.componentInstance as unknown as { basculer: (g: Groupe) => void }).basculer(
      groupe('A', 'Année 2025')
    );

    expect(basculer).toHaveBeenCalledWith(groupe('A', 'Année 2025'));
  });
});
