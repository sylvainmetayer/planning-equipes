import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComptesApi } from '../../core/api/comptes-api';
import { EditionStore } from '../../core/edition.store';
import { Compte } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SessionStore } from '../../core/session.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ComptesCard } from './comptes-card';

function compte(id: string, extra: Partial<Compte> = {}): Compte {
  return {
    id,
    email: `${id}@x.fr`,
    nom: null,
    creeLe: '2026-01-01T00:00:00Z',
    derniereConnexionLe: null,
    desactiveLe: null,
    desactivePar: null,
    habilitations: [],
    ...extra,
  };
}

describe('ComptesCard', () => {
  const api = {
    list: vi.fn(),
    create: vi.fn(),
    deactivate: vi.fn(),
    reactivate: vi.fn(),
    grant: vi.fn(),
    withdraw: vi.fn(),
  };
  const notify = vi.fn();

  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
    api.list.mockResolvedValue([compte('moi'), compte('autre')]);
    for (const f of [api.create, api.deactivate, api.reactivate, api.grant, api.withdraw]) {
      f.mockResolvedValue(compte('x'));
    }
    notify.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ComptesApi, useValue: api },
        { provide: NotificationService, useValue: { notify } },
        { provide: ConfirmService, useValue: { ask: vi.fn(() => Promise.resolve(true)) } },
        {
          provide: SessionStore,
          useValue: {
            statut: () => ({ compteId: 'moi' }),
            refresh: vi.fn(() => Promise.resolve()),
          },
        },
        { provide: EditionStore, useValue: { editions: () => [], courant: () => ({ id: 'E7' }) } },
      ],
    });
  });

  async function rendre() {
    const fixture = TestBed.createComponent(ComptesCard);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('lists the accounts and refuses the deactivation of its own account at the button (matched by id)', async () => {
    const fixture = await rendre();
    const boutons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        '.comptes-actions button',
      ),
    ).filter((b) => b.textContent?.includes('Désactiver'));
    expect(boutons.map((b) => b.disabled)).toEqual([false, true]);
  });

  it('adds an account by its address and name, then reads the list again', async () => {
    const fixture = await rendre();
    const card = fixture.componentInstance;
    card['email'].set(' ana@x.fr ');
    card['nom'].set('Ana');
    await card['add']();
    expect(api.create).toHaveBeenCalledWith({ email: 'ana@x.fr', nom: 'Ana' });
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('shows the refusal of the server instead of swallowing it', async () => {
    api.deactivate.mockRejectedValue(new Error('Il doit rester au moins un administrateur actif'));
    const fixture = await rendre();
    const card = fixture.componentInstance;
    await card['toggle'](compte('autre'));
    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({
        variant: 'error',
        message: expect.stringContaining('administrateur'),
      }),
    );
  });

  it('grants an organiser right on the current edition by default', async () => {
    const fixture = await rendre();
    const card = fixture.componentInstance;
    card['openGrant'](compte('autre'));
    await card['grantRight'](compte('autre'));
    expect(api.grant).toHaveBeenCalledWith('autre', {
      role: 'ORGANISATEUR',
      editionId: 'E7',
      expireLe: null,
    });
  });
});
