// A webhook written by another session since its form opened (issue #362):
// the form stays open as typed unless the write landed or the user chose to
// reload — the same rule as `ReferenceCrudService`.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WebhooksApi } from '../../core/api/webhooks-api';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../../core/api.service';
import { WebhookRequest, WebhookView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { WebhooksCard } from './webhooks-card';

const WEBHOOK: WebhookView = {
  id: 'w1',
  name: 'slack',
  format: 'SLACK',
  destination: '',
  chatId: null,
  events: [],
  active: true,
  createdAt: '2026-07-01T08:00:00Z',
  modifiedAt: '2026-07-02T09:00:00.123Z',
  lastDelivery: null,
};

const REQUEST: WebhookRequest = {
  name: 'renommé',
  format: 'SLACK',
  url: '',
  token: null,
  chatId: null,
  events: [],
  active: true,
};

const STALE = new ApiError(
  409,
  'conflict',
  'Ce webhook a été modifié par une autre session.',
  CODE_MODIFICATION_CONCURRENTE,
  '2026-07-03T10:00:00Z',
);

describe('WebhooksCard — a stale write', () => {
  let card: WebhooksCard;
  let api: { list: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn> };
  let confirm: { askThreeWay: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = {
      list: vi.fn(() => Promise.resolve([WEBHOOK])),
      update: vi
        .fn()
        .mockRejectedValueOnce(STALE)
        .mockResolvedValue({ webhook: WEBHOOK, secret: null }),
    };
    confirm = { askThreeWay: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: WebhooksApi, useValue: api },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
    card = TestBed.createComponent(WebhooksCard).componentInstance;
  });

  it('keeps the form open when the choice is dismissed', async () => {
    confirm.askThreeWay.mockResolvedValue(null);
    expect(await card['save'](WEBHOOK, REQUEST)).toBe(false);
    expect(api.update).toHaveBeenCalledTimes(1);
    expect(api.update.mock.calls[0][1].modifiedAt).toBe(WEBHOOK.modifiedAt);
  });

  it('overwrites without the precondition, then closes', async () => {
    confirm.askThreeWay.mockResolvedValue(true);
    expect(await card['save'](WEBHOOK, REQUEST)).toBe(true);
    expect(api.update.mock.calls[1][1].modifiedAt).toBeNull();
  });

  it('writes nothing and closes on « Recharger »', async () => {
    confirm.askThreeWay.mockResolvedValue(false);
    expect(await card['save'](WEBHOOK, REQUEST)).toBe(true);
    expect(api.update).toHaveBeenCalledTimes(1);
  });

  it('keeps the form open on any other refusal', async () => {
    api.update.mockReset().mockRejectedValue(new ApiError(400, 'invalid', 'Adresse refusée'));
    expect(await card['save'](WEBHOOK, REQUEST)).toBe(false);
  });
});
