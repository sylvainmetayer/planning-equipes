// The state control of a day: « Figée » asks first and never goes through
// unconfirmed; the other two states are saved directly.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ValidationsApi } from '../../core/api/validations-api';
import { DayState, PrerequisJournee } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ValidationPanel } from './validation-panel';

function lu(etat: DayState): PrerequisJournee {
  return {
    jour: '2026-09-05',
    validee: etat !== 'A_RELIRE',
    validationId: null,
    valideeLe: null,
    validePar: null,
    valideParNom: null,
    commentaire: 'vu avec la régie',
    prerequis: [],
    tousSatisfaits: true,
    etat,
  };
}

async function monter(etat: DayState, confirmed: boolean) {
  const store = { setEtat: vi.fn(() => Promise.resolve({})), progression: () => ({}) };
  const confirm = { ask: vi.fn(() => Promise.resolve(confirmed)) };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ValidationsApi, useValue: { prerequis: () => Promise.resolve(lu(etat)) } },
      { provide: ValidationsStore, useValue: store },
      { provide: ConfirmService, useValue: confirm },
    ],
  });
  const fixture = TestBed.createComponent(ValidationPanel);
  fixture.componentRef.setInput('jour', '2026-09-05');
  await fixture.whenStable();
  const instance = fixture.componentInstance;
  const panel = {
    choisir: (valeur: DayState) => instance['choix'].set(valeur),
    save: () => instance['save'](),
  };
  return { panel, store, confirm };
}

describe('ValidationPanel', () => {
  it('asks before freezing, and saves nothing when declined', async () => {
    const { panel, store, confirm } = await monter('RELUE', false);
    panel.choisir('FIGEE');

    await panel.save();

    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(store.setEtat).not.toHaveBeenCalled();
  });

  it('freezes once confirmed, keeping the comment the day carries', async () => {
    const { panel, store } = await monter('RELUE', true);
    panel.choisir('FIGEE');

    await panel.save();

    expect(store.setEtat).toHaveBeenCalledWith('2026-09-05', 'FIGEE', 'vu avec la régie');
  });

  it('reads a day without asking', async () => {
    const { panel, store, confirm } = await monter('A_RELIRE', false);
    panel.choisir('RELUE');

    await panel.save();

    expect(confirm.ask).not.toHaveBeenCalled();
    expect(store.setEtat).toHaveBeenCalledWith('2026-09-05', 'RELUE', 'vu avec la régie');
  });
});
