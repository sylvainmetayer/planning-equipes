import { describe, expect, it } from 'vitest';
import { lienCarte } from './lien-carte';

describe('lienCarte', () => {
  it('points a geocoded emplacement at OpenStreetMap', () => {
    // The same address shape as the individual PDF: a planning read on paper
    // and the same planning read on screen open the same pin.
    expect(lienCarte(47.2184, -1.5536)).toBe(
      'https://www.openstreetmap.org/?mlat=47.2184&mlon=-1.5536#map=18/47.2184/-1.5536',
    );
  });

  it('offers nothing until both coordinates are there', () => {
    // Half a position points at the Gulf of Guinea: worse than no link at all.
    expect(lienCarte(47.2184, null)).toBeNull();
    expect(lienCarte(null, -1.5536)).toBeNull();
    expect(lienCarte(null, null)).toBeNull();
  });

  it('accepts zero, which is a coordinate like any other', () => {
    expect(lienCarte(0, 0)).toContain('mlat=0&mlon=0');
  });
});
