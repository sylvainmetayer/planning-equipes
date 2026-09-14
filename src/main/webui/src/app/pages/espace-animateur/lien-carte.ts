// Map link of an emplacement (issue #534).
//
// OpenStreetMap, like the individual PDF and the admin map: one provider for
// the whole product, and the only one the application already sends people to.
// The same address shape as `AnimateurPlanningPdf.osmUrl`, so a planning read
// on paper and the same planning read on screen open the same pin.

/**
 * `https://www.openstreetmap.org/?mlat=…` for a geocoded emplacement, `null`
 * as soon as one of the two coordinates is missing: half a position points at
 * the Gulf of Guinea, which is worse than no link at all.
 */
export function lienCarte(latitude: number | null, longitude: number | null): string | null {
  if (!Number.isFinite(latitude as number) || !Number.isFinite(longitude as number)) {
    return null;
  }
  return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=18/${latitude}/${longitude}`;
}
