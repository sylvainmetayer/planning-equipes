import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComptesApi } from '../../core/api/comptes-api';
import { EditionStore } from '../../core/edition.store';
import { Compte } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
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
    passwordSet: false,
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
    invite: vi.fn(),
    resetPassword: vi.fn(),
  };
  const notify = vi.fn();

  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
    api.list.mockResolvedValue([compte('moi'), compte('autre')]);
    for (const f of [api.create, api.deactivate, api.reactivate, api.grant, api.withdraw]) {
      f.mockResolvedValue(compte('x'));
    }
    api.invite.mockResolvedValue(undefined);
    api.resetPassword.mockResolvedValue(undefined);
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
        { provide: ReferenceDataStore, useValue: { stands: () => [{ id: 'S1', nom: 'Buvette' }] } },
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
    expect(api.create).toHaveBeenCalledWith({ email: 'ana@x.fr', nom: 'Ana', invite: true });
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('invites an account with no password yet, resets one that has it, and never shows the link', async () => {
    const fixture = await rendre();
    const card = fixture.componentInstance;
    await card['sendLink'](compte('nouveau'));
    expect(api.invite).toHaveBeenCalledWith('nouveau');
    await card['sendLink'](compte('ancien', { passwordSet: true }));
    expect(api.resetPassword).toHaveBeenCalledWith('ancien');
    expect(notify).toHaveBeenCalledTimes(2);
  });

  it('labels the button by what it does', async () => {
    api.list.mockResolvedValue([compte('a'), compte('b', { passwordSet: true })]);
    const fixture = await rendre();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Inviter');
    expect(text).toContain('Réinitialiser le mot de passe');
  });

  it('reads the list again when the invitation of a new account fails', async () => {
    api.create.mockImplementation(async () => {
      throw new Error("Le compte est créé, mais l'invitation n'a pas pu partir");
    });
    const fixture = await rendre();
    const card = fixture.componentInstance;
    card['email'].set('ana@x.fr');
    await card['add']();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'error' }));
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

  it('grants a responsable de stand right on the current edition, with the chosen stands', async () => {
    const fixture = await rendre();
    const card = fixture.componentInstance;
    card['openGrant'](compte('autre'));
    card['role'].set('RESPONSABLE_STAND');
    card['standIds'].set(['S1']);
    await card['grantRight'](compte('autre'));
    expect(api.grant).toHaveBeenCalledWith('autre', {
      role: 'RESPONSABLE_STAND',
      editionId: 'E7',
      expireLe: null,
      standIds: ['S1'],
    });
  });
});
