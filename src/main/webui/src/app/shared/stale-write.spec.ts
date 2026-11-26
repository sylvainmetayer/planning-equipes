import { describe, expect, it, vi } from 'vitest';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../core/api.service';
import { fakeOf } from '../core/testing/fake';
import { ConfirmService } from './confirm-dialog';
import { NotificationService } from '../core/notification.service';
import { askStaleChoice, isStaleWrite, writeOrAskStale } from './stale-write';

const stale = new ApiError(
  409,
  'conflict',
  'Ce webhook a été modifié.',
  CODE_MODIFICATION_CONCURRENTE,
);

function confirmAnswering(answer: boolean | null): Pick<ConfirmService, 'askThreeWay'> {
  return fakeOf<Pick<ConfirmService, 'askThreeWay'>>({
    askThreeWay: () => Promise.resolve(answer),
  });
}

describe('stale write', () => {
  it('tells the 409 of a concurrent write from any other refusal', () => {
    expect(isStaleWrite(stale)).toBe(true);
    expect(isStaleWrite(new ApiError(409, 'conflict', 'Solveur occupé'))).toBe(false);
    expect(isStaleWrite(new Error('boom'))).toBe(false);
  });

  it('keeps overwrite, reload and dismissal apart', async () => {
    expect(await askStaleChoice(confirmAnswering(true), stale)).toBe('overwrite');
    expect(await askStaleChoice(confirmAnswering(false), stale)).toBe('reload');
    expect(await askStaleChoice(confirmAnswering(null), stale)).toBe('dismiss');
  });

  describe('writeOrAskStale', () => {
    function deps(answer: boolean | null) {
      return {
        confirm: confirmAnswering(answer),
        notifications: fakeOf<NotificationService>({ notify: vi.fn() }),
      };
    }

    it('answers a stale write by overwriting, without the stamp', async () => {
      const write = vi.fn((overwrite: boolean) =>
        overwrite ? Promise.resolve('ecrit') : Promise.reject(stale),
      );
      const result = await writeOrAskStale(deps(true), write, () => Promise.resolve());
      expect(result).toEqual({ saved: 'ecrit' });
      expect(write.mock.calls).toEqual([[false], [true]]);
    });

    it('reloads on request and says nothing was saved', async () => {
      const reload = vi.fn(() => Promise.resolve());
      const d = deps(false);
      const result = await writeOrAskStale(d, () => Promise.reject(stale), reload);
      expect(result).toBeNull();
      expect(reload).toHaveBeenCalledOnce();
      expect(d.notifications.notify).toHaveBeenCalledOnce();
    });

    it('rethrows when dismissed, and any refusal that is not a stale write', async () => {
      await expect(
        writeOrAskStale(
          deps(null),
          () => Promise.reject(stale),
          () => Promise.resolve(),
        ),
      ).rejects.toBe(stale);
      const other = new ApiError(400, 'invalid', 'Valeur refusée');
      await expect(
        writeOrAskStale(
          deps(true),
          () => Promise.reject(other),
          () => Promise.resolve(),
        ),
      ).rejects.toBe(other);
    });
  });
});
