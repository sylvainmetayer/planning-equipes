// The invitation to freeze: once per edition and milestone, never when both
// families are already frozen, and never a crash when storage is refused.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GelInvitation, alreadyOffered } from './gel-invitation';
import { GelReferentielStore } from './gel-referentiel.store';
import { NotificationService } from './notification.service';

describe('GelInvitation', () => {
  let frozen: Set<string>;
  let readError: string;
  let notify: ReturnType<typeof vi.fn>;
  let invitation: GelInvitation;

  beforeEach(() => {
    // The keys this spec writes, and only them: other spec files share the storage.
    localStorage.removeItem('planning-equipes.gel-invitation.resolution');
    localStorage.removeItem('planning-equipes.gel-invitation.publication');
    localStorage.removeItem('planning-equipes.editionId');
    frozen = new Set();
    readError = '';
    notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: GelReferentielStore,
          useValue: {
            reload: vi.fn().mockResolvedValue(undefined),
            isFrozen: (family: string) => frozen.has(family),
            error: () => readError,
          },
        },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
    invitation = TestBed.inject(GelInvitation);
  });

  afterEach(() => {
    localStorage.removeItem('planning-equipes.gel-invitation.resolution');
    localStorage.removeItem('planning-equipes.gel-invitation.publication');
    vi.restoreAllMocks();
  });

  it('offers once per milestone, leading to the freeze switches', async () => {
    await invitation.offer('resolution');
    await invitation.offer('resolution');

    expect(notify).toHaveBeenCalledOnce();
    expect(notify.mock.calls[0][0].title).toBe('Figer les stands et les créneaux ?');
    expect(notify.mock.calls[0][0].lien.route).toBe('/parametres');
    expect(alreadyOffered('resolution')).toBe(true);

    await invitation.offer('publication');
    expect(notify).toHaveBeenCalledTimes(2);
    expect(notify.mock.calls[1][0].message).toContain('publié');
  });

  it('stays silent when the stands and the timeslots are already frozen', async () => {
    frozen.add('STANDS');
    frozen.add('CRENEAUX');

    await invitation.offer('resolution');

    expect(notify).not.toHaveBeenCalled();
    expect(alreadyOffered('resolution')).toBe(true);
  });

  it('neither offers nor spends the milestone when the freeze could not be read', async () => {
    readError = 'Serveur injoignable';

    await invitation.offer('resolution');

    expect(notify).not.toHaveBeenCalled();
    expect(alreadyOffered('resolution')).toBe(false);

    readError = '';
    await invitation.offer('resolution');
    expect(notify).toHaveBeenCalledOnce();
    expect(alreadyOffered('resolution')).toBe(true);
  });

  it('survives a storage that refuses every access', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });

    await expect(invitation.offer('resolution')).resolves.toBeUndefined();
    expect(notify).toHaveBeenCalledOnce();
  });
});
