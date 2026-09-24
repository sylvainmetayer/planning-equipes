import { describe, expect, it } from 'vitest';
import { nextGridCell } from './grid-navigation';

describe('nextGridCell', () => {
  const from = { ligne: 1, colonne: 1 };

  it('moves one cell with each arrow', () => {
    expect(nextGridCell('ArrowRight', from, 2, 2)).toEqual({ ligne: 1, colonne: 2 });
    expect(nextGridCell('ArrowLeft', from, 2, 2)).toEqual({ ligne: 1, colonne: 0 });
    expect(nextGridCell('ArrowDown', from, 2, 2)).toEqual({ ligne: 2, colonne: 1 });
    expect(nextGridCell('ArrowUp', from, 2, 2)).toEqual({ ligne: 0, colonne: 1 });
  });

  it('stops at the edges rather than wrapping', () => {
    const corner = { ligne: 2, colonne: 2 };
    expect(nextGridCell('ArrowRight', corner, 2, 2)).toEqual(corner);
    expect(nextGridCell('ArrowDown', corner, 2, 2)).toEqual(corner);
    const origin = { ligne: 0, colonne: 0 };
    expect(nextGridCell('ArrowLeft', origin, 2, 2)).toEqual(origin);
    expect(nextGridCell('ArrowUp', origin, 2, 2)).toEqual(origin);
  });

  it('sends Home and End to the ends of the row', () => {
    expect(nextGridCell('Home', from, 2, 4)).toEqual({ ligne: 1, colonne: 0 });
    expect(nextGridCell('End', from, 2, 4)).toEqual({ ligne: 1, colonne: 4 });
  });

  it('answers null for a key that is not a move, so it can travel on', () => {
    expect(nextGridCell('Enter', from, 2, 2)).toBeNull();
    expect(nextGridCell('g', from, 2, 2)).toBeNull();
  });
});
