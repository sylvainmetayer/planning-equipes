// Click/drag location picker built on Leaflet + OpenStreetMap tiles: the
// open-source equivalent of a Google Maps place picker. Used by the
// Emplacement form to set latitude/longitude visually, in sync with the
// plain numeric inputs next to it.

import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';
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

const DEFAULT_CENTER: L.LatLngTuple = [46.6, 2.5]; // France, when no point is set yet.
const DEFAULT_ZOOM = 5;
const POINT_ZOOM = 13;

export interface MapPosition {
  latitude: number;
  longitude: number;
}

@Component({
  selector: 'app-map-picker',
  templateUrl: './map-picker.html',
  styleUrl: './map-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MapPicker implements AfterViewInit, OnDestroy {
  readonly latitude = input<number | null>(null);
  readonly longitude = input<number | null>(null);
  readonly disabled = input(false);
  readonly positionChange = output<MapPosition>();

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  private map?: L.Map;
  private marker?: L.Marker;
  /**
   * Signal, not a plain field: both effects below bail out on it, and a plain
   * field notifies nothing — they would never re-run once the map exists. The
   * component is correct today only because `placeMarker` re-derives
   * `draggable` from `disabled()` at creation time; that is an invariant no
   * one can see from here. As a signal, the effects are the single source of
   * truth for the marker's drag state again.
   */
  private readonly ready = signal(false);

  constructor() {
    // Keeps the marker in sync when latitude/longitude are set from outside
    // (the numeric inputs next to this component), without re-emitting
    // positionChange — that would create a feedback loop with the inputs.
    // Both inputs are read before the readiness guard so the effect keeps
    // tracking them when it runs before the map exists.
    effect(() => {
      const latitude = this.latitude();
      const longitude = this.longitude();
      if (!this.ready() || !this.map || latitude == null || longitude == null) {
        return;
      }
      this.placeMarker(latitude, longitude);
      this.map.panTo([latitude, longitude]);
    });
    effect(() => {
      const disabled = this.disabled();
      if (!this.ready() || !this.marker) {
        return;
      }
      if (disabled) {
        this.marker.dragging?.disable();
      } else {
        this.marker.dragging?.enable();
      }
    });
  }

  ngAfterViewInit(): void {
    const position = this.currentPosition();
    this.map = L.map(this.mapHost().nativeElement).setView(
      position ?? DEFAULT_CENTER,
      position ? POINT_ZOOM : DEFAULT_ZOOM
    );
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
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
    }).addTo(this.map);
    if (position) {
      this.placeMarker(position[0], position[1]);
    }
    this.map.on('click', (event: L.LeafletMouseEvent) => {
      if (this.disabled()) {
        return;
      }
      this.placeMarker(event.latlng.lat, event.latlng.lng);
      this.emit(event.latlng.lat, event.latlng.lng);
    });
    // Marks init as done only after this callback runs; the sync effects fire
    // before ngAfterViewInit on the first pass, when there's no map yet. Being
    // a signal, this write makes both of them run again now that it exists.
    this.ready.set(true);
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  private currentPosition(): L.LatLngTuple | null {
    const latitude = this.latitude();
    const longitude = this.longitude();
    return latitude != null && longitude != null ? [latitude, longitude] : null;
  }

  private placeMarker(lat: number, lng: number): void {
    if (this.marker) {
      this.marker.setLatLng([lat, lng]);
      return;
    }
    this.marker = L.marker([lat, lng], { draggable: !this.disabled() }).addTo(this.map!);
    this.marker.on('dragend', () => {
      const position = this.marker!.getLatLng();
      this.emit(position.lat, position.lng);
    });
  }

  // Leaflet callbacks run outside Angular's knowledge; the app is zoneless, so
  // the value has to travel through an output() for the parent's signal write
  // — and the resulting render — to happen at all.
  private emit(latitude: number, longitude: number): void {
    this.positionChange.emit({ latitude, longitude });
  }
}
