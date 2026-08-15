import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GroupeActuelBar } from './groupe-actuel-bar';
import { ApiService } from '../core/api.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { GroupeCreneau } from '../core/models';

describe('GroupeActuelBar', () => {
  let fixture: ComponentFixture<GroupeActuelBar>;
  let store: PlanningResolutionStore;
  const put = vi.fn().mockResolvedValue({});

  beforeEach(() => {
    put.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { put, get: vi.fn() } }
      ]
    });
    store = TestBed.inject(PlanningResolutionStore);
    fixture = TestBed.createComponent(GroupeActuelBar);
  });

  function setGroupes(groupes: GroupeCreneau[]): void {
    store.groupesCreneaux.set(groupes);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders nothing while no group is active', async () => {
    setGroupes([{ id: 'A', nom: 'Année 2025', actif: false }]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.groupe-actuel-bar')).toBeNull();
  });

  it('names the active group', async () => {
    setGroupes([
      { id: 'A', nom: 'Année 2025', actif: false },
      { id: 'B', nom: 'Année 2026', actif: true }
    ]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.groupe-actuel-bar')).not.toBeNull();
    expect(text()).toContain('Année 2026');
  });

  it('hides the switcher when there is nothing to switch to', async () => {
    setGroupes([{ id: 'B', nom: 'Année 2026', actif: true }]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.groupe-actuel-bar-switch')).toBeNull();
  });

  it('activates the picked group then reloads the page', async () => {
    setGroupes([
      { id: 'A', nom: 'Année 2025', actif: false },
      { id: 'B', nom: 'Année 2026', actif: true }
    ]);
    await fixture.whenStable();
    const reload = vi.fn();
    // `reloadPage`/`basculer` are protected (template-only API); the test drives
    // them through a structural view of the component instead of loosening them.
    const bar = fixture.componentInstance as unknown as {
      reloadPage: () => void;
      basculer: (groupe: GroupeCreneau) => Promise<void>;
    };
    bar.reloadPage = reload;

    await bar.basculer({ id: 'A', nom: 'Année 2025', actif: false });

    expect(put).toHaveBeenCalledWith('/api/groupes-creneaux/A/actif', {});
    expect(reload).toHaveBeenCalledOnce();
  });
});
