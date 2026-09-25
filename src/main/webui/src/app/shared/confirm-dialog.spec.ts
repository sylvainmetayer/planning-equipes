// The application's replacement for `window.confirm`, in front of every
// destructive action: delete a stand, delete fifty rows, wipe the database.
//
// Two things must hold, and nothing else in the suite says either. The dialog
// must offer a *labelled* way out — a dismiss button that is not the confirm
// button — and, above all, `ask()` must answer `false` for anything that is not
// an explicit confirmation: a cancel, an Escape, a backdrop click. A `null`
// leaking through as truthy would turn "I closed the popup" into "yes, delete".

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfirmData, ConfirmDialog, ConfirmService } from './confirm-dialog';

function monter(data: ConfirmData) {
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: data },
    ],
  });
  const fixture = TestBed.createComponent(ConfirmDialog);
  fixture.detectChanges();
  return { fixture, close, racine: fixture.nativeElement as HTMLElement };
}

function boutons(racine: HTMLElement): HTMLButtonElement[] {
  return Array.from(racine.querySelectorAll('mat-dialog-actions button'));
}

describe('ConfirmDialog', () => {
  it('shows the title and the message it was opened with', () => {
    const { racine } = monter({
      title: 'Supprimer le stand s42 ?',
      message: 'Action irréversible.',
    });

    expect(racine.querySelector('h2')!.textContent!.trim()).toBe('Supprimer le stand s42 ?');
    expect(racine.querySelector('mat-dialog-content p')!.textContent!.trim()).toBe(
      'Action irréversible.',
    );
  });

  /**
   * The impact figures of a deletion are fetched from the server, and the
   * dialog opens before they arrive: it must render without them and grow the
   * paragraph when they land, never wait.
   */
  it('opens without the detail it was still fetching, then shows it', async () => {
    let repondre: (text: string) => void = () => undefined;
    const detail = new Promise<string>((resolve) => {
      repondre = resolve;
    });
    const { fixture, racine } = monter({ title: 't', message: 'Action irréversible.', detail });

    expect(racine.querySelectorAll('mat-dialog-content p')).toHaveLength(1);

    repondre('Référencé par 42 affectation(s).');
    await detail;
    fixture.detectChanges();

    const paragraphes = Array.from(racine.querySelectorAll('mat-dialog-content p'));
    expect(paragraphes.map((p) => p.textContent!.trim())).toEqual([
      'Action irréversible.',
      'Référencé par 42 affectation(s).',
    ]);
  });

  /** The count informs, it never blocks: a failed lookup just leaves it out. */
  it('stays usable when the detail it was fetching failed', async () => {
    const detail = Promise.reject(new Error('injoignable'));
    const { fixture, racine, close } = monter({ title: 't', message: 'm', detail });

    await detail.catch(() => undefined);
    fixture.detectChanges();

    expect(racine.querySelectorAll('mat-dialog-content p')).toHaveLength(1);
    boutons(racine)[1].click();
    expect(close).toHaveBeenCalledWith(true);
  });

  it('offers a dismiss button distinct from the confirm one, both labelled by default', () => {
    const { racine } = monter({ title: 't', message: 'm' });

    const libelles = boutons(racine).map((bouton) => bouton.textContent!.trim());
    expect(libelles).toEqual(['Annuler', 'Confirmer']);
  });

  it('uses the caller labels when it gives them', () => {
    const { racine } = monter({
      title: 't',
      message: 'm',
      confirmLabel: 'Supprimer',
      cancelLabel: 'Garder',
    });

    expect(boutons(racine).map((bouton) => bouton.textContent!.trim())).toEqual([
      'Garder',
      'Supprimer',
    ]);
  });

  // Three states, not two: the cancel button answers false, and only Escape
  // or the backdrop leave null. One caller needs them apart — the conflict
  // dialog of issue #362, whose cancel button ("Recharger") throws away what
  // the user typed. `ask()` folds both into false for everybody else.
  it('closes with false on cancel and with true on confirm', () => {
    const { racine, close } = monter({ title: 't', message: 'm' });

    boutons(racine)[0].click();
    expect(close).toHaveBeenCalledWith(false);

    boutons(racine)[1].click();
    expect(close).toHaveBeenLastCalledWith(true);
  });

  it('colours the confirm button as a warning for a destructive action only', () => {
    const dangereux = monter({ title: 't', message: 'm', danger: true });
    const anodin = monter({ title: 't', message: 'm' });

    // Material maps `color` to a palette class: the red of a deletion must not
    // be worn by an ordinary confirmation, nor missing from a destructive one.
    expect(boutons(dangereux.racine)[1].className).not.toBe(boutons(anodin.racine)[1].className);
    expect(boutons(dangereux.racine)[1].classList.contains('mat-warn')).toBe(true);
    expect(boutons(anodin.racine)[1].classList.contains('mat-warn')).toBe(false);
  });
});

describe('ConfirmService', () => {
  let dialog: { open: ReturnType<typeof vi.fn> };
  let service: ConfirmService;

  beforeEach(() => {
    dialog = { open: vi.fn() };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: MatDialog, useValue: dialog }],
    });
    service = TestBed.inject(ConfirmService);
  });

  function fermeAvec(resultat: unknown) {
    dialog.open.mockReturnValue({ afterClosed: () => of(resultat) });
  }

  it('answers true only when the user confirmed', async () => {
    fermeAvec(true);
    await expect(service.ask({ title: 't', message: 'm' })).resolves.toBe(true);
  });

  it('answers false on a cancel', async () => {
    fermeAvec(null);
    await expect(service.ask({ title: 't', message: 'm' })).resolves.toBe(false);
  });

  it('answers false when the dialog was dismissed by Escape or the backdrop', async () => {
    // `afterClosed()` emits `undefined` in that case — the value that would be
    // truthiest to get wrong: it must never mean "yes, delete".
    fermeAvec(undefined);
    await expect(service.ask({ title: 't', message: 'm' })).resolves.toBe(false);
  });

  it('passes the caller data through to the dialog', async () => {
    fermeAvec(true);
    await service.ask({ title: 'Supprimer ?', message: 'Irréversible.', danger: true });

    expect(dialog.open).toHaveBeenCalledOnce();
    expect(dialog.open.mock.calls[0][0]).toBe(ConfirmDialog);
    expect(dialog.open.mock.calls[0][1].data).toEqual({
      title: 'Supprimer ?',
      message: 'Irréversible.',
      danger: true,
    });
  });
});
