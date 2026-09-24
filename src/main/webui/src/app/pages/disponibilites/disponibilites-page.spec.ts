// The « en attente » view of the declarations screen: what the home screen's
// « À traiter aujourd'hui » counts and links to (`?statut=en-attente`) — the
// declarations waiting for a decision, the oldest first, the decided ones
// out of sight.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { DeclarationAdminView } from '../../core/models';
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
