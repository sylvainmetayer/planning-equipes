import { describe, expect, it } from 'vitest';
import {
  POIDS_IMPORTANCE,
  POIDS_MAX,
  clampWeight,
  importanceAtMinimum,
  importanceOf,
} from './importance';

describe('importance', () => {
  it('reads the three positions from their weights, anything else as its own value', () => {
    expect(importanceOf(1)).toBe('FAIBLE');
    expect(importanceOf(5)).toBe('NORMALE');
    expect(importanceOf(25)).toBe('FORTE');
    expect(importanceOf(7)).toBeNull();
    expect(importanceOf(100)).toBeNull();
  });

  it('writes 1, 5 and 25, the scale of ADR 0057', () => {
    expect(POIDS_IMPORTANCE).toEqual({ FAIBLE: 1, NORMALE: 5, FORTE: 25 });
  });

  /**
   * The Diagnostic hides « Baisser l'importance » on a rule at the bottom: the
   * button used to lead to a field already reading 1, twelve times out of
   * twelve on the test data.
   */
  it('says when a rule cannot go any lower', () => {
    expect(importanceAtMinimum({ poids: 1 })).toBe(true);
    expect(importanceAtMinimum({ poids: 5 })).toBe(false);
    expect(importanceAtMinimum({ poids: 2 })).toBe(false);
  });

  it('brings a typed weight into the range the server accepts', () => {
    expect(clampWeight(0, 5)).toBe(1);
    expect(clampWeight(12.6, 5)).toBe(13);
    expect(clampWeight(10_000, 5)).toBe(POIDS_MAX);
    expect(clampWeight(Number.NaN, 5)).toBe(5);
  });
});
