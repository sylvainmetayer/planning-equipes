import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from './api/admin-api';
import { StatutSession } from './models';
import { SessionStore } from './session.store';

describe('SessionStore', () => {
  const session = vi.fn();
  const navigateByUrl = vi.fn(() => Promise.resolve(true));

  function statut(authentifie: boolean, ...roles: string[]): StatutSession {
    return { authentifie, nom: 'a@b.fr', roles, compteId: 'c1' };
  }

  function store(): SessionStore {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminApi, useValue: { session } },
        { provide: Router, useValue: { navigateByUrl } },
      ],
    });
    return TestBed.inject(SessionStore);
  }

  beforeEach(() => {
    session.mockReset();
    navigateByUrl.mockClear();
  });

  it('assumes nothing before the answer, and reads the session once', async () => {
    session.mockResolvedValue(statut(true, 'organisateur'));
    const s = store();
    expect(s.isAdmin()).toBe(false);
    expect(s.known()).toBe(false);
    await Promise.all([s.ready(), s.ready()]);
    expect(session).toHaveBeenCalledTimes(1);
    expect(s.known()).toBe(true);
    expect(s.isAdmin()).toBe(false);
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('knows an administrator, and reads again on demand', async () => {
    session.mockResolvedValue(statut(true, 'admin'));
    const s = store();
    await s.ready();
    expect(s.isAdmin()).toBe(true);
    session.mockResolvedValue(statut(true, 'organisateur'));
    await s.refresh();
    expect(s.isAdmin()).toBe(false);
  });

  it('takes a person with no role at all to the login page that explains it', async () => {
    session.mockResolvedValue(statut(true));
    await store().ready();
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('stays unknown, never admin, when the session cannot be read', async () => {
    session.mockRejectedValue(new Error('401'));
    const s = store();
    await s.ready();
    expect(s.known()).toBe(true);
    expect(s.isAdmin()).toBe(false);
  });
});
