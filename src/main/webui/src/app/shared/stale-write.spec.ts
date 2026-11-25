import { describe, expect, it } from 'vitest';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../core/api.service';
import { fakeOf } from '../core/testing/fake';
import { ConfirmService } from './confirm-dialog';
import { askStaleChoice, isStaleWrite } from './stale-write';

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
});
