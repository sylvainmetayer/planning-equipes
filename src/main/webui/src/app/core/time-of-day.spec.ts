import { describe, expect, it } from 'vitest';
import { endMinutesOfDay, formatDuration, formatHourTick, minutesOfDay } from './time-of-day';

describe('minutesOfDay', () => {
  it('counts the minutes since midnight', () => {
    expect(minutesOfDay('00:00')).toBe(0);
    expect(minutesOfDay('09:30')).toBe(570);
    expect(minutesOfDay('23:59')).toBe(1439);
  });
});

describe('endMinutesOfDay', () => {
  it('reads a closing time of midnight as the end of the day, never the start of the next one', () => {
    expect(endMinutesOfDay('00:00')).toBe(1440);
    expect(endMinutesOfDay('19:00')).toBe(1140);
  });
});

describe('formatHourTick', () => {
  it('pads the hour and folds the closing midnight back to 00', () => {
    expect(formatHourTick(9)).toBe('09:00');
    expect(formatHourTick(24)).toBe('00:00');
  });
});

describe('formatDuration', () => {
  it('drops the empty half of the duration', () => {
    expect(formatDuration(45)).toContain('45');
    expect(formatDuration(180)).toContain('3');
    expect(formatDuration(210)).toContain('30');
  });
});
