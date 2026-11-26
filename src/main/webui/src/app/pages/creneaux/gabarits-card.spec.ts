// The day-templates card: what it reads, the calendar it writes as a whole,
// and the apply gesture that previews before the dialog writes.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../../core/api/editions-api';
import { GenerateurCreneauxApi } from '../../core/api/generateur-creneaux-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { EtatGel, EtatGenerateur } from '../../core/models';
import { GabaritsApplicationDialog } from './gabarits-application-dialog';
import { GabaritsCard } from './gabarits-card';

const ETAT: EtatGenerateur = {
  gabarits: [
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
    { date: '2027-07-12', gabaritId: 1 },
    { date: '2027-07-13', gabaritId: 2 },
  ],
  datesEnEcart: ['2027-07-13'],
  datesSousConsigne: [],
};

async function mount(): Promise<{
  fixture: ComponentFixture<GabaritsCard>;
  card: GabaritsCard;
  racine: HTMLElement;
}> {
  const fixture = TestBed.createComponent(GabaritsCard);
  await fixture.whenStable();
  return {
    fixture,
    card: fixture.componentInstance,
    racine: fixture.nativeElement as HTMLElement,
  };
}

describe('GabaritsCard', () => {
  const api = {
    etat: vi.fn(),
    setCalendrier: vi.fn(),
    withdraw: vi.fn(),
    preview: vi.fn(),
    apply: vi.fn(),
    delete: vi.fn(),
    previewReconnaissance: vi.fn(),
    reconnaitre: vi.fn(),
  };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(null) })) };
  const notifications = { notify: vi.fn() };
  const confirm = { ask: vi.fn(() => Promise.resolve(true)) };
  /** What `GET /api/editions/courant/gel` answers; nothing frozen unless a test says so. */
  let gel: EtatGel[] = [];

  beforeEach(() => {
    for (const stub of Object.values(api)) {
      stub.mockReset();
    }
    dialog.open.mockClear();
    notifications.notify.mockClear();
    gel = [];
    api.etat.mockResolvedValue(ETAT);
    api.setCalendrier.mockImplementation((calendrier) =>
      Promise.resolve({
        ...ETAT,
        calendrier,
        datesEnEcart: [],
      }),
    );
    api.withdraw.mockImplementation((dates: string[]) =>
      Promise.resolve({
        ...ETAT,
        calendrier: ETAT.calendrier.filter((each) => !dates.includes(each.date)),
        datesEnEcart: [],
      }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: GenerateurCreneauxApi, useValue: api },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        { provide: EditionsApi, useValue: { gel: vi.fn(() => Promise.resolve(gel)) } },
      ],
    });
  });

  it('shows the templates as chips, the calendar with its drift, and the bounds', async () => {
    const { racine } = await mount();

    expect(racine.querySelectorAll('.gabarit-item')).toHaveLength(2);
    expect(racine.querySelectorAll('.vacation-chip-relais')).toHaveLength(1);
    expect(racine.querySelectorAll('.gabarits-table tbody tr')).toHaveLength(2);
    expect(racine.querySelectorAll('.ligne-en-ecart')).toHaveLength(1);
    expect(racine.querySelector('.gabarits-bornes')!.textContent).toContain('2027-07-12');
    expect(racine.querySelector('.gabarits-ecarts')!.textContent).toContain('1 date(s)');
  });

  it('adds a range of dates to the chosen template and writes the calendar as a whole', async () => {
    const { fixture, card } = await mount();

    card['du'].set('2027-07-14');
    card['au'].set('2027-07-15');
    card['gabaritChoisie'].set(2);
    await fixture.whenStable();
    await card['ajouterDates']();

    expect(api.setCalendrier).toHaveBeenCalledOnce();
    expect(api.setCalendrier.mock.calls[0][0]).toEqual([
      { date: '2027-07-12', gabaritId: 1 },
      { date: '2027-07-13', gabaritId: 2 },
      { date: '2027-07-14', gabaritId: 2 },
      { date: '2027-07-15', gabaritId: 2 },
    ]);
  });

  it('removes a date without touching the others', async () => {
    const { card } = await mount();

    await card['retirer']('2027-07-12');

    expect(api.withdraw).toHaveBeenCalledExactlyOnceWith(['2027-07-12']);
    expect(api.setCalendrier).not.toHaveBeenCalled();
  });

  it('previews before opening the application dialog, and writes nothing itself', async () => {
    const apercu = {
      crees: 3,
      supprimes: 0,
      conserves: 1,
      misAJour: 0,
      aucunChangement: false,
    };
    api.preview.mockResolvedValue({ source: 'GABARITS', application: apercu });
    const { card } = await mount();

    await card['appliquer']();

    expect(api.preview).toHaveBeenCalledExactlyOnceWith({ source: 'GABARITS' });
    expect(api.apply).not.toHaveBeenCalled();
    expect(dialog.open).toHaveBeenCalledOnce();
    expect(dialog.open).toHaveBeenCalledWith(
      GabaritsApplicationDialog,
      expect.objectContaining({ data: { apercu } }),
    );
  });

  it('greys out applying the calendar under a timeslots freeze, and leaves the calendar open', async () => {
    gel = [
      { famille: 'CRENEAUX', libelle: 'Créneaux', fige: true, figeLe: '2026-07-01T08:00:00Z' },
    ];
    const { fixture, card, racine } = await mount();
    await fixture.whenStable();

    const applyButton = Array.from(racine.querySelectorAll<HTMLButtonElement>('button')).find(
      (each) => each.textContent?.includes('Appliquer le calendrier'),
    )!;
    expect(applyButton.disabled).toBe(true);
    await card['appliquer']();
    expect(api.preview).not.toHaveBeenCalled();
    expect(dialog.open).not.toHaveBeenCalled();

    // The templates and the calendar are not timeslots: writing them stays open.
    await card['retirer']('2027-07-12');
    expect(api.withdraw).toHaveBeenCalledOnce();
  });

  it('leaves applying the calendar open under a stands freeze', async () => {
    gel = [{ famille: 'STANDS', libelle: 'Stands', fige: true, figeLe: '2026-07-01T08:00:00Z' }];
    const { fixture, racine } = await mount();
    await fixture.whenStable();

    const applyButton = Array.from(racine.querySelectorAll<HTMLButtonElement>('button')).find(
      (each) => each.textContent?.includes('Appliquer le calendrier'),
    )!;
    expect(applyButton.disabled).toBe(false);
  });
});
