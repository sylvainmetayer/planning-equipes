// Click/drag location picker built on Leaflet + OpenStreetMap tiles: the
// open-source equivalent of a Google Maps place picker. Used by the
// Emplacement form to set latitude/longitude visually, in sync with the
// plain numeric inputs next to it.

import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild
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
  styleUrl: './map-picker.css'
})
export class MapPicker implements AfterViewInit, OnChanges, OnDestroy {
  @Input() latitude: number | null = null;
  @Input() longitude: number | null = null;
  @Input() disabled = false;
  @Output() readonly positionChange = new EventEmitter<MapPosition>();

  @ViewChild('mapHost', { static: true }) private readonly mapHost!: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private marker?: L.Marker;
  private ready = false;

  ngAfterViewInit(): void {
    const hasPosition = this.hasPosition();
    this.map = L.map(this.mapHost.nativeElement).setView(
      hasPosition ? this.currentLatLng() : DEFAULT_CENTER,
      hasPosition ? POINT_ZOOM : DEFAULT_ZOOM
    );
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19
    }).addTo(this.map);
    if (hasPosition) {
      this.placeMarker(this.latitude!, this.longitude!);
    }
    this.map.on('click', (event: L.LeafletMouseEvent) => {
      if (this.disabled) {
        return;
      }
      this.placeMarker(event.latlng.lat, event.latlng.lng);
      this.positionChange.emit({ latitude: event.latlng.lat, longitude: event.latlng.lng });
    });
    // Marks init as done only after this callback runs; ngOnChanges fires
    // before ngAfterViewInit on first change, when there's no map yet to sync.
    this.ready = true;
  }

  // Keeps the marker in sync when latitude/longitude are set from outside
  // (the numeric inputs next to this component), without re-emitting
  // positionChange — that would create a feedback loop with the inputs.
  ngOnChanges(changes: SimpleChanges): void {
    if (!this.ready || !this.map) {
      return;
    }
    if ((changes['latitude'] || changes['longitude']) && this.hasPosition()) {
      this.placeMarker(this.latitude!, this.longitude!);
      this.map.panTo(this.currentLatLng());
    }
    if (changes['disabled'] && this.marker) {
      if (this.disabled) {
        this.marker.dragging?.disable();
      } else {
        this.marker.dragging?.enable();
      }
    }
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  private hasPosition(): boolean {
    return this.latitude != null && this.longitude != null;
  }

  private currentLatLng(): L.LatLngTuple {
    return [this.latitude!, this.longitude!];
  }

  private placeMarker(lat: number, lng: number): void {
    if (this.marker) {
      this.marker.setLatLng([lat, lng]);
      return;
    }
    this.marker = L.marker([lat, lng], { draggable: !this.disabled }).addTo(this.map!);
    this.marker.on('dragend', () => {
      const position = this.marker!.getLatLng();
      this.positionChange.emit({ latitude: position.lat, longitude: position.lng });
    });
  }
}
