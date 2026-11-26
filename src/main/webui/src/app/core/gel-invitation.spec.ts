// The invitation to enter the Calcul phase: once per edition, only while the
// edition is still in preparation, and never a crash when storage is refused.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionStore } from './edition.store';
import { GelInvitation, alreadyOffered } from './gel-invitation';
import { EditionPhase } from './models';
import { NotificationService } from './notification.service';

describe('GelInvitation', () => {
  let phase: EditionPhase | null;
  let notify: ReturnType<typeof vi.fn>;
  let invitation: GelInvitation;

  beforeEach(() => {
    // The key this spec writes, and only it: other spec files share the storage.
    localStorage.removeItem('planning-equipes.gel-invitation.resolution');
    localStorage.removeItem('planning-equipes.editionId');
    phase = 'PREPARATION';
    notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: EditionStore, useValue: { phase: () => phase } },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
    invitation = TestBed.inject(GelInvitation);
  });

  afterEach(() => {
    localStorage.removeItem('planning-equipes.gel-invitation.resolution');
    vi.restoreAllMocks();
  });

  it('offers once per edition, leading to the phase control', () => {
    invitation.offer('resolution');
    invitation.offer('resolution');

    expect(notify).toHaveBeenCalledOnce();
    expect(notify.mock.calls[0][0].title).toBe("Passer l'édition en phase Calcul ?");
    expect(notify.mock.calls[0][0].lien.route).toBe('/');
    expect(alreadyOffered('resolution')).toBe(true);
  });

  it('stays silent once the edition is past its preparation', () => {
    phase = 'PUBLIEE';

    invitation.offer('resolution');

    expect(notify).not.toHaveBeenCalled();
    expect(alreadyOffered('resolution')).toBe(true);
  });

  it('neither offers nor spends the milestone while the phase is unknown', () => {
    phase = null;

    invitation.offer('resolution');

    expect(notify).not.toHaveBeenCalled();
    expect(alreadyOffered('resolution')).toBe(false);

    phase = 'PREPARATION';
    invitation.offer('resolution');
    expect(notify).toHaveBeenCalledOnce();
  });

  it('survives a storage that refuses every access', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(() => invitation.offer('resolution')).not.toThrow();
    expect(notify).toHaveBeenCalledOnce();
  });
});
