// The Leaflet half of the day replay (issue #306): one marker per located
// emplacement, coloured by what the persisted plan says is happening there at
// the instant the cursor points at.
//
// Kept in its own component so `carte-jour-page` holds no Leaflet at all, and
// so the whole map — tiles included — stays inside the lazy chunk of the
// route. Markers are grouped by emplacement rather than drawn per stand: two
// stands on the same place would otherwise be two markers on the same pixel,
// one of them unreachable.

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
import { ajouterTuilesOsm } from '../../shared/leaflet-base';
import { MarqueurJour, comptePastille } from './carte-jour';

/** France, when the day holds no located emplacement at all. */
const CENTRE_DEFAUT: L.LatLngTuple = [46.6, 2.5];
const ZOOM_DEFAUT = 5;
/** Cap of the automatic framing: one emplacement alone must not zoom to the rooftops. */
const ZOOM_MAX_CADRAGE = 17;

@Component({
  selector: 'app-carte-jour-map',
  template: `<div
    #hote
    class="carte-jour-hote"
    role="application"
    [attr.aria-label]="ariaLabel()"
  ></div>`,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CarteJourMap implements AfterViewInit, OnDestroy {
  readonly marqueurs = input.required<MarqueurJour[]>();
  /** Emplacement the list currently highlights, drawn with a ring. */
  readonly selection = input<string | null>(null);
  /**
   * Changes exactly when the map must be re-framed — the chosen day, here.
   * Moving the time cursor must not move the viewport under the reader.
   */
  readonly cadrage = input<string>('');
  readonly ariaLabel = input<string>('');
  readonly marqueurChoisi = output<string>();

  private readonly hote = viewChild.required<ElementRef<HTMLDivElement>>('hote');

  private map?: L.Map;
  /** Handle of the pending re-measure, so it never outlives the map it measures. */
  private mesure?: number;
  private readonly couche = new Map<string, L.Marker>();
  /**
   * Plain field, not a signal: it is written from inside the sync effect, and a
   * signal written there is how a change-detection loop starts. Nothing reads
   * it to render — it only remembers the last framing the map was given.
   */
  private dernierCadrage: string | null = null;
  /** Signal, so the sync effect below runs again once the map exists. */
  private readonly pret = signal(false);

  constructor() {
    effect(() => {
      const marqueurs = this.marqueurs();
      const selection = this.selection();
      const cadrage = this.cadrage();
      if (!this.pret() || !this.map) {
        return;
      }
      this.synchroniser(marqueurs, selection);
      if (cadrage !== this.dernierCadrage) {
        this.dernierCadrage = cadrage;
        this.cadrer(marqueurs);
      }
    });
  }

  ngAfterViewInit(): void {
    this.map = L.map(this.hote().nativeElement, { center: CENTRE_DEFAUT, zoom: ZOOM_DEFAUT });
    ajouterTuilesOsm(this.map);
    // The card the map lives in is laid out after this hook on the first pass;
    // without this Leaflet keeps the size it measured on an empty container and
    // renders a strip of tiles. Pure DOM work, no change detection involved.
    this.mesure = requestAnimationFrame(() => {
      this.mesure = undefined;
      this.map?.invalidateSize();
    });
    this.pret.set(true);
  }

  ngOnDestroy(): void {
    // Both, and neither is redundant: `Map.remove()` drops `_mapPane` while
    // leaving `_loaded` true, so an `invalidateSize()` landing after it walks
    // into a TypeError. Cancelling the frame closes the window, dropping the
    // handle makes the `?.` above actually guard something.
    if (this.mesure !== undefined) {
      cancelAnimationFrame(this.mesure);
      this.mesure = undefined;
    }
    this.map?.remove();
    this.map = undefined;
    this.couche.clear();
  }

  /** Creates, updates and removes markers in place: a full redraw would flicker on every cursor step. */
  private synchroniser(marqueurs: MarqueurJour[], selection: string | null): void {
    const vus = new Set<string>();
    marqueurs.forEach((marqueur) => {
      vus.add(marqueur.emplacementId);
      const existant = this.couche.get(marqueur.emplacementId);
      const icone = this.icone(marqueur, selection === marqueur.emplacementId);
      if (existant) {
        existant.setIcon(icone);
        existant.setLatLng([marqueur.latitude, marqueur.longitude]);
        this.legender(existant, marqueur);
        return;
      }
      const cree = L.marker([marqueur.latitude, marqueur.longitude], {
        icon: icone,
        // Leaflet gives a keyboard-reachable marker a tabindex of its own; the
        // real accessible equivalent of this map is the list next to it.
        keyboard: true
        // No `title` here: see `legender` — Leaflet would keep it alongside its
        // own tooltip.
      });
      cree.on('click', () => this.marqueurChoisi.emit(marqueur.emplacementId));
      // Leaflet callbacks run outside Angular's knowledge and the app is
      // zoneless: the value has to travel through an output() for the parent's
      // signal write — and the resulting render — to happen at all.
      cree.on('keypress', (event: L.LeafletKeyboardEvent) => {
        if (event.originalEvent.key === 'Enter' || event.originalEvent.key === ' ') {
          this.marqueurChoisi.emit(marqueur.emplacementId);
        }
      });
      cree.addTo(this.map!);
      // After `addTo`, not before: the icon element only exists once the marker
      // is on the map, and `legender` writes an attribute on it.
      this.legender(cree, marqueur);
      this.couche.set(marqueur.emplacementId, cree);
    });
    this.couche.forEach((marqueur, id) => {
      if (!vus.has(id)) {
        marqueur.remove();
        this.couche.delete(id);
      }
    });
  }

  /**
   * The tooltip, built as a text node rather than as HTML: an emplacement name
   * is user input, and Leaflet's string form goes through `innerHTML`.
   */
  private legender(marqueur: L.Marker, donnees: MarqueurJour): void {
    const contenu = document.createElement('span');
    contenu.textContent = donnees.resume;
    if (marqueur.getTooltip()) {
      marqueur.setTooltipContent(contenu);
    } else {
      marqueur.bindTooltip(contenu, { direction: 'top' });
    }
    // The Leaflet tooltip only, never a native `title` as well: Leaflet keeps
    // both, so the browser's own bubble would stack on top of the Leaflet one
    // with the same text, and a screen reader would read the summary twice —
    // once as the accessible name taken from `title`, once through the
    // `aria-describedby` Leaflet points at the open tooltip. The name is the
    // place, the summary is its description.
    marqueur.getElement()?.setAttribute('aria-label', donnees.nom);
  }

  /**
   * A `divIcon` rather than a coloured image: the colours then live in the
   * stylesheet, which is what lets them be `--mat-sys-*` tokens and follow the
   * dark theme. Only a number is injected as markup — never a name.
   */
  private icone(marqueur: MarqueurJour, selectionne: boolean): L.DivIcon {
    const compte = comptePastille(marqueur);
    return L.divIcon({
      className: '',
      html: `<span class="carte-jour-pastille etat-${marqueur.etat}${selectionne ? ' selection' : ''}">${compte}</span>`,
      iconSize: [28, 28],
      iconAnchor: [14, 14],
      tooltipAnchor: [0, -14]
    });
  }

  /** Frames every marker of the day, or falls back to the whole country when there is none. */
  private cadrer(marqueurs: MarqueurJour[]): void {
    if (!this.map) {
      return;
    }
    if (marqueurs.length === 0) {
      this.map.setView(CENTRE_DEFAUT, ZOOM_DEFAUT);
      return;
    }
    const bornes = L.latLngBounds(marqueurs.map((marqueur) => [marqueur.latitude, marqueur.longitude]));
    this.map.fitBounds(bornes, { padding: [32, 32], maxZoom: ZOOM_MAX_CADRAGE });
  }
}
