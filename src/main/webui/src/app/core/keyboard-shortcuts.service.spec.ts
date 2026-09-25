import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommandPaletteDialog } from '../shared/command-palette-dialog';
import { KeyboardShortcutsDialog } from '../shared/keyboard-shortcuts-dialog';
import { KeyboardShortcutsService } from './keyboard-shortcuts.service';
import { SingleKeyShortcutsService } from './single-key-shortcuts';

/** The two things this service asks of `MatDialog`: opening one, and knowing whether one is open. */
function fakeDialog() {
  const ferme = new Subject<unknown>();
  const reference = {
    afterClosed: () => ferme.asObservable(),
    close: vi.fn((valeur: unknown) => ferme.next(valeur)),
  };
  return {
    openDialogs: [] as unknown[],
    open: vi.fn(() => reference),
    reference,
    ferme,
  };
}

function configure() {
  const dialog = fakeDialog();
  const router = { navigateByUrl: vi.fn(), navigate: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      KeyboardShortcutsService,
      { provide: MatDialog, useValue: dialog },
      { provide: Router, useValue: router },
    ],
  });
  const service = TestBed.inject(KeyboardShortcutsService);
  service.start();
  return { service, dialog, router };
}

/** Dispatches a real `keydown` on the document, from `cible` when one is given. */
function frapper(
  key: string,
  options: KeyboardEventInit & { cible?: Element } = {},
): KeyboardEvent {
  const { cible: target, ...init } = options;
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  (target ?? document.body).dispatchEvent(event);
  return event;
}

describe('KeyboardShortcutsService', () => {
  let ouverts: KeyboardShortcutsService[] = [];

  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    for (const service of ouverts) {
      service.stop();
    }
    ouverts = [];
    document.body.innerHTML = '';
  });

  function start() {
    const contexte = configure();
    ouverts.push(contexte.service);
    return contexte;
  }

  it('opens the palette on Ctrl+K, and takes the shortcut from the browser', () => {
    const { dialog } = start();
    const event = frapper('k', { ctrlKey: true });
    expect(dialog.open).toHaveBeenCalledWith(CommandPaletteDialog, expect.anything());
    expect(event.defaultPrevented).toBe(true);
  });

  it('opens the palette on Cmd+K too, for the macOS keyboard', () => {
    const { dialog } = start();
    frapper('k', { metaKey: true });
    expect(dialog.open).toHaveBeenCalledWith(CommandPaletteDialog, expect.anything());
  });

  it('opens the palette even from inside a field: a modifier is never mistaken for typing', () => {
    const { dialog } = start();
    const champ = document.createElement('input');
    document.body.append(champ);
    frapper('k', { ctrlKey: true, cible: champ });
    expect(dialog.open).toHaveBeenCalledOnce();
  });

  it('closes the palette on a second Ctrl+K instead of stacking a second one', () => {
    const { dialog } = start();
    frapper('k', { ctrlKey: true });
    frapper('k', { ctrlKey: true });
    expect(dialog.reference.close).toHaveBeenCalledOnce();
    expect(dialog.open).toHaveBeenCalledOnce();
  });

  it('navigates to the picked entry once the palette closes', () => {
    const { dialog, router } = start();
    frapper('k', { ctrlKey: true });
    dialog.ferme.next({ route: '/timeline', queryParams: { animateur: 'a1' } });
    expect(router.navigate).toHaveBeenCalledWith(['/timeline'], {
      queryParams: { animateur: 'a1' },
    });
  });

  it('navigates nowhere when the palette is dismissed', () => {
    const { dialog, router } = start();
    frapper('k', { ctrlKey: true });
    dialog.ferme.next(null);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('navigates on `g` then the letter of the page', () => {
    const { router } = start();
    frapper('g');
    frapper('a');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/animateurs');
  });

  it('does nothing on `g` followed by an unassigned letter', () => {
    const { router } = start();
    frapper('g');
    frapper('w');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('forgets the prefix after one key: `g` then `x` then `a` is not a navigation to /animateurs', () => {
    const { router } = start();
    frapper('g');
    frapper('x');
    router.navigateByUrl.mockClear();
    frapper('a');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('stays silent while the focus is in a text field', () => {
    const { router, dialog } = start();
    const champ = document.createElement('input');
    document.body.append(champ);
    frapper('g', { cible: champ });
    frapper('a', { cible: champ });
    frapper('?', { cible: champ });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('opens the shortcut list on `?`', () => {
    const { dialog } = start();
    const event = frapper('?');
    expect(dialog.open).toHaveBeenCalledWith(KeyboardShortcutsDialog, expect.anything());
    expect(event.defaultPrevented).toBe(true);
  });

  it('never stacks a single-key dialog over an open one', () => {
    const { dialog } = start();
    dialog.openDialogs.push({});
    frapper('?');
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('puts the caret in the page filter on `/`, selecting what is already typed', () => {
    start();
    const filtre = document.createElement('input');
    filtre.dataset['pageFilter'] = '';
    filtre.value = 'village';
    document.body.append(filtre);
    const event = frapper('/');
    expect(document.activeElement).toBe(filtre);
    expect(filtre.selectionStart).toBe(0);
    expect(filtre.selectionEnd).toBe('village'.length);
    expect(event.defaultPrevented).toBe(true);
  });

  it('leaves `/` to the browser on a page that has no filter', () => {
    start();
    const event = frapper('/');
    expect(event.defaultPrevented).toBe(false);
  });

  it('submits the focused form on Ctrl+Entrée, through its own submit button', () => {
    start();
    document.body.innerHTML = `
      <form><input name="nom" /><button type="submit">Enregistrer</button></form>
    `;
    const bouton = document.querySelector('button') as HTMLButtonElement;
    const clic = vi.fn();
    bouton.addEventListener('click', clic);
    // What `(ngSubmit)` does in the application, and what jsdom needs to stop
    // short of the navigation it does not implement.
    document.querySelector('form')?.addEventListener('submit', (submit) => submit.preventDefault());
    const champ = document.querySelector('input') as HTMLInputElement;
    champ.focus();
    const event = frapper('Enter', { ctrlKey: true, cible: champ });
    expect(clic).toHaveBeenCalledOnce();
    expect(event.defaultPrevented).toBe(true);
  });

  it('refuses to submit a form whose save button the page has disabled', () => {
    start();
    document.body.innerHTML = `
      <form><input name="nom" /><button type="submit" disabled>Enregistrer</button></form>
    `;
    const bouton = document.querySelector('button') as HTMLButtonElement;
    const clic = vi.fn();
    bouton.addEventListener('click', clic);
    const champ = document.querySelector('input') as HTMLInputElement;
    champ.focus();
    frapper('Enter', { ctrlKey: true, cible: champ });
    expect(clic).not.toHaveBeenCalled();
  });

  it('leaves Ctrl+Entrée alone outside any form', () => {
    start();
    const event = frapper('Enter', { ctrlKey: true });
    expect(event.defaultPrevented).toBe(false);
  });

  it('ignores a key another listener has already handled', () => {
    const { dialog } = start();
    const event = new KeyboardEvent('keydown', {
      key: 'k',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    });
    event.preventDefault();
    document.body.dispatchEvent(event);
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('stops listening once the shell that started it is gone', () => {
    const { service, router } = start();
    service.stop();
    frapper('g');
    frapper('a');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('registers a single listener however often start() is called', () => {
    const { service, router } = start();
    service.start();
    service.start();
    frapper('g');
    frapper('a');
    expect(router.navigateByUrl).toHaveBeenCalledOnce();
  });

  // WCAG 2.1.4: single-key shortcuts can be turned off on this browser.
  describe('with the single-key shortcuts turned off', () => {
    afterEach(() => localStorage.clear());

    it('ignores g, / and ? but keeps the modifier combinations', () => {
      const { dialog, router } = start();
      TestBed.inject(SingleKeyShortcutsService).set(false);
      const filtre = document.createElement('input');
      filtre.dataset['pageFilter'] = '';
      document.body.append(filtre);

      frapper('g');
      frapper('l');
      frapper('?');
      frapper('/');

      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(dialog.open).not.toHaveBeenCalled();
      expect(document.activeElement).not.toBe(filtre);

      frapper('k', { ctrlKey: true });
      expect(dialog.open).toHaveBeenCalledWith(CommandPaletteDialog, expect.anything());
    });

    it('arms them again once turned back on', () => {
      const { dialog } = start();
      const preference = TestBed.inject(SingleKeyShortcutsService);
      preference.set(false);
      preference.set(true);

      frapper('?');

      expect(dialog.open).toHaveBeenCalledWith(KeyboardShortcutsDialog, expect.anything());
    });
  });
});
