import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../core/api/editions-api';
import { EditionStore } from '../core/edition.store';
import { ActivationPreview, Edition } from '../core/models';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';
import { activationMessage, EditionActivationActions } from './edition-activation-actions';

const edition: Edition = { id: 'B', nom: 'Année 2026', active: false, creeLe: null };
const apercu: ActivationPreview = {
  sortante: { id: 'A', nom: 'Année 2025', active: true, creeLe: null },
  jobsEnFile: 2,
  resolutionEnCours: true,
  demandesOuvertes: 3,
  liensAnimateurs: 4,
  liensMuraux: 1,
};

describe('EditionActivationActions', () => {
  const api = {
    activationPreview: vi.fn(),
    activate: vi.fn(),
    deactivate: vi.fn(),
  };
  const confirm = { ask: vi.fn() };
  const notify = vi.fn();
  const reload = vi.fn();
  let actions: EditionActivationActions;

  beforeEach(() => {
    vi.resetAllMocks();
    api.activationPreview.mockResolvedValue(apercu);
    api.activate.mockResolvedValue(undefined);
    api.deactivate.mockResolvedValue(undefined);
    reload.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: EditionsApi, useValue: api },
        { provide: EditionStore, useValue: { reload } },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
    actions = TestBed.inject(EditionActivationActions);
  });

  it('activates after the confirmation and reloads the store', async () => {
    confirm.ask.mockResolvedValue(true);
    expect(await actions.activate(edition)).toBe(true);
    expect(confirm.ask.mock.calls[0][0].message).toContain('Année 2025');
    expect(api.activate).toHaveBeenCalledWith('B');
    expect(reload).toHaveBeenCalled();
  });

  it('does nothing when the confirmation is refused', async () => {
    confirm.ask.mockResolvedValue(false);
    expect(await actions.activate(edition)).toBe(false);
    expect(api.activate).not.toHaveBeenCalled();
  });

  it('reports a failed preview without asking', async () => {
    api.activationPreview.mockRejectedValue(new Error('boom'));
    expect(await actions.activate(edition)).toBe(false);
    expect(confirm.ask).not.toHaveBeenCalled();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'error' }));
  });

  it('reports the refusal of a running solve', async () => {
    confirm.ask.mockResolvedValue(true);
    api.activate.mockRejectedValue(new Error('calcul en cours'));
    expect(await actions.activate(edition)).toBe(false);
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'error' }));
    expect(reload).not.toHaveBeenCalled();
  });

  it('deactivates only once confirmed', async () => {
    confirm.ask.mockResolvedValueOnce(false);
    expect(await actions.deactivate(edition)).toBe(false);
    expect(api.deactivate).not.toHaveBeenCalled();

    confirm.ask.mockResolvedValueOnce(true);
    expect(await actions.deactivate(edition)).toBe(true);
    expect(api.deactivate).toHaveBeenCalledWith('B');
  });

  it('warns that a running solve will refuse the switch', () => {
    expect(activationMessage(apercu)).toContain("demande(s) d'échange");
    expect(activationMessage(apercu)).toContain('sera refusée');
  });
});
