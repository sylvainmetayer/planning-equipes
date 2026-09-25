// Great-circle distance between two emplacements, mirroring what the solver
// does server-side (`QualiteConstraints.emplacementsEloignes`, 300 m).
//
// The frontend needs it for one honest reason: two constraints reason in
// metres, so the referential screen must be able to say how far apart two
// emplacements actually are — an organiser cannot judge "éloigné" from two
// pairs of decimal coordinates.

const RAYON_TERRE_METRES = 6_371_000;

export interface PointGeo {
  latitude: number | null;
  longitude: number | null;
}

/** Metres between two points, or `null` as soon as one coordinate is missing. */
export function distanceMetres(depuis: PointGeo, vers: PointGeo): number | null {
  if (
    depuis.latitude === null ||
    depuis.longitude === null ||
    vers.latitude === null ||
    vers.longitude === null
  ) {
    return null;
  }
  const radians = (degres: number) => (degres * Math.PI) / 180;
  const deltaLatitude = radians(vers.latitude - depuis.latitude);
  const deltaLongitude = radians(vers.longitude - depuis.longitude);
  const a =
    Math.sin(deltaLatitude / 2) ** 2 +
    Math.cos(radians(depuis.latitude)) *
      Math.cos(radians(vers.latitude)) *
      Math.sin(deltaLongitude / 2) ** 2;
  return Math.round(2 * RAYON_TERRE_METRES * Math.asin(Math.min(1, Math.sqrt(a))));
}

/** Human distance: metres below a kilometre, kilometres with one decimal above. */
export function formatDistance(metres: number): string {
  return metres < 1000 ? `${metres} m` : `${(metres / 1000).toFixed(1)} km`;
}

/** What turns a distance into a walk: the edition's quality settings, or their defaults. */
export interface WalkingSettings {
  vitesseMarcheKmH?: number;
  facteurDetour?: number;
}

/** The server's defaults (`ParametresQualite`), used until the edition's own settings are read. */
export const WALKING_DEFAULTS: Required<WalkingSettings> = {
  vitesseMarcheKmH: 4,
  facteurDetour: 1.3,
};

/**
 * Walking minutes for a straight-line distance, rounded up — the arithmetic of
 * `WalkingTime` on the server: distance × detour factor ÷ speed. Zero for no
 * distance.
 */
export function walkingMinutes(metres: number, settings: WalkingSettings = {}): number {
  const speed = settings.vitesseMarcheKmH ?? WALKING_DEFAULTS.vitesseMarcheKmH;
  const factor = settings.facteurDetour ?? WALKING_DEFAULTS.facteurDetour;
  if (metres <= 0 || speed <= 0) {
    return 0;
  }
  const metresPerMinute = (speed * 1000) / 60;
  return Math.ceil((metres * factor) / metresPerMinute - 1e-9);
}
