import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ResponsableApi } from '../../core/api/responsable-api';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { ReglagesResponsables, Stand } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReglageResponsablesCard } from './reglage-responsables-card';

function stand(id: string, nom: string): Stand {
  return {
    id,
    nom,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

const REGLAGES: ReglagesResponsables = {
  parDefaut: { nomsVisibles: true, ouvertureLe: null },
  parStand: { S1: { nomsVisibles: false, ouvertureLe: null } },
};

describe('ReglageResponsablesCard', () => {
  function setup(options: {
    stands?: Stand[];
    reglages?: () => Promise<ReglagesResponsables>;
    save?: (corps: ReglagesResponsables) => Promise<ReglagesResponsables>;
    reload?: () => Promise<void>;
  }) {
    const stands = signal(options.stands ?? []);
    const api = fakeOf<ResponsableApi>({
      reglages: options.reglages ?? (() => Promise.resolve(REGLAGES)),
      saveReglages: options.save ?? ((corps) => Promise.resolve(corps)),
    });
    const store = fakeOf<ReferenceDataStore>({
      stands,
      reload: options.reload ?? (() => Promise.resolve(undefined)),
    });
    const notifications = fakeOf<NotificationService>({ notify: vi.fn() });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(ResponsableApi, api),
        provideFake(ReferenceDataStore, store),
        provideFake(NotificationService, notifications),
      ],
    });
    return { api, store, notifications, stands };
  }

  async function render() {
    const fixture = TestBed.createComponent(ReglageResponsablesCard);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('loads the stands before reading the setting when the page was opened cold', async () => {
    const { api, store, stands } = setup({
      reload: () => {
        stands.set([stand('S1', 'Buvette')]);
        return Promise.resolve();
      },
    });
    const fixture = await render();
    expect(store.reload).toHaveBeenCalledWith(['stands']);
    await fixture.componentInstance['save']();
    expect(api.saveReglages.mock.calls[0][0].parStand).toEqual({
      S1: { nomsVisibles: false, ouvertureLe: null },
    });
  });

  it('sends nothing about any stand when none is known, so none is erased', async () => {
    const { api } = setup({});
    const fixture = await render();
    await fixture.componentInstance['save']();
    expect(api.saveReglages.mock.calls[0][0].parStand).toEqual({});
  });

  it('does not reload the stands when the store already holds them', async () => {
    const { store } = setup({ stands: [stand('S1', 'Buvette')] });
    await render();
    expect(store.reload).not.toHaveBeenCalled();
  });

  it('shows the default, one row per stand and the stand name as its label', async () => {
    setup({ stands: [stand('S1', 'Buvette'), stand('S2', 'Tir')] });
    const fixture = await render();
    const card = fixture.componentInstance;
    expect(card['defaut']()).toEqual({ noms: true, ouverture: '' });
    expect(card['lignes']().map((l) => [l.standId, l.mode])).toEqual([
      ['S1', 'horaires'],
      ['S2', 'herite'],
    ]);
    expect(card['standNom']('S2')).toBe('Tir');
    expect(card['standNom']('inconnu')).toBe('inconnu');
    expect(card['ouvertureLabel']('S1')).toContain('Buvette');
    const labels = [...fixture.nativeElement.querySelectorAll('mat-label')].map(
      (el: Element) => el.textContent,
    );
    expect(labels).toEqual(expect.arrayContaining(['Buvette', 'Tir']));
  });

  it('shows the opening date of the default only while names are shown', async () => {
    setup({ stands: [stand('S1', 'Buvette')] });
    const fixture = await render();
    const ouverture = () => fixture.nativeElement.querySelector('input[name="ouverture"]');
    expect(ouverture()).not.toBeNull();
    fixture.componentInstance['patchDefaut']({ noms: false });
    fixture.detectChanges();
    expect(ouverture()).toBeNull();
  });

  it('saves the exceptions and the opening dates the screen holds', async () => {
    const { api, notifications } = setup({
      stands: [stand('S1', 'Buvette'), stand('S2', 'Tir')],
    });
    const fixture = await render();
    const card = fixture.componentInstance;
    card['patchDefaut']({ ouverture: '2026-08-01T09:00' });
    card['patchLigne']('S1', { mode: 'noms', ouverture: '2026-08-02T10:30' });
    card['patchLigne']('S2', { mode: 'herite' });
    await card['save']();

    const corps = api.saveReglages.mock.calls[0][0];
    expect(corps.parDefaut).toEqual({
      nomsVisibles: true,
      ouvertureLe: new Date('2026-08-01T09:00').toISOString(),
    });
    expect(corps.parStand).toEqual({
      S1: { nomsVisibles: true, ouvertureLe: new Date('2026-08-02T10:30').toISOString() },
      S2: null,
    });
    expect(notifications.notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'success' }),
    );
    expect(card['busy']()).toBe(false);
    expect(card['error']()).toBe('');
  });

  it('adopts the answer of the server after a save', async () => {
    setup({
      stands: [stand('S1', 'Buvette')],
      save: () =>
        Promise.resolve({
          parDefaut: { nomsVisibles: false, ouvertureLe: null },
          parStand: { S1: { nomsVisibles: true, ouvertureLe: null } },
        }),
    });
    const fixture = await render();
    const card = fixture.componentInstance;
    await card['save']();
    expect(card['defaut']().noms).toBe(false);
    expect(card['lignes']()[0].mode).toBe('noms');
  });

  it('says why the save failed and notifies nothing', async () => {
    const { notifications } = setup({
      stands: [stand('S1', 'Buvette')],
      save: () => Promise.reject(new Error('Conflit')),
    });
    const fixture = await render();
    const card = fixture.componentInstance;
    await card['save']();
    expect(card['error']()).not.toBe('');
    expect(notifications.notify).not.toHaveBeenCalled();
    expect(card['busy']()).toBe(false);
  });

  it('says why the setting could not be read', async () => {
    setup({
      stands: [stand('S1', 'Buvette')],
      reglages: () => Promise.reject(new Error('Hors service')),
    });
    const fixture = await render();
    expect(fixture.componentInstance['error']()).not.toBe('');
    expect(fixture.componentInstance['lignes']()).toEqual([]);
  });
});
