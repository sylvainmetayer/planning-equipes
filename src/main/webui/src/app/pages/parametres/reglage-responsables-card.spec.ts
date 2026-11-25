import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ResponsableApi } from '../../core/api/responsable-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReglageResponsablesCard } from './reglage-responsables-card';

describe('ReglageResponsablesCard', () => {
  const api = {
    reglages: vi.fn(),
    saveReglages: vi.fn(),
  };
  const store = {
    stands: vi.fn(),
    reload: vi.fn(),
  };

  beforeEach(() => {
    api.reglages.mockReset();
    api.saveReglages.mockReset();
    store.stands.mockReset();
    store.reload.mockReset();
    api.reglages.mockResolvedValue({
      parDefaut: { nomsVisibles: true, ouvertureLe: null },
      parStand: { S1: { nomsVisibles: false, ouvertureLe: null } },
    });
    api.saveReglages.mockImplementation((corps: unknown) => Promise.resolve(corps));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ResponsableApi, useValue: api },
        { provide: ReferenceDataStore, useValue: store },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(ReglageResponsablesCard);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('loads the stands before reading the setting when the page was opened cold', async () => {
    store.stands.mockReturnValueOnce([]).mockReturnValue([{ id: 'S1', nom: 'Buvette' }]);
    store.reload.mockResolvedValue(undefined);
    const fixture = await render();
    expect(store.reload).toHaveBeenCalledWith(['stands']);
    await fixture.componentInstance['save']();
    const corps = api.saveReglages.mock.calls[0][0];
    expect(corps.parStand).toEqual({ S1: { nomsVisibles: false, ouvertureLe: null } });
  });

  it('sends nothing about any stand when none is known, so none is erased', async () => {
    store.stands.mockReturnValue([]);
    store.reload.mockResolvedValue(undefined);
    const fixture = await render();
    await fixture.componentInstance['save']();
    expect(api.saveReglages.mock.calls[0][0].parStand).toEqual({});
  });
});
