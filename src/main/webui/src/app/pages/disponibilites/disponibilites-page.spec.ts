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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { DeclarationAdminView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DisponibilitesPage } from './disponibilites-page';
import { oldestFirst, readPendingOnly } from './declarations-filter';

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

  it('puts the declaration received first on top', () => {
    expect(oldestFirst([RECENTE, ANCIENNE]).map((each) => each.id)).toEqual([
      'ancienne',
      'recente',
    ]);
  });
});

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: DisponibilitesApi,
        useValue: {
          declarations: vi.fn(async () => [RECENTE, ANCIENNE, APPLIQUEE]),
          configuration: vi.fn(async () => ({ collecteOuverte: true, debut: null, fin: null })),
        },
      },
      { provide: MatDialog, useValue: { open: vi.fn() } },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: Location, useValue: { path: () => '/disponibilites', replaceState } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(DisponibilitesPage);
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
    const { fixture } = setUp({ statut: 'en-attente' });
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(pendingNames(root)).toEqual(['Animateur ancienne', 'Animateur recente']);
    expect(root.textContent).not.toContain('Déjà traitées');
  });

  it('shows the decided ones too without the param, and writes the filter back when ticked', async () => {
    const { fixture, replaceState } = setUp({});
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

describe('DisponibilitesPage during a solve', () => {
  const api = {
    declarations: vi.fn(async () => [DECLARATION]),
    configuration: vi.fn(async () => ({ collecteOuverte: true, debut: null, fin: null })),
    decide: vi.fn(async () => undefined),
    saveConfiguration: vi.fn(),
  };
  const notify = vi.fn();
  let locked: ReturnType<typeof signal<boolean>>;
  let ask: ReturnType<typeof vi.fn>;

  function mount(): ComponentFixture<DisponibilitesPage> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: DisponibilitesApi, useValue: api },
        { provide: NotificationService, useValue: { notify } },
        { provide: ConfirmService, useValue: { ask } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: SolverJobService, useValue: { editingLocked: locked } },
        { provide: Location, useValue: { path: () => '/disponibilites', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
    return TestBed.createComponent(DisponibilitesPage);
  }

  function button(fixture: ComponentFixture<DisponibilitesPage>, label: string): HTMLButtonElement {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
      (candidate) => candidate.textContent?.includes(label),
    ) as HTMLButtonElement;
  }

  beforeEach(() => {
    vi.clearAllMocks();
    locked = signal(false);
    ask = vi.fn(async () => true);
  });

  it('disables « Appliquer » and « Refuser » while a solve holds the edition, and says why', async () => {
    locked.set(true);
    const fixture = mount();
    await fixture.whenStable();

    expect(button(fixture, 'Appliquer').disabled).toBe(true);
    expect(button(fixture, 'Refuser').disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('.locked-hint')).not.toBeNull();
  });

  it('leaves them enabled without a solve', async () => {
    const fixture = mount();
    await fixture.whenStable();

    expect(button(fixture, 'Appliquer').disabled).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelector('.locked-hint')).toBeNull();
  });

  it('sends nothing when a solve started while the confirmation was open, and says so', async () => {
    ask = vi.fn(async () => {
      locked.set(true);
      return true;
    });
    const fixture = mount();
    await fixture.whenStable();

    button(fixture, 'Appliquer').click();
    await fixture.whenStable();

    expect(api.decide).not.toHaveBeenCalled();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'warning' }));
  });

  it('applies as usual once confirmed, no solve running', async () => {
    const fixture = mount();
    await fixture.whenStable();

    button(fixture, 'Appliquer').click();
    await fixture.whenStable();

    expect(api.decide).toHaveBeenCalledWith('D1', 'application', null);
  });
});
