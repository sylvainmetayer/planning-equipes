// The map of every location of the edition, on the « Lieux » tab of the
// Stands page: one marker per located place, its stands in the tooltip, and a
// marker dragged elsewhere is the place moved — the position saved by the
// page, without a dialog. Reached only inside the tab's `@defer`: Leaflet is a
// chunk of its own and must stay out of the Stands table's.

import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  ViewEncapsulation,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
// Also installs the default marker icon paths, by importing it.
import { ajouterTuilesOsm } from '../../shared/leaflet-base';

/** France, when no place is located yet. */
const DEFAULT_CENTER: L.LatLngTuple = [46.6, 2.5];
const DEFAULT_ZOOM = 5;
const MAX_FRAMING_ZOOM = 17;

/** One place on the map: where it is, and the stands standing on it. */
export interface LieuMarker {
  id: string;
  nom: string;
  latitude: number;
  longitude: number;
  stands: string[];
}

/** A marker dropped somewhere else: the place and its new position. */
export interface LieuMove {
  id: string;
  latitude: number;
  longitude: number;
}

/** The tooltip of a place: its name, then its stands, one line each — plain text, never HTML. */
export function lieuTooltip(lieu: LieuMarker, none: string): HTMLElement {
  const element = document.createElement('div');
  const titre = document.createElement('strong');
  titre.textContent = lieu.nom;
  element.append(titre);
  const lignes = lieu.stands.length > 0 ? lieu.stands : [none];
  for (const ligne of lignes) {
    const item = document.createElement('div');
    item.textContent = ligne;
    element.append(item);
  }
  return element;
}

@Component({
  selector: 'app-lieux-map',
  template: `
    <div
      #mapHost
      class="lieux-map-host"
      role="application"
      i18n-aria-label="@@lieux.carte.aria"
      aria-label="Carte des lieux de l'édition"
    ></div>
    <p class="lieux-map-hint" i18n="@@lieux.carte.aide">
      Faites glisser un marqueur pour corriger la position d'un lieu : elle est enregistrée aussitôt.
    </p>
  `,
  styleUrl: './lieux-map.css',
  // Global by design (AGENTS.md): loaded with the tab that draws the map.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LieuxMap implements AfterViewInit, OnDestroy {
  readonly lieux = input.required<readonly LieuMarker[]>();
  /** A solve running or a freeze: the markers stay where they are. */
  readonly disabled = input(false);
  /**
   * Raised by the page when a move could not be saved: the markers are laid
   * again where the places are, the dropped one back at its stored position.
   */
  readonly revision = input(0);
  readonly moved = output<LieuMove>();

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');
  private map?: L.Map;
  private readonly markers = new Map<string, L.Marker>();
  /** A signal, so the effects below run again once the map exists. */
  private readonly ready = signal(false);
  /** Framed once, on the first places: a later move must not jump the view. */
  private framed = false;

  constructor() {
    effect(() => {
      const lieux = this.lieux();
      const disabled = this.disabled();
      this.revision();
      if (!this.ready() || !this.map) {
        return;
      }
      this.draw(lieux, disabled);
    });
  }

  ngAfterViewInit(): void {
    this.map = L.map(this.mapHost().nativeElement).setView(DEFAULT_CENTER, DEFAULT_ZOOM);
    ajouterTuilesOsm(this.map);
    this.ready.set(true);
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  private draw(lieux: readonly LieuMarker[], disabled: boolean): void {
    const map = this.map!;
    const none = $localize`:@@lieux.carte.aucunStand:Aucun stand`;
    const kept = new Set(lieux.map((lieu) => lieu.id));
    for (const [id, marker] of this.markers) {
      if (!kept.has(id)) {
        marker.remove();
        this.markers.delete(id);
      }
    }
    for (const lieu of lieux) {
      let marker = this.markers.get(lieu.id);
      if (marker) {
        marker.setLatLng([lieu.latitude, lieu.longitude]);
      } else {
        marker = L.marker([lieu.latitude, lieu.longitude], {
          draggable: !disabled,
          title: lieu.nom,
          alt: lieu.nom,
        }).addTo(map);
        const id = lieu.id;
        const created = marker;
        // Leaflet calls back outside Angular's knowledge; the app is zoneless,
        // so the move travels through an output() to reach the page's signals.
        created.on('dragend', () => {
          const position = created.getLatLng();
          this.moved.emit({ id, latitude: position.lat, longitude: position.lng });
        });
        this.markers.set(lieu.id, marker);
      }
      marker.unbindTooltip();
      marker.bindTooltip(lieuTooltip(lieu, none), { direction: 'top' });
      if (disabled) {
        marker.dragging?.disable();
      } else {
        marker.dragging?.enable();
      }
    }
    if (!this.framed && lieux.length > 0) {
      this.framed = true;
      map.fitBounds(
        L.latLngBounds(lieux.map((lieu) => [lieu.latitude, lieu.longitude] as L.LatLngTuple)),
        { padding: [32, 32], maxZoom: MAX_FRAMING_ZOOM },
      );
    }
  }
}
