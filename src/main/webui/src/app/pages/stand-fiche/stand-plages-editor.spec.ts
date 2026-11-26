// The list of dated plages of the fiche stand: the origin badge of each, the
// day filter, and the writes it sends through the stand's plage routes.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StandsApi } from '../../core/api/stands-api';
import { PlageStand, WrittenStand } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SessionStore } from '../../core/session.store';
import { Fake, fakeOf, provideFake } from '../../core/testing/fake';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StandPlagesEditor } from './stand-plages-editor';

const PLAGES: PlageStand[] = [
  {
    id: 1,
    mode: 'OUVERTURE',
    date: '2026-07-11',
    heureDebut: '10:00:00',
    heureFin: '18:00:00',
    motif: null,
    effectif: 2,
    depliee: true,
  },
  {
    id: 2,
    mode: 'FERMETURE',
    date: '2026-07-11',
    heureDebut: '12:00:00',
    heureFin: null,
    motif: 'orage',
    depliee: false,
  },
  {
    id: 3,
    mode: 'OUVERTURE',
    date: '2026-07-12',
    heureDebut: '10:00:00',
    heureFin: '18:00:00',
    motif: null,
    effectif: 2,
    depliee: true,
  },
];

const WRITTEN: WrittenStand = { stand: {} as WrittenStand['stand'], avertissements: [] };

describe('StandPlagesEditor', () => {
  let fixture: ComponentFixture<StandPlagesEditor>;
  let api: Fake<StandsApi>;
  let confirm: { ask: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;

  function mount(inputs: { jour?: string; locked?: boolean; lecteur?: boolean } = {}): void {
    TestBed.resetTestingModule();
    api = fakeOf<StandsApi>({
      addPlage: () => Promise.resolve(WRITTEN),
      removePlage: () => Promise.resolve(WRITTEN),
      removePlagesOfDay: () => Promise.resolve(WRITTEN),
    });
    confirm = { ask: vi.fn(() => Promise.resolve(true)) };
    notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(StandsApi, api),
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify } },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
        { provide: SessionStore, useValue: { lectureSeule: signal(inputs.lecteur ?? false) } },
      ],
    });
    fixture = TestBed.createComponent(StandPlagesEditor);
    fixture.componentRef.setInput('standId', 'S1');
    fixture.componentRef.setInput('plages', PLAGES);
    fixture.componentRef.setInput('effectifMax', 4);
    fixture.componentRef.setInput('jour', inputs.jour ?? '');
    fixture.componentRef.setInput('locked', inputs.locked ?? false);
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const rows = (): HTMLElement[] => Array.from(root().querySelectorAll('tbody tr'));

  beforeEach(() => mount());

  it("lists every plage with its type and its origin, the model's apart from the typed ones", () => {
    expect(rows()).toHaveLength(3);
    const badges = Array.from(root().querySelectorAll('.stand-plage-badge')).map((each) =>
      each.textContent!.trim(),
    );
    expect(badges).toContain('modèle');
    expect(badges).toContain('saisie');
    expect(root().querySelector('.stand-plage-saisie')!.closest('tr')!.textContent).toContain(
      'orage',
    );
  });

  it('narrows to the chosen day, and offers « Retirer ce jour » there only', () => {
    expect(root().textContent).not.toContain('Retirer ce jour');
    mount({ jour: '2026-07-12' });
    expect(rows()).toHaveLength(1);
    expect(root().textContent).toContain('Retirer ce jour');
  });

  it('removes one plage by its id', async () => {
    (rows()[1].querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(api.removePlage).toHaveBeenCalledWith('S1', 2);
  });

  it('removes a whole day once confirmed', async () => {
    mount({ jour: '2026-07-11' });
    const emitted = vi.fn();
    fixture.componentInstance.changed.subscribe(emitted);
    const button = Array.from(root().querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Retirer ce jour'),
    )!;
    button.click();
    await fixture.whenStable();
    expect(confirm.ask).toHaveBeenCalled();
    expect(api.removePlagesOfDay).toHaveBeenCalledWith('S1', '2026-07-11');
    expect(emitted).toHaveBeenCalled();
  });

  it('adds a plage typed by hand, and says what the server asks to check', async () => {
    api.addPlage.mockResolvedValue({
      stand: WRITTEN.stand,
      avertissements: [{ type: 'STAND_FENETRE_SANS_EFFET', message: 'hors créneau' }],
    });
    const instance = fixture.componentInstance;
    instance['patch']({ date: '2026-07-13', heureDebut: '09:00', mode: 'FERMETURE' });
    fixture.detectChanges();
    const submit = Array.from(root().querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Ajouter la plage'),
    )!;
    submit.click();
    await fixture.whenStable();
    expect(api.addPlage).toHaveBeenCalledWith(
      'S1',
      expect.objectContaining({ date: '2026-07-13', mode: 'FERMETURE', heureFin: null }),
    );
    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'warning', message: 'hors créneau' }),
    );
  });

  it('writes nothing while locked, and draws no button for a lecteur', async () => {
    mount({ locked: true });
    (rows()[0].querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(api.removePlage).not.toHaveBeenCalled();

    mount({ lecteur: true });
    expect(root().querySelectorAll('button')).toHaveLength(0);
    expect(root().querySelector('form')).toBeNull();
  });
});
