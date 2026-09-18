// The day-templates card: what it reads, the calendar it writes as a whole,
// and the apply gesture that previews before the dialog writes.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { EtatJourneesTypes } from '../../core/models';
import { JourneesTypesCard } from './journees-types-card';

const ETAT: EtatJourneesTypes = {
  journeesTypes: [
    {
      id: 1,
      nom: 'Jour normal',
      vacations: [
        { heureDebut: '09:00:00', heureFin: '12:00:00', couverturePause: false },
        { heureDebut: '12:00:00', heureFin: '13:00:00', couverturePause: true },
      ],
    },
    {
      id: 2,
      nom: 'Nocturne',
      vacations: [{ heureDebut: '14:00:00', heureFin: '00:00:00', couverturePause: false }],
    },
  ],
  calendrier: [
    { date: '2027-07-12', journeeTypeId: 1 },
    { date: '2027-07-13', journeeTypeId: 2 },
  ],
  datesEnEcart: ['2027-07-13'],
  datesSousConsigne: [],
};

type CardInternals = {
  du: { set: (v: string) => void };
  au: { set: (v: string) => void };
  journeeTypeChoisie: { set: (v: number | null) => void };
  ajouterDates: () => Promise<void>;
  retirer: (date: string) => Promise<void>;
  appliquer: () => Promise<void>;
};

describe('JourneesTypesCard', () => {
  const api = {
    etat: vi.fn(),
    setCalendrier: vi.fn(),
    previewApplication: vi.fn(),
    apply: vi.fn(),
    delete: vi.fn(),
    previewReconnaissance: vi.fn(),
    reconnaitre: vi.fn(),
  };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(null) })) };
  const notifications = { notify: vi.fn() };
  const confirm = { ask: vi.fn(async () => true) };

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    dialog.open.mockClear();
    notifications.notify.mockClear();
    api.etat.mockResolvedValue(ETAT);
    api.setCalendrier.mockImplementation(async (calendrier) => ({
      ...ETAT,
      calendrier,
      datesEnEcart: [],
    }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: JourneesTypesApi, useValue: api },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
      ],
    });
  });

  async function monter(): Promise<{
    fixture: ComponentFixture<JourneesTypesCard>;
    card: CardInternals;
    racine: HTMLElement;
  }> {
    const fixture = TestBed.createComponent(JourneesTypesCard);
    await fixture.whenStable();
    return {
      fixture,
      card: fixture.componentInstance as unknown as CardInternals,
      racine: fixture.nativeElement as HTMLElement,
    };
  }

  it('shows the templates as chips, the calendar with its drift, and the bounds', async () => {
    const { racine } = await monter();

    expect(racine.querySelectorAll('.journee-type-item')).toHaveLength(2);
    expect(racine.querySelectorAll('.vacation-chip-relais')).toHaveLength(1);
    expect(racine.querySelectorAll('.journees-types-table tbody tr')).toHaveLength(2);
    expect(racine.querySelectorAll('.ligne-en-ecart')).toHaveLength(1);
    expect(racine.querySelector('.journees-types-bornes')!.textContent).toContain('2027-07-12');
    expect(racine.querySelector('.journees-types-ecarts')!.textContent).toContain('1 date(s)');
  });

  it('adds a range of dates to the chosen template and writes the calendar as a whole', async () => {
    const { fixture, card } = await monter();

    card.du.set('2027-07-14');
    card.au.set('2027-07-15');
    card.journeeTypeChoisie.set(2);
    await fixture.whenStable();
    await card.ajouterDates();

    expect(api.setCalendrier).toHaveBeenCalledOnce();
    expect(api.setCalendrier.mock.calls[0][0]).toEqual([
      { date: '2027-07-12', journeeTypeId: 1 },
      { date: '2027-07-13', journeeTypeId: 2 },
      { date: '2027-07-14', journeeTypeId: 2 },
      { date: '2027-07-15', journeeTypeId: 2 },
    ]);
  });

  it('removes a date without touching the others', async () => {
    const { card } = await monter();

    await card.retirer('2027-07-12');

    expect(api.setCalendrier.mock.calls[0][0]).toEqual([{ date: '2027-07-13', journeeTypeId: 2 }]);
  });

  it('previews before opening the application dialog, and writes nothing itself', async () => {
    const apercu = {
      crees: 3,
      supprimes: 0,
      conserves: 1,
      misAJour: 0,
      aucunChangement: false,
    };
    api.previewApplication.mockResolvedValue(apercu);
    const { card } = await monter();

    await card.appliquer();

    expect(api.previewApplication).toHaveBeenCalledOnce();
    expect(api.apply).not.toHaveBeenCalled();
    expect(dialog.open).toHaveBeenCalledOnce();
    const [, config] = dialog.open.mock.calls[0] as unknown as [
      unknown,
      { data: { apercu: unknown } },
    ];
    expect(config.data.apercu).toBe(apercu);
  });
});
