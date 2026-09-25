import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { Animateur, ContrainteAdHoc, Creneau, Stand } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { TableFilter } from '../../shared/table-filter';
import {
  AreteReseau,
  buildNetwork,
  disposer,
  NoeudReseau,
  resumeReseau,
  PairType,
} from './reseau-paires';

/** Which edges the network draws: the `paires` view state of the page. */
export type FiltreTypesPaires = 'toutes' | 'affinites' | 'incompatibilites';

export function readFiltreTypes(value: string | null): FiltreTypesPaires {
  return value === 'affinites' || value === 'incompatibilites' ? value : 'toutes';
}

/** The width the layout is computed for; the SVG then scales to its container. */
const LARGEUR_DESSIN = 960;

/** One adjustment carried by an edge, with its scope already worded. */
interface AjustementListe {
  contrainte: ContrainteAdHoc;
  /** Its narrowing, empty when it applies everywhere. */
  portee: string;
  /** The same, « partout » when empty: what a listed adjustment prints. */
  porteeAffichee: string;
  /** Tells the « Modifier » of one listed adjustment from the next. */
  modifierLabel: string;
}

/**
 * Everything the template shows about one edge, worded once per network
 * rather than on every change detection: the names, the tooltip, the scope,
 * and each adjustment the edge carries.
 */
interface EdgeView {
  arete: AreteReseau;
  libelleType: string;
  sourceLabel: string;
  targetLabel: string;
  infobulle: string;
  portee: string;
  ajustements: AjustementListe[];
}

/** One pair of the selected person, as listed under the drawing. */
interface ListedPair {
  edge: EdgeView;
  autre: NoeudReseau;
}

/**
 * The « Réseau » reading of the manual adjustments (the page's `?vue=reseau`):
 * the people tied by an AFFINITE or an INCOMPATIBILITE, their affinity
 * clusters framed, the people tied by incompatibilities only apart. Drawn by
 * hand in SVG over the pure `reseau-paires.ts` — there is no charting
 * dependency, and none is needed for circles and lines.
 *
 * <p>The drawing is an image (`role="img"`, its summary as a name): the
 * keyboard reaches the same content through the text alternative under it,
 * where every person is a button that selects them and every adjustment of
 * the selection has its own « Modifier ». The reason of an adjustment,
 * potentially sensitive, lives in an edge's tooltip only, never in a
 * permanent label.</p>
 *
 * <p>Everything the template prints is worded in a `computed`, indexed by
 * edge key or cluster number: no method runs on a change detection pass.</p>
 */
@Component({
  selector: 'app-reseau-paires-vue',
  imports: [MatButtonModule, MatButtonToggleModule, MatIconModule, RouterLink, TableFilter],
  templateUrl: './reseau-paires-vue.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReseauPairesView {
  readonly contraintes = input<readonly ContrainteAdHoc[]>([]);
  readonly animateurs = input<readonly Animateur[]>([]);
  readonly creneaux = input<readonly Creneau[]>([]);
  readonly stands = input<readonly Stand[]>([]);
  readonly editingLocked = input(false);
  /** The page's view state, two-way: a name to find, and which edges to draw. */
  readonly filtre = model('');
  readonly types = model<FiltreTypesPaires>('toutes');
  /** An edge carrying one adjustment was clicked, or a « Modifier »: the page opens that adjustment. */
  readonly editer = output<ContrainteAdHoc>();
  /** The empty state's button: the page opens the creation form. */
  readonly create = output<void>();

  /** The person whose pairs are highlighted and listed; null when nobody is selected. */
  protected readonly selection = signal<string | null>(null);
  /** The edge carrying several adjustments whose list is open; null when none is. */
  protected readonly areteSelectionnee = signal<string | null>(null);

  protected readonly reseau = computed(() => buildNetwork(this.contraintes(), this.animateurs()));
  protected readonly disposition = computed(() => disposer(this.reseau(), LARGEUR_DESSIN));
  protected readonly resume = computed(() => resumeReseau(this.reseau()));
  private readonly nodesById = computed(
    () => new Map(this.reseau().noeuds.map((noeud) => [noeud.id, noeud])),
  );

  /** Each edge's wording, by edge key. */
  private readonly edgeViews = computed(() => {
    const views = new Map<string, EdgeView>();
    const partout = $localize`:@@adHoc.reseau.partout:partout`;
    for (const arete of this.reseau().aretes) {
      const libelleType = this.typeLabel(arete.type);
      const ajustements = arete.contraintes.map((contrainte) => {
        const portee = this.porteeContrainte(contrainte);
        const scope = portee || partout;
        // The pair is already on screen: the type and the scope are what
        // tell two adjustments of the same edge apart — not an id drawn per
        // edition.
        return {
          contrainte,
          portee,
          porteeAffichee: scope,
          modifierLabel: $localize`:@@adHoc.reseau.modifier.label:Modifier l'ajustement ${libelleType}:type: (${scope}:portee:)`,
        };
      });
      const sourceLabel = this.label(arete.source);
      const targetLabel = this.label(arete.target);
      views.set(arete.key, {
        arete,
        libelleType,
        sourceLabel,
        targetLabel,
        infobulle: this.infobulle(arete, libelleType, sourceLabel, targetLabel, ajustements),
        portee:
          ajustements
            .map((ajustement) => ajustement.portee)
            .filter(Boolean)
            .join(' ; ') || partout,
        ajustements,
      });
    }
    return views;
  });

  /** The frame titles, by cluster number (null: the people tied by incompatibilities only). */
  private readonly titresCadres = computed(
    () =>
      new Map<number | null, string>(
        this.disposition().cadres.map((cadre) => [
          cadre.grappe,
          cadre.grappe === null
            ? $localize`:@@adHoc.reseau.isoles.cadre:Liés seulement par des incompatibilités — ${cadre.taille}:taille: personne(s)`
            : this.titreGrappe(cadre.grappe, cadre.taille),
        ]),
      ),
  );

  protected readonly cadresDessines = computed(() =>
    this.disposition().cadres.map((cadre) => ({
      cadre,
      titre: this.titresCadres().get(cadre.grappe) ?? '',
    })),
  );

  /** The edges the type filter keeps, with their geometry, their wording and whether they are lit. */
  protected readonly tracesVisibles = computed(() => {
    const types = this.types();
    const selection = this.selection();
    const areteSelectionnee = this.openEdge()?.arete.key ?? null;
    return this.disposition().aretes.flatMap((trace) => {
      const edge = this.edgeViews().get(trace.key);
      if (!edge || !this.typeVisible(edge.arete.type, types)) {
        return [];
      }
      const arete = edge.arete;
      const actif =
        areteSelectionnee !== null
          ? arete.key === areteSelectionnee
          : selection === null || arete.source === selection || arete.target === selection;
      return [{ trace, arete, infobulle: edge.infobulle, actif }];
    });
  });

  /** The ids the name filter finds; null when the filter is empty (everyone is shown plainly). */
  protected readonly trouves = computed(() => {
    const filtre = this.filtre();
    if (!filtre.trim()) {
      return null;
    }
    return new Set(
      this.reseau()
        .noeuds.filter((noeud) => correspondAuFiltre(filtre, [noeud.label, noeud.id]))
        .map((noeud) => noeud.id),
    );
  });

  protected readonly noeudsDessines = computed(() => {
    const trouves = this.trouves();
    return this.reseau().noeuds.flatMap((noeud) => {
      const position = this.disposition().noeuds.get(noeud.id);
      const attenue = trouves !== null && !trouves.has(noeud.id);
      return position ? [{ noeud, position, attenue }] : [];
    });
  });

  /** The text alternative, narrowed by the name filter: the clusters and the isolated. */
  protected readonly grappesListees = computed(() =>
    this.reseau()
      .grappes.map((grappe) => ({
        grappe,
        titre: this.titreGrappe(grappe.numero, grappe.membres.length),
        membres: this.filterNodes(grappe.membres),
        internes: this.reseau().incompatibilitesInternes.flatMap((arete) => {
          const edge = this.edgeViews().get(arete.key);
          return edge && this.nodesById().get(arete.source)?.grappe === grappe.numero ? [edge] : [];
        }),
      }))
      .filter((entree) => entree.membres.length > 0),
  );
  protected readonly isolesListes = computed(() => this.filterNodes(this.reseau().isoles));
  protected readonly pairesDoublees = computed(() =>
    this.reseau().aretes.flatMap((arete) => {
      const edge = this.edgeViews().get(arete.key);
      return edge && arete.doublee && arete.type === 'AFFINITE' ? [edge] : [];
    }),
  );

  protected readonly noeudSelectionne = computed(() => {
    const id = this.selection();
    return id ? (this.nodesById().get(id) ?? null) : null;
  });
  protected readonly pairesSelection = computed<ListedPair[]>(() => {
    const id = this.selection();
    if (!id) {
      return [];
    }
    return this.reseau().aretes.flatMap((arete) => {
      if (arete.source !== id && arete.target !== id) {
        return [];
      }
      const edge = this.edgeViews().get(arete.key);
      const autre = this.nodesById().get(arete.source === id ? arete.target : arete.source);
      return edge && autre ? [{ edge, autre }] : [];
    });
  });

  /** The edge whose adjustments are listed; null once the data no longer holds it. */
  protected readonly openEdge = computed(() => {
    const key = this.areteSelectionnee();
    return key === null ? null : (this.edgeViews().get(key) ?? null);
  });

  protected readonly typesLabel = $localize`:@@adHoc.reseau.types:Paires dessinées`;

  protected select(id: string): void {
    this.selection.set(this.selection() === id ? null : id);
    this.areteSelectionnee.set(null);
  }

  /**
   * A click on an edge: its adjustment when it carries one, else the list of
   * all of them under the drawing — opening the first one alone would leave
   * the others out of reach from the network. While a solve locks editing,
   * the list opens even for one, so the click shows its disabled « Modifier »
   * and why, instead of doing nothing.
   */
  protected cliquerArete(arete: AreteReseau): void {
    if (arete.contraintes.length > 1 || this.editingLocked()) {
      this.areteSelectionnee.set(this.areteSelectionnee() === arete.key ? null : arete.key);
      return;
    }
    const [contrainte] = arete.contraintes;
    if (contrainte) {
      this.open(contrainte);
    }
  }

  protected open(contrainte: ContrainteAdHoc): void {
    if (!this.editingLocked()) {
      this.editer.emit(contrainte);
    }
  }

  private label(id: string): string {
    return this.nodesById().get(id)?.label ?? id;
  }

  private typeLabel(type: PairType): string {
    return type === 'AFFINITE'
      ? $localize`:@@adHoc.reseau.affinite:Affinité`
      : $localize`:@@adHoc.reseau.incompatibilite:Incompatibilité`;
  }

  /** The edge's tooltip: type, the two names, the scope — and the reason, which is shown nowhere else. */
  private infobulle(
    arete: AreteReseau,
    libelleType: string,
    sourceLabel: string,
    targetLabel: string,
    ajustements: readonly AjustementListe[],
  ): string {
    const lignes = [
      `${libelleType} : ${sourceLabel} — ${targetLabel}`,
      ...ajustements.map(({ contrainte, portee }) =>
        [contrainte.id, portee, contrainte.raison].filter(Boolean).join(' · '),
      ),
    ];
    if (arete.interne) {
      lignes.push(
        $localize`:@@adHoc.reseau.interne.infobulle:Incompatibilité interne à une grappe d'affinités`,
      );
    }
    if (arete.doublee) {
      lignes.push(
        $localize`:@@adHoc.reseau.doublee.infobulle:Paire déclarée à la fois en affinité et incompatible`,
      );
    }
    if (ajustements.length > 1) {
      lignes.push(
        $localize`:@@adHoc.reseau.plusieurs.infobulle:Cliquer pour lister ses ${ajustements.length}:nombre: ajustements`,
      );
    }
    return lignes.join('\n');
  }

  private titreGrappe(numero: number, taille: number): string {
    return $localize`:@@adHoc.reseau.grappe:Grappe ${numero}:numero: — ${taille}:taille: personne(s)`;
  }

  private porteeContrainte(contrainte: ContrainteAdHoc): string {
    const morceaux: string[] = [];
    if (contrainte.creneau) {
      const creneau = this.creneaux().find((candidat) => candidat.id === contrainte.creneau?.id);
      morceaux.push(
        creneau
          ? $localize`:@@adHoc.reseau.portee.creneau:créneau J${creneau.jour}:jour: ${creneau.heureDebut}:debut:–${creneau.heureFin}:fin:`
          : $localize`:@@adHoc.scope.creneauSupprime:créneau supprimé`,
      );
    }
    if (contrainte.stand) {
      const stand = this.stands().find((candidat) => candidat.id === contrainte.stand?.id);
      const nom = stand?.nom || contrainte.stand.id;
      morceaux.push($localize`:@@adHoc.reseau.portee.stand:stand ${nom}:nom:`);
    }
    return morceaux.join(' · ');
  }

  private typeVisible(type: PairType, filtre: FiltreTypesPaires): boolean {
    return (
      filtre === 'toutes' ||
      (filtre === 'affinites' && type === 'AFFINITE') ||
      (filtre === 'incompatibilites' && type === 'INCOMPATIBILITE')
    );
  }

  private filterNodes(ids: readonly string[]): NoeudReseau[] {
    const trouves = this.trouves();
    return ids.flatMap((id) => {
      const noeud = this.nodesById().get(id);
      return noeud && (trouves === null || trouves.has(id)) ? [noeud] : [];
    });
  }
}
