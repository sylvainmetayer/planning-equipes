import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Animateur, Creneau, Emplacement, PlanningEvenement, Stand } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { MapPicker } from '../../shared/map-picker';
import { StatusMessage } from '../../shared/status-message';

/** One selectable item of a column, with the size of what it leads to. */
export interface NoeudGraphe {
  id: string;
  libelle: string;
  /** Secondary line: what distinguishes two same-looking nodes. */
  detail?: string;
  /** How many items the next column would hold — the cost of drilling in. */
  descendants: number;
}

/** Which column a selection belongs to; also the drill-down order. */
export type NiveauGraphe = 'emplacement' | 'stand' | 'creneau' | 'animateur';

/** Stands with no emplacement still have to be reachable, so they get a bucket of their own. */
const NO_EMPLACEMENT = '__sans_emplacement__';

/**
 * Descending navigation through the data: emplacement → stands → créneaux →
 * animateurs, one column per level, sliding left to right.
 *
 * <h2>Why columns and not a force-directed graph</h2>
 * At 23 emplacements, 65 stands and 153 animateurs, a node-and-edge cloud is
 * a picture of a hairball: the reader cannot follow a single edge, which is
 * the one thing they came to do. Columns keep the *path* visible — the
 * breadcrumb says exactly where you are — while the panel on the right draws
 * only the selected node's immediate neighbourhood, which is small enough to
 * be read and is where the "graph" intuition actually pays off.
 *
 * <h2>Where the links come from</h2>
 * Emplacement → stand is reference data, always available. Stand → créneau →
 * animateur is read from the **persisted planning**: those links only exist
 * once a solve has assigned somebody. Without a plan the first two columns
 * still work and the last two say so, rather than inventing a theoretical
 * opening grid that nobody would be able to check against anything.
 */
@Component({
  selector: 'app-graphe-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MapPicker,
    StatusMessage,
  ],
  templateUrl: './graphe-page.html',
  styleUrl: './graphe-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphePage {
  private readonly reference = inject(ReferenceDataStore);
  private readonly planningState = inject(PlanningStateService);

  protected readonly chargement = signal(true);
  protected readonly planning = signal<PlanningEvenement | null>(null);

  protected readonly emplacementSelectionne = signal<string | null>(null);
  protected readonly standSelectionne = signal<string | null>(null);
  protected readonly creneauSelectionne = signal<string | null>(null);
  protected readonly animateurSelectionne = signal<string | null>(null);

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    try {
      await this.reference.reload();
      this.planning.set(await this.planningState.loadForDisplay());
    } catch {
      // A missing plan is an ordinary state, not an error: the first two
      // columns are built from reference data and stay usable.
    } finally {
      this.chargement.set(false);
    }
  }

  /* ------------------------------- Index ---------------------------------- */

  /** Postes grouped once, so every column below is a lookup rather than a scan. */
  private readonly postesParStand = computed(() => {
    const index = new Map<string, { creneau: Creneau; animateur: Animateur | null }[]>();
    for (const poste of this.planning()?.postes ?? []) {
      if (!poste.stand || !poste.creneau) {
        continue;
      }
      const liste = index.get(poste.stand.id) ?? [];
      liste.push({ creneau: poste.creneau, animateur: poste.animateur });
      index.set(poste.stand.id, liste);
    }
    return index;
  });

  private readonly standsParEmplacement = computed(() => {
    const index = new Map<string, Stand[]>();
    for (const stand of this.reference.stands()) {
      const key = stand.emplacement?.id ?? NO_EMPLACEMENT;
      index.set(key, [...(index.get(key) ?? []), stand]);
    }
    return index;
  });

  /* ------------------------------ Colonnes -------------------------------- */

  protected readonly emplacements = computed<NoeudGraphe[]>(() => {
    const parEmplacement = this.standsParEmplacement();
    const noeuds = this.reference.emplacements().map((emplacement) => ({
      id: emplacement.id,
      libelle: emplacement.nom,
      detail: this.coordonnees(emplacement),
      descendants: parEmplacement.get(emplacement.id)?.length ?? 0,
    }));
    const orphelins = parEmplacement.get(NO_EMPLACEMENT)?.length ?? 0;
    if (orphelins > 0) {
      noeuds.push({
        id: NO_EMPLACEMENT,
        libelle: $localize`:@@graphe.sansEmplacement:Sans emplacement`,
        detail: $localize`:@@graphe.sansEmplacement.detail:Stands dont la fiche ne porte aucun lieu`,
        descendants: orphelins,
      });
    }
    return noeuds.sort((a, b) => a.libelle.localeCompare(b.libelle));
  });

  protected readonly stands = computed<NoeudGraphe[]>(() => {
    const emplacement = this.emplacementSelectionne();
    if (!emplacement) {
      return [];
    }
    const postes = this.postesParStand();
    return (this.standsParEmplacement().get(emplacement) ?? [])
      .map((stand) => ({
        id: stand.id,
        libelle: stand.nom,
        detail: $localize`:@@graphe.stand.detail:effectif ${stand.effectifMin}:min:–${stand.effectifMax}:max:`,
        descendants: new Set((postes.get(stand.id) ?? []).map((poste) => poste.creneau.id)).size,
      }))
      .sort((a, b) => a.libelle.localeCompare(b.libelle));
  });

  protected readonly creneaux = computed<NoeudGraphe[]>(() => {
    const stand = this.standSelectionne();
    if (!stand) {
      return [];
    }
    const parCreneau = new Map<number, { creneau: Creneau; animateurs: Set<string> }>();
    for (const poste of this.postesParStand().get(stand) ?? []) {
      const entree = parCreneau.get(poste.creneau.id) ?? {
        creneau: poste.creneau,
        animateurs: new Set<string>(),
      };
      if (poste.animateur) {
        entree.animateurs.add(poste.animateur.id);
      }
      parCreneau.set(poste.creneau.id, entree);
    }
    return [...parCreneau.values()]
      .map(({ creneau, animateurs }) => ({
        id: String(creneau.id),
        libelle: `${creneau.date} ${creneau.heureDebut}–${creneau.heureFin}`,
        detail: $localize`:@@graphe.creneau.detail:jour ${creneau.jour}:jour:`,
        descendants: animateurs.size,
      }))
      .sort((a, b) => a.libelle.localeCompare(b.libelle));
  });

  protected readonly animateurs = computed<NoeudGraphe[]>(() => {
    const stand = this.standSelectionne();
    const creneau = this.creneauSelectionne();
    if (!stand || !creneau) {
      return [];
    }
    const vus = new Map<string, Animateur>();
    for (const poste of this.postesParStand().get(stand) ?? []) {
      if (String(poste.creneau.id) === creneau && poste.animateur) {
        vus.set(poste.animateur.id, poste.animateur);
      }
    }
    return [...vus.values()]
      .map((animateur) => ({
        id: animateur.id,
        libelle: `${animateur.prenom} ${animateur.nom}`,
        detail: animateur.manager ? $localize`:@@graphe.animateur.manager:manager` : undefined,
        descendants: 0,
      }))
      .sort((a, b) => a.libelle.localeCompare(b.libelle));
  });

  /* ----------------------------- Sélection -------------------------------- */

  /**
   * Selecting a node clears everything to its right. Keeping a stale
   * downstream selection visible while its parent changed is the classic way
   * a drill-down starts lying to the reader.
   */
  protected selectionner(niveau: NiveauGraphe, id: string): void {
    if (niveau === 'emplacement') {
      this.emplacementSelectionne.set(this.emplacementSelectionne() === id ? null : id);
      this.standSelectionne.set(null);
      this.creneauSelectionne.set(null);
      this.animateurSelectionne.set(null);
    } else if (niveau === 'stand') {
      this.standSelectionne.set(this.standSelectionne() === id ? null : id);
      this.creneauSelectionne.set(null);
      this.animateurSelectionne.set(null);
    } else if (niveau === 'creneau') {
      this.creneauSelectionne.set(this.creneauSelectionne() === id ? null : id);
      this.animateurSelectionne.set(null);
    } else {
      this.animateurSelectionne.set(this.animateurSelectionne() === id ? null : id);
    }
  }

  protected reinitialiser(): void {
    this.emplacementSelectionne.set(null);
    this.standSelectionne.set(null);
    this.creneauSelectionne.set(null);
    this.animateurSelectionne.set(null);
  }

  /** Trail of what is currently selected, deepest last — the reader's "where am I". */
  protected readonly filAriane = computed(() => {
    const etapes: { niveau: NiveauGraphe; libelle: string }[] = [];
    const add = (niveau: NiveauGraphe, noeuds: NoeudGraphe[], id: string | null) => {
      const noeud = id ? noeuds.find((candidat) => candidat.id === id) : undefined;
      if (noeud) {
        etapes.push({ niveau, libelle: noeud.libelle });
      }
    };
    add('emplacement', this.emplacements(), this.emplacementSelectionne());
    add('stand', this.stands(), this.standSelectionne());
    add('creneau', this.creneaux(), this.creneauSelectionne());
    add('animateur', this.animateurs(), this.animateurSelectionne());
    return etapes;
  });

  /* ------------------------------- Détail --------------------------------- */

  /** The deepest selected node — what the right-hand panel describes. */
  protected readonly noeudCourant = computed<{ niveau: NiveauGraphe; noeud: NoeudGraphe } | null>(
    () => {
      const candidats: [NiveauGraphe, NoeudGraphe[], string | null][] = [
        ['animateur', this.animateurs(), this.animateurSelectionne()],
        ['creneau', this.creneaux(), this.creneauSelectionne()],
        ['stand', this.stands(), this.standSelectionne()],
        ['emplacement', this.emplacements(), this.emplacementSelectionne()],
      ];
      for (const [niveau, noeuds, id] of candidats) {
        const noeud = id ? noeuds.find((candidat) => candidat.id === id) : undefined;
        if (noeud) {
          return { niveau, noeud };
        }
      }
      return null;
    },
  );

  /** The selected emplacement's own record, for the map — null for the "sans emplacement" bucket. */
  protected readonly emplacementCourant = computed<Emplacement | null>(() => {
    const id = this.emplacementSelectionne();
    return id
      ? (this.reference.emplacements().find((candidat) => candidat.id === id) ?? null)
      : null;
  });

  /**
   * The selected node's immediate neighbourhood, laid out on a circle: its
   * parent (if any) and up to {@link VOISINS_MAX} children. This is the part
   * that looks like a graph, kept to one hop precisely so it stays readable —
   * two hops would already put 150 animateurs on screen.
   */
  protected readonly voisinage = computed(() => {
    const courant = this.noeudCourant();
    if (!courant) {
      return null;
    }
    const enfants = this.enfantsDe(courant.niveau).slice(0, GraphePage.VOISINS_MAX);
    const parent = this.parentDe(courant.niveau);
    const total = enfants.length + (parent ? 1 : 0);
    const rayon = 74;
    const points = [
      ...(parent ? [{ libelle: parent, parent: true }] : []),
      ...enfants.map((enfant) => ({
        libelle: enfant.libelle,
        parent: false,
      })),
    ].map((point, index) => {
      const angle = (2 * Math.PI * index) / Math.max(total, 1) - Math.PI / 2;
      return {
        ...point,
        x: 100 + rayon * Math.cos(angle),
        y: 100 + rayon * Math.sin(angle),
      };
    });
    return {
      centre: courant.noeud.libelle,
      points,
      tronques: Math.max(0, courant.noeud.descendants - enfants.length),
    };
  });

  /** Above this, the ring stops being readable and starts being decoration. */
  private static readonly VOISINS_MAX = 10;

  private enfantsDe(niveau: NiveauGraphe): NoeudGraphe[] {
    if (niveau === 'emplacement') {
      return this.stands();
    }
    if (niveau === 'stand') {
      return this.creneaux();
    }
    if (niveau === 'creneau') {
      return this.animateurs();
    }
    return [];
  }

  private parentDe(niveau: NiveauGraphe): string | null {
    const libelle = (noeuds: NoeudGraphe[], id: string | null) =>
      (id ? noeuds.find((candidat) => candidat.id === id)?.libelle : undefined) ?? null;
    if (niveau === 'stand') {
      return libelle(this.emplacements(), this.emplacementSelectionne());
    }
    if (niveau === 'creneau') {
      return libelle(this.stands(), this.standSelectionne());
    }
    if (niveau === 'animateur') {
      return libelle(this.creneaux(), this.creneauSelectionne());
    }
    return null;
  }

  /** True once a plan exists: what columns 3 and 4 are read from. */
  protected readonly planDisponible = computed(() => (this.planning()?.postes?.length ?? 0) > 0);

  private coordonnees(emplacement: Emplacement): string | undefined {
    return emplacement.latitude != null && emplacement.longitude != null
      ? `${emplacement.latitude.toFixed(4)}, ${emplacement.longitude.toFixed(4)}`
      : undefined;
  }
}
