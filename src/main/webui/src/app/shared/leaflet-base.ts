// Leaflet plumbing shared by every map of the application: the default marker
// icon paths, and the OpenStreetMap tile layer with its attribution and its
// referrer policy.
//
// Extracted from `map-picker.ts` when the day map (issue #306) needed exactly
// the same two things. Neither is a detail: the icon paths are the only ones
// that survive an esbuild bundle, and the referrer policy below is a
// deployment constraint. Two copies of them would drift.
//
// Importing this module is what installs the icon defaults — same behaviour as
// before, one import away.

import * as L from 'leaflet';

// Leaflet's default marker icon references relative image paths that don't
// survive an esbuild bundle. Its images are copied to /leaflet-images by the
// "assets" entry in angular.json (same mechanism as the public/ folder), so
// point the default icon at that served path instead of importing the PNGs
// (esbuild has no loader configured for image imports here).
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'leaflet-images/marker-icon-2x.png',
  iconUrl: 'leaflet-images/marker-icon.png',
  shadowUrl: 'leaflet-images/marker-shadow.png'
});

const TUILES_OSM = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

/**
 * Adds the OpenStreetMap tiles to a map, with the attribution their licence
 * requires.
 *
 * The tiles stay light in dark mode on purpose: they are content, not chrome,
 * and darkening them would mean another tile provider rather than a variable
 * (see `docs/architecture.md`).
 */
export function ajouterTuilesOsm(map: L.Map): L.TileLayer {
  return L.tileLayer(TUILES_OSM, {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19,
    // The server sends Referrer-Policy: no-referrer on every response, to
    // keep the espace animateur token — which travels in the URL — out of
    // outbound requests. But openstreetmap.org blocks tile traffic that
    // arrives with no Referer at all (https://osm.wiki/blocked), so the map
    // would come up blank. This per-tile policy wins over the document one
    // and still never sends the path: only the origin leaves, and only when
    // the tile request isn't a downgrade to http.
    referrerPolicy: 'strict-origin-when-cross-origin'
  }).addTo(map);
}
