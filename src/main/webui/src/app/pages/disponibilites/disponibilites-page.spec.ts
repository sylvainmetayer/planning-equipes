// The « en attente » view of the declarations screen: what the home screen's
// « À traiter aujourd'hui » counts and links to (`?statut=en-attente`) — the
// declarations waiting for a decision, the oldest first, the decided ones
// out of sight. And the screen during a solve: applying a declaration writes
// the fiche through the service a solve holding the edition refuses in 409,
// so the decision waits for the solve instead of discovering the refusal
// afterwards.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { DeclarationAdminView, TeammateRequestView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DisponibilitesPage } from './disponibilites-page';
import { oldestFirst, readDisponibilitesTab, readPendingOnly } from './declarations-filter';

function declaration(id: string, statut: string, creeLe: string): DeclarationAdminView {
  return {
    id,
    statut,
    creeLe,
    decideLe: statut === 'EN_ATTENTE' ? null : '2026-07-04T08:00:00Z',
    animateurNom: `Animateur ${id}`,
    joursIndisponibles: [],
    souhaitsLabels: [],
    joursActuels: [],
    souhaitsActuelsLabels: [],
    commentaire: null,
    commentaireAdmin: null,
  } as unknown as DeclarationAdminView;
}

const RECENTE = declaration('recente', 'EN_ATTENTE', '2026-07-03T08:00:00Z');
const ANCIENNE = declaration('ancienne', 'EN_ATTENTE', '2026-07-01T08:00:00Z');
const APPLIQUEE = declaration('appliquee', 'APPLIQUEE', '2026-06-01T08:00:00Z');

describe('declarations-filter', () => {
  it('reads `statut=en-attente` and nothing else', () => {
    expect(readPendingOnly('en-attente')).toBe(true);
    expect(readPendingOnly(null)).toBe(false);
    expect(readPendingOnly('toutes')).toBe(false);
  });

  it('reads the tab, the declarations whenever it is not `covoiturage`', () => {
    expect(readDisponibilitesTab('covoiturage')).toBe('covoiturage');
    expect(readDisponibilitesTab(null)).toBe('declarations');
    expect(readDisponibilitesTab('autre')).toBe('declarations');
  });

  it('puts the declaration received first on top', () => {
    expect(oldestFirst([RECENTE, ANCIENNE]).map((each) => each.id)).toEqual([
      'ancienne',
      'recente',
    ]);
  });
});

async function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: DisponibilitesApi,
        useValue: {
          declarations: vi.fn(async () => [RECENTE, ANCIENNE, APPLIQUEE]),
          configuration: vi.fn(async () => ({ collecteOuverte: true, debut: null, fin: null })),
          carpools: vi.fn(async () => []),
        },
      },
      { provide: MatDialog, useValue: { open: vi.fn() } },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: Location, useValue: { path: () => '/disponibilites', replaceState } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { queryParamMap: convertToParamMap(queryParams) },
          queryParamMap: of(convertToParamMap(queryParams)),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(DisponibilitesPage);
  // The first render runs ngOnInit, which starts the load.
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, replaceState };
}

function pendingNames(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('h3[mat-card-title]')).map(
    (each) => each.textContent?.trim() ?? '',
  );
}

describe('DisponibilitesPage « en attente »', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('opens on the pending declarations only, the oldest first', async () => {
    const { fixture } = await setUp({ statut: 'en-attente' });
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(pendingNames(root)).toEqual(['Animateur ancienne', 'Animateur recente']);
    expect(root.textContent).not.toContain('Déjà traitées');
  });

  it('shows the decided ones too without the param, and writes the filter back when ticked', async () => {
    const { fixture, replaceState } = await setUp({});
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Déjà traitées');
    expect(replaceState).toHaveBeenLastCalledWith('/disponibilites');

    (
      fixture.componentInstance as unknown as { pendingOnly: { set(v: boolean): void } }
    ).pendingOnly.set(true);
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/disponibilites?statut=en-attente');
    expect(root.textContent).not.toContain('Déjà traitées');
  });
});

const DECLARATION = {
  id: 'D1',
  animateurId: 'a1',
  animateurNom: 'A. N.',
  statut: 'EN_ATTENTE',
  joursIndisponibles: ['2026-07-14'],
  souhaitsLabels: [],
  joursActuels: [],
  souhaitsActuelsLabels: [],
  commentaire: null,
  commentaireAdmin: null,
  creeLe: '2026-07-01T08:00:00Z',
  decideLe: null,
} as unknown as DeclarationAdminView;

function button(fixture: ComponentFixture<DisponibilitesPage>, label: string): HTMLButtonElement {
  return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((candidate) =>
    candidate.textContent?.includes(label),
  ) as HTMLButtonElement;
}

describe('DisponibilitesPage during a solve', () => {
  const api = {
    declarations: vi.fn(async () => [DECLARATION]),
    configuration: vi.fn(async () => ({ collecteOuverte: true, debut: null, fin: null })),
    carpools: vi.fn(async (): Promise<TeammateRequestView[]> => []),
    validateCarpool: vi.fn(async () => ({ request: null, avertissements: [] })),
    setCarpoolAside: vi.fn(async () => undefined),
    cancelCarpool: vi.fn(async () => undefined),
    decide: vi.fn(async () => undefined),
    saveConfiguration: vi.fn(),
  };
  const notify = vi.fn();
  let locked: ReturnType<typeof signal<boolean>>;
  let ask: ReturnType<typeof vi.fn>;
  let dialogAnswer: string | null;

  async function mount(
    queryParams: Record<string, string> = {},
  ): Promise<ComponentFixture<DisponibilitesPage>> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: DisponibilitesApi, useValue: api },
        { provide: NotificationService, useValue: { notify } },
        { provide: ConfirmService, useValue: { ask } },
        {
          provide: MatDialog,
          useValue: { open: vi.fn(() => ({ afterClosed: () => of(dialogAnswer) })) },
        },
        { provide: SolverJobService, useValue: { editingLocked: locked } },
        { provide: Location, useValue: { path: () => '/disponibilites', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(queryParams) },
            queryParamMap: of(convertToParamMap(queryParams)),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(DisponibilitesPage);
    // The first render runs ngOnInit, which starts the load.
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  beforeEach(() => {
    vi.clearAllMocks();
    locked = signal(false);
    ask = vi.fn(async () => true);
    dialogAnswer = null;
  });

  it('disables « Appliquer » and « Refuser » while a solve holds the edition, and says why', async () => {
    locked.set(true);
    const fixture = await mount();
    await fixture.whenStable();

    expect(button(fixture, 'Appliquer').disabled).toBe(true);
    expect(button(fixture, 'Refuser').disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('.locked-hint')).not.toBeNull();
  });

  it('leaves them enabled without a solve', async () => {
    const fixture = await mount();
    await fixture.whenStable();

    expect(button(fixture, 'Appliquer').disabled).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelector('.locked-hint')).toBeNull();
  });

  it('sends nothing when a solve started while the confirmation was open, and says so', async () => {
    ask = vi.fn(async () => {
      locked.set(true);
      return true;
    });
    const fixture = await mount();
    await fixture.whenStable();

    button(fixture, 'Appliquer').click();
    await fixture.whenStable();

    expect(api.decide).not.toHaveBeenCalled();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'warning' }));
  });

  it('applies as usual once confirmed, no solve running', async () => {
    const fixture = await mount();
    await fixture.whenStable();

    button(fixture, 'Appliquer').click();
    await fixture.whenStable();

    expect(api.decide).toHaveBeenCalledWith('D1', 'application', null);
  });

  function pendingCarpool(): TeammateRequestView {
    return {
      id: 'K1',
      animateurId: 'A1',
      nature: 'COVOITURAGE',
      status: 'EN_ATTENTE',
      members: [
        { animateurId: 'A1', fullName: 'Alice Martin' },
        { animateurId: 'A2', fullName: 'Bob Durand' },
      ],
      confirmedByAll: true,
      divergentDayCount: 2,
      divergentDays: ['2026-07-10', '2026-07-11'],
      contrainteId: null,
      createdAt: '2026-06-01T10:00:00Z',
      decidedAt: null,
      reason: null,
    };
  }

  it('keeps the covoiturage requests off the declarations tab', async () => {
    api.carpools.mockResolvedValueOnce([pendingCarpool()]);
    const fixture = await mount();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).not.toContain('Alice Martin, Bob Durand');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.dispo-onglet-compte')?.textContent,
    ).toBe('(1)');
  });

  it('shows a pending carpool on its tab with its badge and its divergent days, and validates it', async () => {
    api.carpools.mockResolvedValueOnce([pendingCarpool()]);
    const fixture = await mount({ onglet: 'covoiturage' });
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Alice Martin, Bob Durand');
    expect(text).toContain('Confirmé par tous');
    expect(text).toContain('2 jour(s) où leurs indisponibilités déclarées divergent');
    expect(text).not.toContain('Animateur D1');

    button(fixture, "Valider l'arrivée groupée").click();
    await fixture.whenStable();

    expect(api.validateCarpool).toHaveBeenCalledWith('K1');
  });

  it('sets a carpool aside with the reason typed, or none', async () => {
    api.carpools.mockResolvedValue([pendingCarpool()]);
    dialogAnswer = 'Bob ne vient que le samedi.';
    const fixture = await mount({ onglet: 'covoiturage' });
    await fixture.whenStable();

    button(fixture, 'Écarter').click();
    await fixture.whenStable();
    expect(api.setCarpoolAside).toHaveBeenCalledWith('K1', 'Bob ne vient que le samedi.');

    dialogAnswer = '';
    button(fixture, 'Écarter').click();
    await fixture.whenStable();
    expect(api.setCarpoolAside).toHaveBeenLastCalledWith('K1', null);

    dialogAnswer = null;
    button(fixture, 'Écarter').click();
    await fixture.whenStable();
    expect(api.setCarpoolAside).toHaveBeenCalledTimes(2);
    api.carpools.mockReset();
    api.carpools.mockResolvedValue([]);
  });

  function decidedCarpool(
    id: string,
    status: TeammateRequestView['status'],
    overrides: Partial<TeammateRequestView> = {},
  ): TeammateRequestView {
    return {
      ...pendingCarpool(),
      id,
      status,
      contrainteId: status === 'ECARTEE' ? null : 'C7',
      decidedAt: '2026-06-02T10:00:00Z',
      ...overrides,
    };
  }

  it('offers to cancel a validated group once, and never a set-aside or cancelled one', async () => {
    api.carpools.mockResolvedValueOnce([
      decidedCarpool('K1', 'VALIDEE'),
      // Bob's own demand, validated with the same car: one action for the group.
      decidedCarpool('K2', 'VALIDEE', { animateurId: 'A2' }),
      decidedCarpool('K3', 'ECARTEE', { reason: 'complet' }),
      decidedCarpool('K4', 'ANNULEE', { contrainteId: 'C5', reason: 'panne' }),
    ]);
    const fixture = await mount({ onglet: 'covoiturage' });
    await fixture.whenStable();

    const actions = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].filter(
      (candidate) => candidate.textContent?.includes("Annuler l'arrivée groupée"),
    );
    expect(actions).toHaveLength(1);
  });

  it('cancels a validated group with the reason typed, then reads it as cancelled', async () => {
    api.carpools
      .mockResolvedValueOnce([decidedCarpool('K1', 'VALIDEE')])
      .mockResolvedValue([decidedCarpool('K1', 'ANNULEE', { reason: 'La voiture est en panne.' })]);
    dialogAnswer = 'La voiture est en panne.';
    const fixture = await mount({ onglet: 'covoiturage' });
    await fixture.whenStable();

    button(fixture, "Annuler l'arrivée groupée").click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect(api.cancelCarpool).toHaveBeenCalledWith('K1', 'La voiture est en panne.');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Annulée');
    expect(text).toContain('La voiture est en panne.');
    expect(button(fixture, "Annuler l'arrivée groupée")).toBeUndefined();
    api.carpools.mockReset();
    api.carpools.mockResolvedValue([]);
  });

  it('sends no cancellation when the dialog is dismissed', async () => {
    api.carpools.mockResolvedValueOnce([decidedCarpool('K1', 'VALIDEE')]);
    dialogAnswer = null;
    const fixture = await mount({ onglet: 'covoiturage' });
    await fixture.whenStable();

    button(fixture, "Annuler l'arrivée groupée").click();
    await fixture.whenStable();

    expect(api.cancelCarpool).not.toHaveBeenCalled();
  });
});
