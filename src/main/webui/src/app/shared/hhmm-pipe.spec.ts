import { describe, expect, it } from 'vitest';
import { HhmmPipe } from './hhmm-pipe';

describe('HhmmPipe', () => {
  const pipe = new HhmmPipe();

  it('drops the seconds the API sends, and keeps a time already short', () => {
    expect(pipe.transform('18:00:00')).toBe('18:00');
    expect(pipe.transform('09:30')).toBe('09:30');
  });

  it('prints nothing for a missing time', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });
});
