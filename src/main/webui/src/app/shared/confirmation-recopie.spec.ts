// The typed-back confirmation guarding the two actions that destroy data.
//
// What is under test is a gate, so the interesting cases are the ones where it
// must stay shut: an approximate transcription and a cancellation both have to
// leave the caller with a `false` it cannot mistake for a confirmation. The
// dialog itself is not rendered — `MatDialog` is mocked, which is what lets
// these cases be written one line each.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../core/notification.service';
import { ConfirmationRecopie, recopieValide } from './confirmation-recopie';
import { PromptDialog } from './prompt-dialog';

describe('recopieValide', () => {
  it('accepts the exact text, whatever whitespace surrounds it', () => {
    expect(recopieValide('Année 2026', 'Année 2026')).toBe(true);
    expect(recopieValide('  Année 2026 ', 'Année 2026')).toBe(true);
  });

  it('refuses anything else, including a cancellation and a case shift', () => {
    expect(recopieValide(null, 'REMPLACER')).toBe(false);
    expect(recopieValide('', 'REMPLACER')).toBe(false);
    expect(recopieValide('remplacer', 'REMPLACER')).toBe(false);
    expect(recopieValide('Année 2025', 'Année 2026')).toBe(false);
  });
});

function injectService(): ConfirmationRecopie {
  return TestBed.inject(ConfirmationRecopie);
}

describe('ConfirmationRecopie', () => {
  const notifications = { notify: vi.fn() };
  const dialog = { open: vi.fn() };

  /** Makes the next dialog answer `saisi`; `null` stands for a cancellation. */
  function repond(saisi: string | null): void {
    dialog.open.mockReturnValue({ afterClosed: () => of(saisi) });
  }

  beforeEach(() => {
    notifications.notify.mockReset();
    dialog.open.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: notifications },
      ],
    });
  });

  const demande = {
    title: 'Vider la base de données ?',
    message: "Tout est supprimé pour l'édition Année 2026.",
    valeurAttendue: 'Année 2026',
  };

  it('confirms when the expected text was typed back, and says nothing more', async () => {
    repond('Année 2026');

    await expect(injectService().demander(demande)).resolves.toBe(true);
    expect(notifications.notify).not.toHaveBeenCalled();
  });

  it('refuses a wrong entry and tells the user nothing happened', async () => {
    repond('Année 2025');

    await expect(injectService().demander(demande)).resolves.toBe(false);
    expect(notifications.notify).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ variant: 'error' }),
    );
    // The expected text is repeated: a rejection the user cannot act on is a
    // dead end, and they will just click the button again.
    expect(notifications.notify.mock.calls[0][0].message).toContain('Année 2026');
  });

  it('refuses a cancellation without turning it into an error', async () => {
    repond(null);

    await expect(injectService().demander(demande)).resolves.toBe(false);
    expect(notifications.notify).not.toHaveBeenCalled();
  });

  it('asks through the shared prompt dialog, naming the text to type and flagging the danger', async () => {
    repond('Année 2026');

    await injectService().demander(demande);

    const [composant, config] = dialog.open.mock.calls[0];
    expect(composant).toBe(PromptDialog);
    expect(config.data.title).toBe(demande.title);
    expect(config.data.message).toBe(demande.message);
    expect(config.data.label).toContain('Année 2026');
    expect(config.data.danger).toBe(true);
  });
});
