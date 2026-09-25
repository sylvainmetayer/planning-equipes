import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { nextGridCell } from '../../core/grid-navigation';
import { Emplacement } from '../../core/models';
import { JourneeCarte } from './carte-jour';
import {
  PorteeCharge,
  grilleCharge,
  grilleChargeEvenement,
  niveauDensite,
} from './charge-emplacement';

/** A day of the event and the minute its busiest span starts, as a click on the event grid asks. */
export interface PicDemande {
  jour: number;
  minutes: number | null;
}

interface CelluleAffichee {
  cle: string;
  texte: string;
  aria: string;
  niveau: number;
  courante: boolean;
  /** Day mode: the span's first minute. Event mode: the minute the peak starts at, or null. */
  minutes: number | null;
  jour: number | null;
}

interface LigneAffichee {
  cle: string;
  nom: string;
  /** Why the place is not on the map, when it is not; empty otherwise. */
  note: string;
  total: boolean;
  cellules: CelluleAffichee[];
}

interface Colonne {
  cle: string;
  libelle: string;
  courante: boolean;
}

/**
 * The load grid under the day map: one row per place, one column per span of
 * the day (or per day of the event), each cell the people present over the
 * seats planned, shaded by density. A click — or Enter — on a cell of the day
 * moves the map's cursor there; on the event grid it opens that day at its
 * busiest span.
 *
 * Leaflet-free on purpose, like `carte-jour.ts`: the table is the accessible
 * reading of what the markers show, and it must render where the map cannot.
 */
@Component({
  selector: 'app-charge-grille',
  imports: [MatButtonToggleModule],
  templateUrl: './charge-grille.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChargeGrille {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly journee = input<JourneeCarte | null>(null);
  readonly journees = input<readonly JourneeCarte[]>([]);
  readonly emplacements = input<readonly Emplacement[]>([]);
  /** The instant the map's cursor points at: its span is the highlighted column. */
  readonly minutes = input(0);
  readonly portee = model<PorteeCharge>('jour');
  readonly instantChoisi = output<number>();
  readonly picChoisi = output<PicDemande>();

  protected readonly noCoordinatesLabel = $localize`:@@carteJour.charge.sansCoordonnees:sans coordonnées, absent de la carte`;
  private readonly totalLabel = $localize`:@@carteJour.charge.total:Total sur le site`;

  private readonly grilleJour = computed(() => grilleCharge(this.journee(), this.emplacements()));
  private readonly grilleEvenement = computed(() =>
    this.portee() === 'evenement'
      ? grilleChargeEvenement(this.journees(), this.emplacements())
      : null,
  );

  protected readonly colonnes = computed<Colonne[]>(() => {
    const evenement = this.grilleEvenement();
    if (evenement) {
      const jourCourant = this.journee()?.jour ?? null;
      return evenement.jours.map((jour) => ({
        cle: `J${jour.jour}`,
        libelle: jour.title,
        courante: jour.jour === jourCourant,
      }));
    }
    const minutes = this.minutes();
    return this.grilleJour().tranches.map((tranche) => ({
      cle: String(tranche.debutMinutes),
      libelle: tranche.libelle,
      courante: tranche.debutMinutes <= minutes && minutes < tranche.finMinutes,
    }));
  });

  protected readonly lignes = computed<LigneAffichee[]>(() => {
    const colonnes = this.colonnes();
    const evenement = this.grilleEvenement();
    if (evenement) {
      const cellules = (
        nom: string,
        valeurs: { presents: number; sieges: number; minutes: number | null }[],
      ) =>
        valeurs.map((valeur, index) =>
          this.cellule(nom, colonnes[index], valeur, evenement.presentsMax, {
            minutes: valeur.minutes,
            jour: evenement.jours[index].jour,
          }),
        );
      return [
        ...evenement.lignes.map((ligne) => ({
          cle: ligne.emplacementId ?? '',
          nom: ligne.nom,
          note: ligne.emplacementId !== null && !ligne.situe ? this.noCoordinatesLabel : '',
          total: false,
          cellules: cellules(ligne.nom, ligne.cellules),
        })),
        {
          cle: '__total',
          nom: this.totalLabel,
          note: '',
          total: true,
          // The site's total is not shaded: its scale is not the places' one.
          cellules: cellules(this.totalLabel, evenement.total).map((cellule) => ({
            ...cellule,
            niveau: 0,
          })),
        },
      ];
    }
    const grille = this.grilleJour();
    if (grille.tranches.length === 0) {
      return [];
    }
    const cellules = (nom: string, valeurs: { presents: number; sieges: number }[]) =>
      valeurs.map((valeur, index) =>
        this.cellule(nom, colonnes[index], valeur, grille.presentsMax, {
          minutes: grille.tranches[index].debutMinutes,
          jour: null,
        }),
      );
    return [
      ...grille.lignes.map((ligne) => ({
        cle: ligne.emplacementId ?? '',
        nom: ligne.nom,
        note: ligne.emplacementId !== null && !ligne.situe ? this.noCoordinatesLabel : '',
        total: false,
        cellules: cellules(ligne.nom, ligne.cellules),
      })),
      {
        cle: '__total',
        nom: this.totalLabel,
        note: '',
        total: true,
        cellules: cellules(this.totalLabel, grille.total).map((cellule) => ({
          ...cellule,
          niveau: 0,
        })),
      },
    ];
  });

  protected readonly legende = computed(() =>
    this.portee() === 'evenement'
      ? $localize`:@@carteJour.charge.legend.evenement:Chaque case donne le pic de la journée : personnes présentes / places prévues. Un clic ouvre ce jour à cette heure-là.`
      : $localize`:@@carteJour.charge.legend.jour:Chaque case : personnes présentes / places prévues. Un clic place le curseur de la carte sur cette tranche.`,
  );

  protected readonly caption = computed(() =>
    this.portee() === 'evenement'
      ? $localize`:@@carteJour.charge.caption.evenement:Charge par emplacement : une ligne par emplacement, une colonne par journée, chaque case le pic de la journée`
      : $localize`:@@carteJour.charge.caption.jour:Charge par emplacement : une ligne par emplacement, une colonne par tranche horaire de la journée`,
  );

  /** Roving tabindex: one Tab stop for the table, the arrows inside it. */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });

  /** The same position clamped to the table on screen, so one cell always carries the Tab stop. */
  protected readonly focusedPosition = computed(() => {
    const lignes = this.lignes();
    if (lignes.length === 0) {
      return { ligne: 0, colonne: 0 };
    }
    const { ligne, colonne } = this.focusedCell();
    const rowInRange = Math.min(Math.max(ligne, 0), lignes.length - 1);
    const lastColumn = Math.max(lignes[rowInRange].cellules.length - 1, 0);
    return { ligne: rowInRange, colonne: Math.min(Math.max(colonne, 0), lastColumn) };
  });

  private cellule(
    nom: string,
    colonne: Colonne,
    valeur: { presents: number; sieges: number },
    presentsMax: number,
    cible: { minutes: number | null; jour: number | null },
  ): CelluleAffichee {
    const { presents, sieges } = valeur;
    const libelle = colonne.libelle;
    return {
      cle: colonne.cle,
      texte: sieges === 0 ? '—' : `${presents}/${sieges}`,
      aria:
        sieges === 0
          ? $localize`:@@carteJour.charge.cell.vide:${nom}:emplacement:, ${libelle}:tranche: : aucune place prévue`
          : $localize`:@@carteJour.charge.cell:${nom}:emplacement:, ${libelle}:tranche: : ${presents}:presents: personne(s) présente(s) sur ${sieges}:sieges: place(s)`,
      niveau: niveauDensite(presents, presentsMax),
      courante: colonne.courante,
      minutes: cible.minutes,
      jour: cible.jour,
    };
  }

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected changerPortee(portee: PorteeCharge): void {
    this.portee.set(portee);
  }

  protected openCell(ligne: number, colonne: number): void {
    const cellule = this.lignes()[ligne]?.cellules[colonne];
    if (!cellule) {
      return;
    }
    if (cellule.jour !== null) {
      this.picChoisi.emit({ jour: cellule.jour, minutes: cellule.minutes });
    } else if (cellule.minutes !== null) {
      this.instantChoisi.emit(cellule.minutes);
    }
  }

  /** Enter and Space do what a click does; the arrows, Home and End move inside the table. */
  protected onCellKeydown(event: KeyboardEvent, ligne: number, colonne: number): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openCell(ligne, colonne);
      return;
    }
    const lignes = this.lignes();
    const target = nextGridCell(
      event.key,
      { ligne, colonne },
      lignes.length - 1,
      (lignes[ligne]?.cellules.length ?? 1) - 1,
    );
    if (!target) {
      return;
    }
    event.preventDefault();
    this.focusedCell.set(target);
    this.host.nativeElement
      .querySelector<HTMLElement>(
        `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`,
      )
      ?.focus();
  }
}
