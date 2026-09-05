import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  AnomalieOuverture,
  CelluleJourOuverture,
  LigneStandOuverture,
  RapportOuvertures,
  RapportSaisieGrille
} from '../../core/models';
import {
  anomaliesParStand,
  classeCellule,
  dureeCourte,
  filtrerStands,
  FiltreOuvertures,
  iconeAnomalie,
  largeurPourcent,
  synthese
} from './ouvertures';
import {
  AdresseCellule,
  Cellules,
  ColonneGrille,
  cellulesDepuis,
  cellulesPartielles,
  cle,
  collerBloc,
  colonnes,
  deplacement,
  ecrireCellule,
  estPartielle,
  jourDeReference,
  libelleColonne,
  lireCellule,
  recopierJour,
  saisie,
  standsModifies
} from './grille-horaires';

/** The two faces of the screen: reading what a solve would get, or typing it. */
export type VueOuvertures = 'CONSULTER' | 'SAISIR';

/**
 * Read-only stand × jour grid of the opening schedule actually in force, so an
 * administrator can validate it visually before spending minutes on a solve.
 *
 * <p>Everything shown comes from `GET /api/ouvertures-stands`, which builds it
 * server-side from the very postes `PlanningService.construirePostes` would hand
 * the solver — recurring horaires expanded, dated exceptions applied, windows
 * clamped to each créneau. Deliberately not recomputed here: a validation screen
 * that offers a second interpretation of the data validates nothing.
 *
 * <p>The same grid is also where the schedule is typed (« Saisir »): one
 * integer per stand and créneau, the way the organiser's own spreadsheet holds
 * it, with the moves a spreadsheet user expects — arrows, Enter, a pasted
 * block, a day copied onto the others. Nothing is written until « Enregistrer »,
 * and only the stands whose cells changed are sent, each with its whole
 * schedule (`PUT /api/ouvertures-stands/grille`).</p>
 */
@Component({
  selector: 'app-ouvertures-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatTooltipModule,
    RouterLink
  ],
  templateUrl: './ouvertures-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class OuverturesPage {
  private readonly api = inject(ApiService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Typing is disabled while a solve runs: the server would refuse the save, and the landing persist would revert it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly rapport = signal<RapportOuvertures | null>(null);
  protected readonly chargement = signal(true);
  protected readonly filtre = signal<FiltreOuvertures>('TOUS');
  protected readonly recherche = signal('');
  protected readonly vue = signal<VueOuvertures>(
    this.route.snapshot.queryParamMap.get('vue') === 'saisie' ? 'SAISIR' : 'CONSULTER'
  );

  /* ------------------------------- entry grid ------------------------------ */

  /** The cells as typed; reset from the report on every reload. */
  protected readonly cellules = signal<Cellules>(new Map());
  /** The cells as the server last reported them: what "modified" is measured against. */
  private readonly reference = signal<Cellules>(new Map());
  private readonly partielles = signal<ReadonlySet<string>>(new Set());
  /** The last cell focused: where a paste lands, and which day a row copy takes. */
  protected readonly celluleActive = signal<AdresseCellule | null>(null);
  protected readonly enregistrement = signal(false);

  protected readonly colonnes = computed<ColonneGrille[]>(() => {
    const rapport = this.rapport();
    return rapport ? colonnes(rapport) : [];
  });
  protected readonly standsModifies = computed(() => standsModifies(this.cellules(), this.reference()));
  private readonly standIdsAffiches = computed(() => this.lignes().map((ligne) => ligne.standId));

  protected readonly synthese = computed(() => {
    const rapport = this.rapport();
    return rapport ? synthese(rapport) : null;
  });
  protected readonly lignes = computed<LigneStandOuverture[]>(() => {
    const rapport = this.rapport();
    return rapport ? filtrerStands(rapport, this.filtre(), this.recherche()) : [];
  });
  private readonly anomaliesParStand = computed(() => anomaliesParStand(this.rapport()?.anomalies ?? []));

  constructor() {
    keepViewInQueryParams(() => ({ vue: optionalParam(this.vue() === 'SAISIR' ? 'saisie' : '') }));
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    try {
      const rapport = await this.api.get<RapportOuvertures>('/api/ouvertures-stands');
      this.rapport.set(rapport);
      const cellules = cellulesDepuis(rapport);
      this.reference.set(cellules);
      this.cellules.set(cellules);
      this.partielles.set(cellulesPartielles(rapport));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /** Leaving the entry view with unsaved cells asks first: they would silently survive, invisible, until the next reload. */
  protected async changerVue(vue: VueOuvertures): Promise<void> {
    if (vue === 'CONSULTER' && this.standsModifies().length > 0) {
      const abandon = await this.confirm.ask({
        title: $localize`:@@ouvertures.saisie.quitterTitle:Abandonner les modifications ?`,
        message: $localize`:@@ouvertures.saisie.quitterMessage:${this.standsModifies().length}:stands: stand(s) ont des cases modifiées non enregistrées.`,
        confirmLabel: $localize`:@@ouvertures.saisie.quitterLabel:Abandonner`,
        danger: true
      });
      if (!abandon) {
        return;
      }
      this.cellules.set(this.reference());
    }
    this.vue.set(vue);
  }

  protected infobullePartielle(): string {
    return $localize`:@@ouvertures.saisie.partielle:Les fenêtres de ce stand ne suivent pas les bornes de ce créneau ; enregistrer depuis la grille les alignera sur le créneau.`;
  }

  protected valeur(standId: string, creneauId: number): string {
    const effectif = this.cellules().get(standId)?.get(creneauId) ?? null;
    return effectif === null ? '' : String(effectif);
  }

  protected estModifiee(standId: string, creneauId: number): boolean {
    return (this.cellules().get(standId)?.get(creneauId) ?? null) !== (this.reference().get(standId)?.get(creneauId) ?? null);
  }

  protected estPartielle(standId: string, creneauId: number): boolean {
    return estPartielle(this.partielles(), { standId, creneauId });
  }

  protected libelleColonne(colonne: ColonneGrille): string {
    return libelleColonne(colonne);
  }

  protected identifiant(standId: string, creneauId: number): string {
    return cle(standId, creneauId);
  }

  /** A keystroke in a cell: digits become the headcount, an emptied field closes the stand; anything else is left as typed. */
  protected saisir(standId: string, creneauId: number, texte: string): void {
    const lu = lireCellule(texte);
    if (lu !== undefined) {
      this.cellules.update((cellules) => ecrireCellule(cellules, { standId, creneauId }, lu));
    }
  }

  protected focaliser(standId: string, creneauId: number): void {
    this.celluleActive.set({ standId, creneauId });
  }

  /**
   * Arrows, Enter, Home and End move between cells the way a spreadsheet
   * does. Left and right only when the caret cannot move inside the field
   * itself, so editing a two-digit value stays possible.
   */
  protected auClavier(event: KeyboardEvent, standId: string, creneauId: number): void {
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    const champ = event.target as HTMLInputElement;
    if (event.key === 'ArrowLeft' && (champ.selectionStart ?? 0) > 0) {
      return;
    }
    if (event.key === 'ArrowRight' && (champ.selectionEnd ?? 0) < champ.value.length) {
      return;
    }
    const cible = deplacement(event.key, { standId, creneauId }, this.standIdsAffiches(), this.colonnes());
    if (cible === null) {
      return;
    }
    event.preventDefault();
    this.hote.nativeElement.querySelector<HTMLInputElement>(`[data-cellule="${cle(cible.standId, cible.creneauId)}"]`)?.focus();
  }

  /** A block copied from a spreadsheet lands from the cell it is pasted in; a single value pastes as typed. */
  protected auCollage(event: ClipboardEvent, standId: string, creneauId: number): void {
    const texte = event.clipboardData?.getData('text') ?? '';
    if (!/[\t\n]/.test(texte)) {
      return;
    }
    event.preventDefault();
    this.cellules.update((cellules) =>
      collerBloc(cellules, texte, { standId, creneauId }, this.standIdsAffiches(), this.colonnes())
    );
  }

  /** The day's cells, for every displayed stand, copied onto every other day. */
  protected recopierJour(date: string): void {
    this.cellules.update((cellules) => recopierJour(cellules, date, this.standIdsAffiches(), this.colonnes()));
  }

  /** One stand's day — the focused one when it is on that row, else its first day with a headcount — copied onto its other days. */
  protected recopierLigne(standId: string): void {
    const active = this.celluleActive();
    const date =
      active?.standId === standId
        ? (this.colonnes().find((colonne) => colonne.creneauId === active.creneauId)?.date ?? null)
        : jourDeReference(this.cellules(), standId, this.colonnes());
    if (date !== null) {
      this.cellules.update((cellules) => recopierJour(cellules, date, [standId], this.colonnes()));
    }
  }

  protected annuler(): void {
    this.cellules.set(this.reference());
  }

  /**
   * Sends the modified stands, each with its whole schedule. Cells the server
   * reported partial are named first: a save flattens them onto the créneau,
   * and that is worth a look before it happens.
   */
  protected async enregistrer(): Promise<void> {
    const modifies = this.standsModifies();
    if (modifies.length === 0 || this.enregistrement()) {
      return;
    }
    const aplatis = modifies.filter((standId) =>
      Array.from(this.partielles()).some((clef) => clef.startsWith(standId + '#'))
    );
    if (aplatis.length > 0) {
      const confirme = await this.confirm.ask({
        title: $localize`:@@ouvertures.saisie.aplatirTitle:Aligner des fenêtres sur les créneaux ?`,
        message: $localize`:@@ouvertures.saisie.aplatirMessage:${aplatis.join(', ')}:stands: : certaines fenêtres ne suivaient pas les bornes des créneaux. Enregistrer depuis la grille les aligne sur les créneaux.`,
        confirmLabel: $localize`:@@ouvertures.saisie.aplatirLabel:Enregistrer`
      });
      if (!confirme) {
        return;
      }
    }
    this.enregistrement.set(true);
    try {
      const rapport = await this.api.put<RapportSaisieGrille>('/api/ouvertures-stands/grille', {
        stands: saisie(this.cellules(), modifies)
      });
      const regles = rapport.stands.reduce((total, ligne) => total + ligne.regles, 0);
      const exceptions = rapport.stands.reduce((total, ligne) => total + ligne.exceptions, 0);
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.doneTitle:Horaires enregistrés`,
        message: $localize`:@@ouvertures.saisie.doneMessage:${rapport.stands.length}:stands: stand(s) réécrit(s) en ${regles}:regles: règle(s) et ${exceptions}:exceptions: exception(s) datée(s).`,
        variant: 'success'
      });
      await this.recharger();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }

  protected readonly largeurPourcent = largeurPourcent;
  protected readonly classeCellule = classeCellule;
  protected readonly iconeAnomalie = iconeAnomalie;

  protected duree(minutes: number): string {
    return dureeCourte(minutes, {
      heures: $localize`:@@ouvertures.duree.heures:h`,
      minutes: $localize`:@@ouvertures.duree.minutes:min`
    });
  }

  /** `2026-07-08` → `08/07`, short enough for a dozen columns. */
  protected libelleJour(date: string): string {
    const [, mois, jour] = date.split('-');
    return `${jour}/${mois}`;
  }

  protected anomaliesDe(standId: string): AnomalieOuverture[] {
    return this.anomaliesParStand().get(standId) ?? [];
  }

  protected infobulleStand(ligne: LigneStandOuverture): string {
    const anomalies = this.anomaliesDe(ligne.standId);
    return anomalies.length === 0 ? '' : anomalies.map((anomalie) => anomalie.message).join('\n');
  }

  /** Everything a cell says, for its tooltip — state, windows, decisive layer, postes. */
  protected infobulleCellule(cellule: CelluleJourOuverture): string {
    const lignes: string[] = [this.libelleEtat(cellule)];
    if (cellule.fenetres.length > 0) {
      lignes.push(
        cellule.fenetres
          .map((fenetre) => `${this.heure(fenetre.heureDebut)} → ${this.heure(fenetre.heureFin)}`)
          .join(', ')
      );
    }
    lignes.push(
      $localize`:@@ouvertures.tooltip.ouvert:Ouvert ${this.duree(cellule.minutesOuvertes)}:ouvert: sur ${this.duree(cellule.minutesAmplitude)}:amplitude:`
    );
    lignes.push($localize`:@@ouvertures.tooltip.postes:${cellule.postes}:postes: poste(s) généré(s)`);
    lignes.push(this.libelleSource(cellule));
    return lignes.join('\n');
  }

  protected libelleEtat(cellule: CelluleJourOuverture): string {
    switch (cellule.etat) {
      case 'OUVERT_TOTAL':
        return $localize`:@@ouvertures.etat.total:Ouvert toute l'amplitude`;
      case 'OUVERT_PARTIEL':
        return $localize`:@@ouvertures.etat.partiel:Ouvert partiellement`;
      case 'FERME':
        return $localize`:@@ouvertures.etat.ferme:Fermé`;
    }
  }

  protected libelleSource(cellule: CelluleJourOuverture): string {
    switch (cellule.source) {
      case 'DEFAUT':
        return $localize`:@@ouvertures.source.defaut:Aucune règle ni exception : ouvert par défaut`;
      case 'REGLE':
        return $localize`:@@ouvertures.source.regle:Décidé par une règle d'horaire récurrente`;
      case 'EXCEPTION':
        return $localize`:@@ouvertures.source.exception:Décidé par une exception datée, qui prime sur les règles`;
    }
  }

  /** `09:00:00` → `09:00`. */
  protected heure(valeur: string): string {
    return valeur.length > 5 ? valeur.slice(0, 5) : valeur;
  }
}
