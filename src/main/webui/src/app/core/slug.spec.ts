import { describe, expect, it } from 'vitest';
import { slugify } from './slug';

describe('slugify', () => {
  it('uppercases and strips accents', () => {
    expect(slugify('Créneaux étendus', [])).toBe('CRENEAUX-ETENDUS');
  });

  it('collapses runs of non-alphanumeric characters into a single dash', () => {
    expect(slugify('Plan B — week  2 !', [])).toBe('PLAN-B-WEEK-2');
  });

  it('trims leading and trailing dashes', () => {
    expect(slugify('  ...plan...  ', [])).toBe('PLAN');
  });

  it('falls back to GROUPE when nothing alphanumeric is left', () => {
    expect(slugify('!!! ???', [])).toBe('GROUPE');
  });

  it('suffixes until the id no longer collides', () => {
    expect(slugify('Plan', ['PLAN'])).toBe('PLAN-2');
    expect(slugify('Plan', ['PLAN', 'PLAN-2', 'PLAN-3'])).toBe('PLAN-4');
  });

  it('leaves a free id untouched even when other ids exist', () => {
    expect(slugify('Plan', ['AUTRE', 'PLAN-2'])).toBe('PLAN');
  });
});
