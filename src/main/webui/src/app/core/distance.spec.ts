// Mirrors what the solver computes server-side (`QualiteConstraints
// .emplacementsEloignes`, 300 m). If these two ever disagree, the referential
// screen tells the organiser one thing and the solver penalises another.

import { describe, expect, it } from 'vitest';
import { distanceMetres, formatDistance } from './distance';

describe('distanceMetres', () => {
  it('is zero between a point and itself', () => {
    expect(distanceMetres({ latitude: 47.2184, longitude: -1.5536 }, { latitude: 47.2184, longitude: -1.5536 })).toBe(0);
  });

  it('measures a known north-south gap: one minute of latitude is a nautical mile', () => {
    // Exact, not a tolerance: a loose band would not notice the earth radius
    // drifting away from the 6 371 km the solver uses server-side.
    const metres = distanceMetres({ latitude: 47, longitude: -1.5 }, { latitude: 47 + 1 / 60, longitude: -1.5 });
    expect(metres).toBe(1853);
  });

  it('is symmetric, so the pair order on screen never changes the answer', () => {
    const nantes = { latitude: 47.2184, longitude: -1.5536 };
    const rennes = { latitude: 48.1173, longitude: -1.6778 };
    expect(distanceMetres(nantes, rennes)).toBe(distanceMetres(rennes, nantes));
  });

  it('agrees to the metre with the known Nantes-Rennes distance', () => {
    const metres = distanceMetres(
      { latitude: 47.2184, longitude: -1.5536 },
      { latitude: 48.1173, longitude: -1.6778 }
    );
    expect(metres).toBe(100_385);
  });

  it('resolves the 300 m threshold the solver reasons with', () => {
    // ~0.0018 degree of latitude is right around 200 m: clearly under the bar.
    const proche = distanceMetres({ latitude: 47.2, longitude: -1.55 }, { latitude: 47.2018, longitude: -1.55 });
    expect(proche).toBe(200);
    // ~0.005 degree is around 550 m: clearly over it.
    const loin = distanceMetres({ latitude: 47.2, longitude: -1.55 }, { latitude: 47.205, longitude: -1.55 });
    expect(loin).toBe(556);
  });

  it('crosses the antimeridian without exploding', () => {
    const metres = distanceMetres({ latitude: 0, longitude: 179.99 }, { latitude: 0, longitude: -179.99 });
    expect(metres).toBe(2224);
  });

  it('returns null as soon as one coordinate is missing, rather than a wrong number', () => {
    const point = { latitude: 47.2, longitude: -1.55 };
    expect(distanceMetres({ latitude: null, longitude: -1.55 }, point)).toBeNull();
    expect(distanceMetres({ latitude: 47.2, longitude: null }, point)).toBeNull();
    expect(distanceMetres(point, { latitude: null, longitude: null })).toBeNull();
  });

  it('treats a zero coordinate as a real coordinate, not as a missing one', () => {
    expect(distanceMetres({ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 0 })).toBe(0);
  });
});

describe('formatDistance', () => {
  it('stays in metres below a kilometre', () => {
    expect(formatDistance(0)).toBe('0 m');
    expect(formatDistance(299)).toBe('299 m');
    expect(formatDistance(999)).toBe('999 m');
  });

  it('switches to kilometres with one decimal at a kilometre exactly', () => {
    expect(formatDistance(1000)).toBe('1.0 km');
    expect(formatDistance(1540)).toBe('1.5 km');
    expect(formatDistance(100_400)).toBe('100.4 km');
  });
});
