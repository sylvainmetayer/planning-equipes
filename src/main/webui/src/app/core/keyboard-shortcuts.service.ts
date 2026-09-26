// The one global `keydown` listener of the admin application (issue #314).
//
// Started and stopped with the admin shell, exactly like `SolverJobService`:
// the service is `providedIn: 'root'` and would otherwise outlive the shell,
// leaving shortcuts armed behind the login page and inside the espace
// animateur — two screens that have neither a palette nor these destinations.
//
// What is deliberately NOT here: the Konami listener (an easter egg with its
// own sequence state), the arrow navigation of the calendars and the heatmap
// (local to one component and only meaningful while it is on screen), and
// Escape — every dialog of this application is a `MatDialog` and none of them
// sets `disableClose`, so Escape already closes the topmost one, focus
// restoration included. Re-implementing it would only be a second, competing
// closer.

import { DestroyRef, Injectable, inject } from '@angular/core';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { CommandPaletteDialog } from '../shared/command-palette-dialog';
import { KeyboardShortcutsDialog } from '../shared/keyboard-shortcuts-dialog';
import { CommandePalette, isInputField, routePourTouche } from './keyboard-shortcuts';
import { SingleKeyShortcutsService } from './single-key-shortcuts';

/**
 * How long the `g` prefix stays armed. Long enough to type two keys without
 * hurrying, short enough that a `g` typed by mistake does not silently turn
 * the next keystroke into a navigation.
 */
const DELAI_PREFIXE_MS = 1500;

/**
 * The filter field a `/` jumps to. Marked by an attribute rather than found by
 * component selector or by position: a page states which of its fields *is*
 * the filter, and the shortcut stays correct when the layout changes.
 */
const SELECTEUR_FILTRE = 'input[data-page-filter]';

@Injectable({ providedIn: 'root' })
export class KeyboardShortcutsService {
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly singleKey = inject(SingleKeyShortcutsService);

  private listener: ((event: KeyboardEvent) => void) | null = null;
  private palette: MatDialogRef<CommandPaletteDialog, CommandePalette | null> | null = null;
  private prefixeArmeJusqua = 0;

  /** Registers the listener. Calling it twice is a no-op, not a second listener. */
  start(): void {
    if (this.listener) {
      return;
    }
    this.listener = (event) => this.onKeyDown(event);
    document.addEventListener('keydown', this.listener);
    this.destroyRef.onDestroy(() => this.stop());
  }

  stop(): void {
    if (!this.listener) {
      return;
    }
    document.removeEventListener('keydown', this.listener);
    this.listener = null;
    this.prefixeArmeJusqua = 0;
  }

  /**
   * Two families of shortcuts, and the difference between them is the whole
   * safety of the feature.
   *
   * <p>A **modifier combination** (Ctrl+K, Ctrl+Entrée) cannot be mistaken for
   * typing, so it fires from anywhere — Ctrl+Entrée is meant to be pressed
   * from inside the field being filled.</p>
   *
   * <p>A **single key** (`g`, `/`, `?`) is a character someone may legitimately
   * want in a text field, so it only fires when the focus is not in one.</p>
   */
  private onKeyDown(event: KeyboardEvent): void {
    // Already handled (a Material overlay, a page's own listener): a shortcut
    // must never be the second reader of the same key press.
    if (event.defaultPrevented) {
      return;
    }
    if (this.gererCombinaison(event)) {
      return;
    }
    // Turned off on this browser (WCAG 2.1.4): the combinations above stay.
    if (
      !this.singleKey.enabled() ||
      event.ctrlKey ||
      event.metaKey ||
      event.altKey ||
      isInputField(event.target)
    ) {
      this.prefixeArmeJusqua = 0;
      return;
    }
    this.gererToucheSimple(event);
  }

  /** Ctrl/Cmd + K and Ctrl/Cmd + Entrée; `true` when the event was consumed. */
  private gererCombinaison(event: KeyboardEvent): boolean {
    if (!(event.ctrlKey || event.metaKey) || event.altKey) {
      return false;
    }
    if (event.key.toLowerCase() === 'k') {
      // Taken from the browser on purpose: Ctrl+K is where every application
      // the user already knows puts its command palette, and a shortcut that
      // works "except in Firefox's search bar" is not a shortcut.
      event.preventDefault();
      this.basculerPalette();
      return true;
    }
    if (event.key === 'Enter') {
      if (this.validerFormulaireActif()) {
        event.preventDefault();
      }
      return true;
    }
    return false;
  }

  private gererToucheSimple(event: KeyboardEvent): void {
    const prefixeArme = Date.now() < this.prefixeArmeJusqua;
    this.prefixeArmeJusqua = 0;
    if (prefixeArme) {
      const route = routePourTouche(event.key.toLowerCase());
      if (route) {
        event.preventDefault();
        void this.router.navigateByUrl(route);
      }
      return;
    }
    // A dialog is open: its own content owns the keyboard. Opening a second
    // one over it (a palette above a half-filled form) would stack two modal
    // focus traps for no benefit.
    if (this.dialog.openDialogs.length > 0) {
      return;
    }
    switch (event.key) {
      case 'g':
        this.prefixeArmeJusqua = Date.now() + DELAI_PREFIXE_MS;
        break;
      case '?':
        event.preventDefault();
        this.dialog.open(KeyboardShortcutsDialog, { width: '32rem', autoFocus: 'dialog' });
        break;
      case '/':
        this.focaliserFiltre(event);
        break;
      default:
        break;
    }
  }

  /**
   * Opens the palette, or closes it when it is already open — Ctrl+K is a
   * toggle everywhere else, and a shortcut that only opens leaves the user
   * reaching for the mouse to undo it.
   */
  private basculerPalette(): void {
    if (this.palette) {
      this.palette.close(null);
      return;
    }
    // Never over another dialog: see `gererToucheSimple`.
    if (this.dialog.openDialogs.length > 0) {
      return;
    }
    this.palette = this.dialog.open<CommandPaletteDialog, undefined, CommandePalette | null>(
      CommandPaletteDialog,
      {
        width: '36rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
        restoreFocus: true,
      },
    );
    this.palette.afterClosed().subscribe((commande) => {
      this.palette = null;
      if (commande?.externe) {
        // Served by the backend (the Quarkus Dev UI): the router would only
        // produce a client-side 404.
        window.open(commande.route, '_blank', 'noopener');
      } else if (commande) {
        void this.router.navigate([commande.route], { queryParams: commande.queryParams ?? {} });
      }
    });
  }

  /**
   * Moves the caret into the page's quick filter. Returns without consuming
   * the key when the page has none, so `/` keeps whatever meaning the browser
   * gives it (Firefox's quick find) instead of being silently swallowed.
   */
  private focaliserFiltre(event: KeyboardEvent): void {
    const filtre = document.querySelector<HTMLInputElement>(SELECTEUR_FILTRE);
    if (!filtre) {
      return;
    }
    event.preventDefault();
    filtre.focus();
    // Selecting what is already there makes the second `/` a "filter for
    // something else" instead of an append to the previous term.
    filtre.select();
  }

  /**
   * Submits the form the focus sits in, by activating its own submit button:
   * `requestSubmit()` would bypass a button disabled by the page's validity
   * rules and save a form the user cannot save with the mouse.
   *
   * <p>Returns whether anything was submitted, so an unhandled Ctrl+Entrée
   * still reaches the field underneath (a textarea inserting a newline).</p>
   */
  private validerFormulaireActif(): boolean {
    const actif = document.activeElement as HTMLElement | null;
    const formulaire =
      actif?.closest('form') ?? document.querySelector('.mat-mdc-dialog-container form');
    if (!formulaire) {
      return false;
    }
    const submit = formulaire.querySelector<HTMLButtonElement>(
      'button[type="submit"]:not([disabled])',
    );
    if (submit) {
      submit.click();
      return true;
    }
    if (formulaire.querySelector('button[type="submit"]')) {
      // A submit button exists but the page disabled it: the form is invalid,
      // and the keyboard must not do what the mouse is refused.
      return true;
    }
    formulaire.requestSubmit();
    return true;
  }
}
