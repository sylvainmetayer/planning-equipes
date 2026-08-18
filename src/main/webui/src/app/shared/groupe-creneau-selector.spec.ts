import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../core/api.service';
import { GroupeCreneau } from '../core/models';
import { ReferenceDataStore } from '../core/reference-data.store';
import { SolverJobService } from '../core/solver-job.service';
import { GroupeCreneauSelector } from './groupe-creneau-selector';

function groupe(id: string, nom: string, actif = false): GroupeCreneau {
  return { id, nom, actif };
}

describe('GroupeCreneauSelector', () => {
  let fixture: ComponentFixture<GroupeCreneauSelector>;
  let store: ReferenceDataStore;
  let solverBusy: boolean;

  beforeEach(() => {
    solverBusy = false;
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get: vi.fn().mockResolvedValue([]), put: vi.fn(), post: vi.fn() } },
        { provide: SolverJobService, useValue: { solverBusy: () => solverBusy } }
      ]
    });
    store = TestBed.inject(ReferenceDataStore);
  });

  function create(): void {
    fixture = TestBed.createComponent(GroupeCreneauSelector);
    // The reload is stubbed out by the fake ApiService; the component keeps
    // whatever the test put in the store.
    (fixture.componentInstance as unknown as { rechargerPage: () => void }).rechargerPage = () => undefined;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders nothing while no group is active', async () => {
    store.groupesCreneaux.set([groupe('A', 'Défaut')]);
    create();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.groupe-creneau-selector')).toBeNull();
  });

  it('names the active group', async () => {
    store.groupesCreneaux.set([groupe('A', 'Défaut'), groupe('B', 'Continu', true)]);
    create();
    await fixture.whenStable();
    expect(text()).toContain('Continu');
  });

  it('activates another group and refreshes the resolution', async () => {
    store.groupesCreneaux.set([groupe('A', 'Défaut'), groupe('B', 'Continu', true)]);
    create();
    await fixture.whenStable();
    const activer = vi.spyOn(store, 'activerGroupeCreneau').mockResolvedValue(undefined);

    await (
      fixture.componentInstance as unknown as { activer: (g: GroupeCreneau) => Promise<void> }
    ).activer(groupe('A', 'Défaut'));

    expect(activer).toHaveBeenCalledWith('A');
  });

  it('does nothing while a solve is running', async () => {
    solverBusy = true;
    store.groupesCreneaux.set([groupe('A', 'Défaut'), groupe('B', 'Continu', true)]);
    create();
    await fixture.whenStable();
    const activer = vi.spyOn(store, 'activerGroupeCreneau').mockResolvedValue(undefined);

    await (
      fixture.componentInstance as unknown as { activer: (g: GroupeCreneau) => Promise<void> }
    ).activer(groupe('A', 'Défaut'));

    expect(activer).not.toHaveBeenCalled();
    const trigger = fixture.nativeElement.querySelector('button[disabled]');
    expect(trigger).not.toBeNull();
  });
});
