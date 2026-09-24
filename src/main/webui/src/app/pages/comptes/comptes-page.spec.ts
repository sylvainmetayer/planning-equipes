// The component is created, never rendered: what is checked is what the
// confirmation says before an account is deactivated — above all, that the
// person about to lock themselves out is told so — and that the account the
// server answers replaces the one on screen.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { ComptesApi } from '../../core/api/comptes-api';
import { StandsApi } from '../../core/api/stands-api';
import { EditionStore } from '../../core/edition.store';
import { Compte } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ConfirmData, ConfirmService } from '../../shared/confirm-dialog';
import { ComptesPage } from './comptes-page';

type PageInternals = {
  deactivate: (compte: Compte) => Promise<void>;
  rows: () => { compte: Compte; own: boolean }[];
};

function account(id: string, email: string, patch: Partial<Compte> = {}): Compte {
  return {
    id,
    email,
    nom: null,
    sujet: null,
    creeLe: '2026-01-01T00:00:00Z',
    derniereConnexionLe: null,
    desactiveLe: null,
    habilitations: [],
    ...patch,
  };
}

describe('ComptesPage', () => {
  const own = account('c1', 'moi@example.org');
  const other = account('c2', 'autre@example.org');
  const comptesApi = {
    list: vi.fn(async () => [own, other]),
    deactivate: vi.fn(async (id: string) =>
      account(id, id === 'c1' ? own.email : other.email, { desactiveLe: '2026-06-15T10:00:00Z' }),
    ),
  };
  const confirm = { ask: vi.fn(async (_data: ConfirmData) => true) };

  beforeEach(() => {
    comptesApi.list.mockClear();
    comptesApi.deactivate.mockClear();
    confirm.ask.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ComptesApi, useValue: comptesApi },
        {
          provide: AdminApi,
          useValue: {
            session: async () => ({ authentifie: true, nom: 'Moi@Example.org', roles: ['admin'] }),
          },
        },
        { provide: StandsApi, useValue: { listInEdition: vi.fn(async () => []) } },
        { provide: EditionStore, useValue: { editions: () => [], courant: () => null } },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
  });

  async function createPage(): Promise<PageInternals> {
    const page = TestBed.createComponent(ComptesPage).componentInstance as unknown as PageInternals;
    // The list and the session are both read on construction.
    await vi.waitFor(() => expect(page.rows().some((row) => row.own)).toBe(true));
    return page;
  }

  it('warns before deactivating the account signed in', async () => {
    const page = await createPage();

    await page.deactivate(own);

    const data = confirm.ask.mock.calls[0][0];
    expect(data.danger).toBe(true);
    expect(data.message).toContain('ceux du realm Keycloak compris');
    await expect(data.detail).resolves.toContain('votre propre compte');
  });

  it('says nothing of the kind for somebody else, and never offers to delete', async () => {
    const page = await createPage();

    await page.deactivate(other);

    const data = confirm.ask.mock.calls[0][0];
    expect(data.detail).toBeUndefined();
    expect(data.confirmLabel).toBe('Désactiver');
  });

  it('puts the account the server answers in place of the one on screen', async () => {
    const page = await createPage();

    await page.deactivate(other);

    expect(comptesApi.deactivate).toHaveBeenCalledWith('c2');
    const row = page.rows().find((candidate) => candidate.compte.id === 'c2');
    expect(row?.compte.desactiveLe).toBe('2026-06-15T10:00:00Z');
    expect(page.rows()).toHaveLength(2);
  });

  it('writes nothing when the confirmation is declined', async () => {
    confirm.ask.mockResolvedValueOnce(false);
    const page = await createPage();

    await page.deactivate(other);

    expect(comptesApi.deactivate).not.toHaveBeenCalled();
  });
});
