// The dialog creates an account ahead of the first Keycloak sign-in, or invites
// an administrator through the same form. What matters: an address that is not
// one never leaves the browser, the person's name is sent only when typed, and
// a refusal of the server stays in the dialog, next to what was typed.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComptesApi } from '../../core/api/comptes-api';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { Compte } from '../../core/models';
import { AddAccountData, AddAccountDialog } from './add-account-dialog';

const CREE: Compte = {
  id: 'c9',
  email: 'nouvelle@example.org',
  nom: null,
  sujet: null,
  creeLe: '2026-01-01T00:00:00Z',
  derniereConnexionLe: null,
  desactiveLe: null,
  habilitations: [],
};

describe('AddAccountDialog', () => {
  const close = vi.fn();
  const comptesApi = fakeOf<ComptesApi>({
    create: () => Promise.resolve(CREE),
    inviteAdministrator: () => Promise.resolve(CREE),
  });

  function monter(data?: AddAccountData) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: MatDialogRef, useValue: { close } },
        provideFake(ComptesApi, comptesApi),
        ...(data ? [{ provide: MAT_DIALOG_DATA, useValue: data }] : []),
      ],
    });
    const fixture = TestBed.createComponent(AddAccountDialog);
    fixture.detectChanges();
    return {
      fixture,
      dialog: fixture.componentInstance,
      racine: fixture.nativeElement as HTMLElement,
    };
  }

  beforeEach(() => {
    close.mockClear();
    comptesApi.create.mockClear();
    comptesApi.inviteAdministrator.mockClear();
  });

  it('creates the account with the address trimmed, and the name only when typed', async () => {
    const { dialog } = monter();
    dialog['email'].set('  nouvelle@example.org ');

    await dialog['save']();

    expect(comptesApi.create).toHaveBeenCalledExactlyOnceWith({
      email: 'nouvelle@example.org',
      nom: null,
    });
    expect(close).toHaveBeenCalledExactlyOnceWith(CREE);
  });

  it('sends the name when there is one', async () => {
    const { dialog } = monter();
    dialog['email'].set('nouvelle@example.org');
    dialog['nom'].set(' Nina Nouvelle ');

    await dialog['save']();

    expect(comptesApi.create).toHaveBeenCalledExactlyOnceWith({
      email: 'nouvelle@example.org',
      nom: 'Nina Nouvelle',
    });
  });

  it('invites an administrator through the same form, under its own title', async () => {
    const { dialog, racine } = monter({ administrateur: true });
    dialog['email'].set('chef@example.org');

    await dialog['save']();

    expect(racine.querySelector('h2')?.textContent).toContain('Inviter un administrateur');
    expect(comptesApi.inviteAdministrator).toHaveBeenCalledExactlyOnceWith({
      email: 'chef@example.org',
      nom: null,
    });
    expect(comptesApi.create).not.toHaveBeenCalled();
  });

  it('refuses an address that is not one before anything is sent, and says so', async () => {
    const { fixture, dialog, racine } = monter();
    expect(racine.querySelector('[role="alert"]')).toBeNull();
    dialog['email'].set('pas-une-adresse');

    await dialog['save']();
    fixture.detectChanges();

    expect(comptesApi.create).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();
    expect(racine.querySelector('p.field-error')?.textContent?.trim()).not.toBe('');
  });

  it('stays open on a refusal of the server and keeps what was typed', async () => {
    comptesApi.create.mockRejectedValueOnce(new Error('Un compte existe déjà pour cette adresse.'));
    const { fixture, dialog } = monter();
    dialog['email'].set('nouvelle@example.org');

    await dialog['save']();
    fixture.detectChanges();

    expect(close).not.toHaveBeenCalled();
    expect(dialog['serverError']()).toContain('existe déjà');
    expect(dialog['email']()).toBe('nouvelle@example.org');
    expect(dialog['busy']()).toBe(false);
  });
});
