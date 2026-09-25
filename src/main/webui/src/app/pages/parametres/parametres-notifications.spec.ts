// The scheduled-notifications card: arming a second edition is refused by the
// server with a 409 naming the armed one, and that sentence stays on the card
// instead of passing by in a snack-bar.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { ApiError } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ParametresNotificationsPanel } from './parametres-notifications';

type PanelInternals = {
  majActives: (actives: boolean) => void;
  enregistrer: () => Promise<void>;
  refus: () => string | null;
};

describe('ParametresNotificationsPanel', () => {
  const adminApi = { notificationSettings: vi.fn(), saveNotificationSettings: vi.fn() };
  const notifications = { notify: vi.fn() };

  beforeEach(() => {
    adminApi.notificationSettings.mockReset();
    adminApi.saveNotificationSettings.mockReset();
    notifications.notify.mockReset();
    adminApi.notificationSettings.mockResolvedValue({
      actives: false,
      heureRappelVeille: '18:00:00',
      delaiRelanceHeures: 72,
      ancienneteEchangeJours: 3,
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminApi, useValue: adminApi },
        { provide: NotificationService, useValue: notifications },
      ],
    });
  });

  async function createPanel(): Promise<PanelInternals> {
    const fixture = TestBed.createComponent(ParametresNotificationsPanel);
    // The first render runs ngOnInit, which starts the load.
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.componentInstance as unknown as PanelInternals;
  }

  it('keeps the refusal to arm a second edition on the card, naming the armed one', async () => {
    const message = 'Les envois automatiques sont déjà activés sur l’édition « 2025 ».';
    adminApi.saveNotificationSettings.mockRejectedValue(new ApiError(409, 'conflict', message));
    const panel = await createPanel();

    panel.majActives(true);
    await panel.enregistrer();

    expect(panel.refus()).toBe(message);
    expect(notifications.notify).not.toHaveBeenCalled();
  });

  it('clears the refusal once a save goes through', async () => {
    adminApi.saveNotificationSettings.mockRejectedValueOnce(new ApiError(409, 'conflict', 'refus'));
    adminApi.saveNotificationSettings.mockImplementation(async (body: unknown) => body);
    const panel = await createPanel();

    panel.majActives(true);
    await panel.enregistrer();
    panel.majActives(false);
    await panel.enregistrer();

    expect(panel.refus()).toBeNull();
  });
});
