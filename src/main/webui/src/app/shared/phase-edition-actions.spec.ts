// The phase gestures: each asks first, says what the new phase freezes or opens,
// reads the edition and the freeze again, and words a refusal.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../core/api/editions-api';
import { EditionStore } from '../core/edition.store';
import { GelReferentielStore } from '../core/gel-referentiel.store';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';
import { PhaseEditionActions } from './phase-edition-actions';

describe('PhaseEditionActions', () => {
  const api = {
    setPhase: vi.fn(),
    unblockStructure: vi.fn(),
    refreezeStructure: vi.fn(),
    etat: vi.fn(),
  };
  const editionStore = { reload: vi.fn() };
  const gel = { reload: vi.fn() };
  const confirm = { ask: vi.fn() };
  const notify = vi.fn();
  let actions: PhaseEditionActions;

  beforeEach(() => {
    for (const fn of [
      ...Object.values(api),
      editionStore.reload,
      gel.reload,
      confirm.ask,
      notify,
    ]) {
      fn.mockReset();
    }
    editionStore.reload.mockResolvedValue(undefined);
    gel.reload.mockResolvedValue(undefined);
    confirm.ask.mockResolvedValue(true);
    api.etat.mockResolvedValue({
      publication: { jamaisPublie: true },
      resolution: { resolue: false },
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: EditionsApi, useValue: api },
        { provide: EditionStore, useValue: editionStore },
        { provide: GelReferentielStore, useValue: gel },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
    actions = TestBed.inject(PhaseEditionActions);
  });

  it('says what the phase freezes before moving to it, then reads everything again', async () => {
    api.setPhase.mockResolvedValue({});

    expect(await actions.changePhase('CALCUL')).toBe(true);

    expect(confirm.ask.mock.calls[0][0].title).toBe("Passer l'édition en phase « Calcul » ?");
    expect(confirm.ask.mock.calls[0][0].message).toContain('figés');
    expect(api.setPhase).toHaveBeenCalledWith('CALCUL');
    expect(editionStore.reload).toHaveBeenCalledOnce();
    expect(gel.reload).toHaveBeenCalledOnce();
    expect(notify.mock.calls[0][0].variant).toBe('success');
  });

  it('touches nothing when the confirmation is declined', async () => {
    confirm.ask.mockResolvedValue(false);

    expect(await actions.changePhase('PREPARATION')).toBe(false);
    expect(await actions.unblockStructure()).toBe(false);

    expect(api.setPhase).not.toHaveBeenCalled();
    expect(api.unblockStructure).not.toHaveBeenCalled();
    expect(notify).not.toHaveBeenCalled();
  });

  it('recalls what a reopened referential would move, as a danger', async () => {
    api.unblockStructure.mockResolvedValue({});

    await actions.unblockStructure();

    const asked = confirm.ask.mock.calls[0][0];
    expect(asked.danger).toBe(true);
    // Nothing published, nothing solved: nothing to recall.
    await expect(asked.detail).resolves.toBe('');
    expect(api.unblockStructure).toHaveBeenCalledOnce();
  });

  it('freezes the structure again without a danger dialog', async () => {
    api.refreezeStructure.mockResolvedValue({});

    expect(await actions.refreezeStructure()).toBe(true);

    expect(confirm.ask.mock.calls[0][0].danger).toBe(false);
    expect(api.refreezeStructure).toHaveBeenCalledOnce();
  });

  // A refusal often means another session moved the phase: the screens read it again.
  it('says a refusal and reads where the edition stands', async () => {
    api.setPhase.mockRejectedValue(new Error('Serveur injoignable'));

    expect(await actions.changePhase('TERMINEE')).toBe(false);

    expect(notify.mock.calls[0][0]).toMatchObject({ title: 'Phase non changée', variant: 'error' });
    expect(notify).toHaveBeenCalledOnce();
    expect(editionStore.reload).toHaveBeenCalledOnce();
    expect(actions.busy()).toBe(false);
  });
});
