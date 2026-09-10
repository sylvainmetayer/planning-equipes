// The "snapshot before a destructive action" safety net, which had no test.
//
// Three properties matter here, and only the first is obvious:
//  - it offers, it never imposes — declining must leave the plan untouched;
//  - it never blocks: a capture that fails is reported and swallowed, because
//    the caller awaits `proposer()` and then goes on to wipe the database;
//  - the snapshot is named after the action it precedes, which is the only
//    thing that lets the user find it again afterwards.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../core/notification.service';
import { PlanSnapshotStore } from '../core/plan-snapshot.store';
import { ConfirmService } from './confirm-dialog';
import { InstantaneAvantAction } from './instantane-avant-action';

describe('InstantaneAvantAction', () => {
  const confirm = { ask: vi.fn() };
  const snapshots = { capturer: vi.fn() };
  const notifications = { notify: vi.fn() };

  beforeEach(() => {
    confirm.ask.mockReset();
    snapshots.capturer.mockReset();
    notifications.notify.mockReset();
    confirm.ask.mockResolvedValue(true);
    snapshots.capturer.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ConfirmService, useValue: confirm },
        { provide: PlanSnapshotStore, useValue: snapshots },
        { provide: NotificationService, useValue: notifications },
      ],
    });
  });

  function service(): InstantaneAvantAction {
    return TestBed.inject(InstantaneAvantAction);
  }

  it('captures the plan when the offer is accepted', async () => {
    await service().proposer('vider la base');

    expect(confirm.ask).toHaveBeenCalledOnce();
    expect(snapshots.capturer).toHaveBeenCalledOnce();
  });

  // A safety net, not a gate: declining is a normal outcome and writes nothing.
  it('captures nothing when the offer is declined', async () => {
    confirm.ask.mockResolvedValue(false);

    await service().proposer('vider la base');

    expect(snapshots.capturer).not.toHaveBeenCalled();
    expect(notifications.notify).not.toHaveBeenCalled();
  });

  it('names the snapshot after the action it precedes', async () => {
    await service().proposer('rejouer un dump SQL');

    expect(snapshots.capturer).toHaveBeenCalledWith(expect.stringContaining('rejouer un dump SQL'));
    expect(snapshots.capturer).toHaveBeenCalledWith(expect.stringContaining('Avant'));
  });

  it('names the action in the question it asks', async () => {
    await service().proposer('vider la base');

    expect(confirm.ask).toHaveBeenCalledWith(
      expect.objectContaining({ message: expect.stringContaining('vider la base') }),
    );
  });

  // The caller awaits `proposer()` and then wipes the database. If a failed
  // capture propagated, a snapshot server hiccup would silently cancel the
  // destructive action the user had already confirmed.
  it('never rejects when the capture fails, so the action still runs', async () => {
    snapshots.capturer.mockRejectedValue(new Error('Instantané refusé par le serveur.'));

    await expect(service().proposer('vider la base')).resolves.toBeUndefined();
  });

  it('reports a failed capture as an error notification rather than swallowing it', async () => {
    snapshots.capturer.mockRejectedValue(new Error('Instantané refusé par le serveur.'));

    await service().proposer('vider la base');

    expect(notifications.notify).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        variant: 'error',
        message: expect.stringContaining('Instantané refusé par le serveur.'),
      }),
    );
  });

  it('says nothing when the capture succeeds', async () => {
    await service().proposer('vider la base');

    expect(notifications.notify).not.toHaveBeenCalled();
  });

  // The offer must not read as dangerous: it is the one reassuring step of a
  // flow whose next dialog already was.
  it('offers the capture without the danger styling of the action itself', async () => {
    await service().proposer('vider la base');

    expect(confirm.ask).toHaveBeenCalledWith(expect.not.objectContaining({ danger: true }));
  });

  // Fire-and-forget would race the destructive action the caller runs next:
  // the snapshot would read a database that is already half rewritten.
  it('does not let the caller proceed until the capture has landed', async () => {
    let resolveCapture!: () => void;
    snapshots.capturer.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveCapture = resolve;
      }),
    );

    let finished = false;
    const running = service()
      .proposer('vider la base')
      .then(() => {
        finished = true;
      });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(finished).toBe(false);

    resolveCapture();
    await running;
    expect(finished).toBe(true);
  });
});
