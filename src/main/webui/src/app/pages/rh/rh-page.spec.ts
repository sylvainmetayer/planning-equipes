import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { RhApi } from '../../core/api/rh-api';
import { EditionAccessible } from '../../core/models';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { RhPage } from './rh-page';

describe('RhPage', () => {
  function setup(editions: EditionAccessible[]) {
    const api = fakeOf<RhApi>({
      editions: () => Promise.resolve(editions),
      rapport: () => Promise.reject(new Error('rien')),
    });
    const adminApi = fakeOf<AdminApi>({
      logout: vi.fn(() => Promise.resolve({ urlDeconnexion: null })),
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(RhApi, api),
        provideFake(AdminApi, adminApi),
      ],
    });
    return { api };
  }

  async function render() {
    const fixture = TestBed.createComponent(RhPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('reads the report of the first edition its rights name', async () => {
    const { api } = setup([
      { id: 'E1', nom: 'Première' },
      { id: 'E2', nom: 'Seconde' },
    ]);
    await render();
    expect(api.rapport).toHaveBeenCalledWith('E1');
  });

  it('says so when no edition is named', async () => {
    const { api } = setup([]);
    const fixture = await render();
    expect(api.rapport).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Aucune édition à afficher');
  });
});
