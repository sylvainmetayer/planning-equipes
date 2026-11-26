// « Paramètres › Notifications »: the record is saved under the `modifieLe` it
// was loaded with, so a section written since is a 409 answered by overwriting
// (no stamp), reloading or neither — and two saves in a row never conflict.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../../core/api.service';
import { AdminApi } from '../../core/api/admin-api';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ParametresNotificationsPanel } from './parametres-notifications';

const STAMP = '2026-11-26T10:00:00Z';
const NEXT_STAMP = '2026-11-26T10:05:00Z';

const NOTIFICATIONS = {
  modifieLe: STAMP,
  heureRappelVeille: '18:00',
  delaiRelanceHeures: 48,
  ancienneteEchangeJours: 3,
};

describe('ParametresNotificationsPanel', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let confirm: { askThreeWay: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let fixture: ComponentFixture<ParametresNotificationsPanel>;

  const STALE = new ApiError(409, 'conflict', 'Modifiée.', CODE_MODIFICATION_CONCURRENTE);

  async function open(): Promise<void> {
    fixture = TestBed.createComponent(ParametresNotificationsPanel);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function typeDelay(value: string): void {
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      'input[type=number][max="720"]',
    )!;
    input.value = value;
    input.dispatchEvent(new Event('change'));
  }

  async function save(): Promise<void> {
    await fixture.componentInstance['enregistrer']();
  }

  beforeEach(() => {
    confirm = { askThreeWay: vi.fn() };
    notify = vi.fn();
    api = {
      notificationSettings: vi.fn(() => Promise.resolve({ ...NOTIFICATIONS })),
      saveNotificationSettings: vi.fn((parametres: object) =>
        Promise.resolve({ ...parametres, modifieLe: NEXT_STAMP }),
      ),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminApi, useValue: api },
        { provide: NotificationService, useValue: { notify } },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
  });

  it('saves the record under its stamp, then under the stamp the write gave back', async () => {
    await open();
    typeDelay('72');

    await save();
    expect(api['saveNotificationSettings']).toHaveBeenLastCalledWith({
      ...NOTIFICATIONS,
      delaiRelanceHeures: 72,
    });

    typeDelay('96');
    await save();
    expect(api['saveNotificationSettings']).toHaveBeenLastCalledWith({
      ...NOTIFICATIONS,
      delaiRelanceHeures: 96,
      modifieLe: NEXT_STAMP,
    });
    expect(confirm.askThreeWay).not.toHaveBeenCalled();
  });

  it('overwrites a section written since: the same record, sent without a stamp', async () => {
    await open();
    typeDelay('72');
    api['saveNotificationSettings'].mockRejectedValueOnce(STALE);
    confirm.askThreeWay.mockResolvedValue(true);

    await save();

    expect(api['saveNotificationSettings']).toHaveBeenCalledTimes(2);
    expect(api['saveNotificationSettings']).toHaveBeenLastCalledWith({
      ...NOTIFICATIONS,
      delaiRelanceHeures: 72,
      modifieLe: null,
    });
  });

  it('reloads on request, saving nothing and saying so', async () => {
    await open();
    typeDelay('72');
    api['saveNotificationSettings'].mockRejectedValueOnce(STALE);
    confirm.askThreeWay.mockResolvedValue(false);
    const reads = api['notificationSettings'].mock.calls.length;

    await save();

    expect(api['saveNotificationSettings']).toHaveBeenCalledOnce();
    expect(api['notificationSettings'].mock.calls.length).toBe(reads + 1);
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'warning' }));
  });

  it('says the refusal and keeps what was typed when the dialog is dismissed', async () => {
    await open();
    typeDelay('72');
    api['saveNotificationSettings'].mockRejectedValueOnce(STALE);
    confirm.askThreeWay.mockResolvedValue(null);

    await save();

    expect(api['saveNotificationSettings']).toHaveBeenCalledOnce();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'error' }));
    expect(fixture.componentInstance['parametres']()?.delaiRelanceHeures).toBe(72);
  });
});
