import { describe, expect, it } from 'vitest';
import { formatSeconds } from './solver-duration';

describe('formatSeconds', () => {
  it('says a budget in the unit a person would say it in', () => {
    expect(formatSeconds(7200)).toBe('2 h');
    expect(formatSeconds(900)).toBe('15 min');
    expect(formatSeconds(90)).toBe('90 s');
    expect(formatSeconds(0)).toBe('0 s');
  });
});
