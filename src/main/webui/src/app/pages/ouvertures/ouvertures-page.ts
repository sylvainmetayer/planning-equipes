import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
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
  SegmentCellule,
} from '../../core/models';
import {
  anomaliesParStand,
  classeCellule,
  dureeCourte,
  filtrerStands,
  FiltreOuvertures,
  iconeAnomalie,
  largeurPourcent,
  synthese,
} from './ouvertures';
import {
  AdresseCellule,
  Cellules,
  ColonneGrille,
  aplatissement,
  cellulesDepuis,
  colonneId,
  cellulesInertes,
  cellulesPartielles,
  key,
  collerBloc,
  countCopied,
  colonnes,
  deplacement,
  ecrireCellule,
  estPartielle,
  jourDeReference,
  libelleColonne,
  propagerClefs,
  propagerScission,
  readCell,
  recopierJour,
  saisie,
  scinder,
  segmentsPartiels,
  standsModifies,
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
    RouterLink,
  ],
  templateUrl: './ouvertures-page.html',
  styleUrl: '../../../styles/ouvertures.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OuverturesPage {
  private readonly standsApi = inject(StandsApi);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Typing is disabled while a solve runs: the server would refuse the save, and the landing persist would revert it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly rapport = signal<RapportOuvertures | null>(null);

  /** The stamps the displayed grid was built from, sent back as preconditions (issue #362). */
  private readonly modifieLeParStand = computed(
    () => new Map((this.rapport()?.stands ?? []).map((ligne) => [ligne.standId, ligne.modifieLe])),
  );
  protected readonly chargement = signal(true);
  protected readonly filtre = signal<FiltreOuvertures>('TOUS');
  protected readonly recherche = signal('');
  protected readonly view = signal<VueOuvertures>(
    this.route.snapshot.queryParamMap.get('vue') === 'saisie' ? 'SAISIR' : 'CONSULTER',
  );

  /* ------------------------------- entry grid ------------------------------ */

  /** The cells as typed; reset from the report on every reload. */
  protected readonly cellules = signal<Cellules>(new Map());
  /** The cells as the server last reported them: what "modified" is measured against. */
  private readonly reference = signal<Cellules>(new Map());
  private readonly partielles = signal<ReadonlySet<string>>(new Set());
  /** What each partial cell really holds: the stretches a save keeps as long as the cell is not retyped. */
  private readonly segments = signal<ReadonlyMap<string, SegmentCellule[]>>(new Map());
  /** The stands with at least one partial cell, in report order: what « Aligner » sends. */
  protected readonly standsPartiels = computed(() =>
    (this.rapport()?.stands ?? [])
      .map((ligne) => ligne.standId)
      .filter((standId) => this.hasPartialCells(standId)),
  );
  /** Cells of another stagger family's créneau: shown, never typed, never sent. */
  private readonly inertes = signal<ReadonlySet<string>>(new Set());
  /** The last cell focused: where a paste lands, and which day a row copy takes. */
  protected readonly celluleActive = signal<AdresseCellule | null>(null);
  protected readonly enregistrement = signal(false);

  /**
   * The columns as displayed: the report's, plus the ones cut in this screen
   * (« scinder »), which exist nowhere else until a cell under them is saved.
   */
  protected readonly colonnes = signal<ColonneGrille[]>([]);
  /** The column whose header shows the hour field of a cut, or none. */
  protected readonly scissionEnCours = signal<string | null>(null);
  protected readonly standsModifies = computed(() =>
    standsModifies(this.cellules(), this.reference()),
  );
  private readonly standIdsAffiches = computed(() => this.lignes().map((ligne) => ligne.standId));

  protected readonly synthese = computed(() => {
    const rapport = this.rapport();
    return rapport ? synthese(rapport) : null;
  });
  protected readonly lignes = computed<LigneStandOuverture[]>(() => {
    const rapport = this.rapport();
    return rapport ? filtrerStands(rapport, this.filtre(), this.recherche()) : [];
  });
  private readonly anomaliesParStand = computed(() =>
    anomaliesParStand(this.rapport()?.anomalies ?? []),
  );

  constructor() {
    keepViewInQueryParams(() => ({ vue: optionalParam(this.view() === 'SAISIR' ? 'saisie' : '') }));
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    try {
      const rapport = await this.standsApi.openings();
      this.rapport.set(rapport);
      this.colonnes.set(colonnes(rapport));
      this.scissionEnCours.set(null);
      this.celluleActive.set(null);
      const cellules = cellulesDepuis(rapport);
      this.reference.set(cellules);
      this.cellules.set(cellules);
      this.partielles.set(cellulesPartielles(rapport));
      this.segments.set(segmentsPartiels(rapport));
      this.inertes.set(cellulesInertes(rapport));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /** Leaving the entry view with unsaved cells asks first: they would silently survive, invisible, until the next reload. */
  protected async changeView(view: VueOuvertures): Promise<void> {
    if (view === 'CONSULTER' && this.standsModifies().length > 0) {
      const abandon = await this.confirm.ask({
        title: $localize`:@@ouvertures.saisie.quitterTitle:Abandonner les modifications ?`,
        message: $localize`:@@ouvertures.saisie.quitterMessage:${this.standsModifies().length}:stands: stand(s) ont des cases modifiées non enregistrées.`,
        confirmLabel: $localize`:@@ouvertures.saisie.quitterLabel:Abandonner`,
        danger: true,
      });
      if (!abandon) {
        return;
      }
      this.cellules.set(this.reference());
    }
    this.view.set(view);
  }

  /** A partial cell says what it holds, and that saving it as shown keeps it. */
  protected infobullePartielle(standId: string, colonneId: string): string {
    const detail = (this.segments().get(key(standId, colonneId)) ?? [])
      .map(
        (segment) =>
          `${this.heure(segment.heureDebut)}-${this.heure(segment.heureFin)} : ${segment.effectif}`,
      )
      .join(', ');
    return $localize`:@@ouvertures.saisie.partielle:Cette case porte plusieurs valeurs (${detail}:segments:). Enregistrée telle quelle, elle les garde ; modifiée, la valeur tapée s'applique à tout le créneau.`;
  }

  protected infobulleAligner(): string {
    return $localize`:@@ouvertures.saisie.alignerTooltip:Étend chaque case à plusieurs valeurs à son créneau entier, à sa valeur la plus haute. Enregistrez ou annulez d'abord vos modifications.`;
  }

  private hasPartialCells(standId: string): boolean {
    const prefixe = standId + '#';
    return Array.from(this.partielles()).some((clef) => clef.startsWith(prefixe));
  }

  protected valeur(standId: string, colonneId: string): string {
    const effectif = this.cellules().get(standId)?.get(colonneId) ?? null;
    return effectif === null ? '' : String(effectif);
  }

  protected estModifiee(standId: string, colonneId: string): boolean {
    return (
      (this.cellules().get(standId)?.get(colonneId) ?? null) !==
      (this.reference().get(standId)?.get(colonneId) ?? null)
    );
  }

  protected estPartielle(standId: string, colonneId: string): boolean {
    return estPartielle(this.partielles(), { standId, colonneId });
  }

  /** A créneau of another stagger family: this stand never holds a seat there. */
  protected estInerte(standId: string, colonneId: string): boolean {
    return this.inertes().has(key(standId, colonneId));
  }

  protected infobulleInerte(): string {
    return $localize`:@@ouvertures.saisie.inerte:Ce créneau est d'une autre famille de relais que ce stand : il n'y tiendra jamais de poste.`;
  }

  protected libelleColonne(colonne: ColonneGrille): string {
    return libelleColonne(colonne);
  }

  protected identifiant(standId: string, colonneId: string): string {
    return key(standId, colonneId);
  }

  /** A keystroke in a cell: digits become the headcount, an emptied field closes the stand; anything else is left as typed. */
  protected saisir(standId: string, colonneId: string, text: string): void {
    if (this.estInerte(standId, colonneId)) {
      return;
    }
    const lu = readCell(text);
    if (lu !== undefined) {
      this.cellules.update((cellules) => ecrireCellule(cellules, { standId, colonneId }, lu));
    }
  }

  /**
   * What is left in the field once it loses the focus: the model's own value.
   * A keystroke that is not a headcount (`5x`) is ignored by {@link saisir},
   * and without this the field would keep showing it while the grid holds — and
   * would save — the old number.
   */
  protected reafficher(event: Event, standId: string, colonneId: string): void {
    (event.target as HTMLInputElement).value = this.valeur(standId, colonneId);
  }

  protected focaliser(standId: string, colonneId: string): void {
    this.celluleActive.set({ standId, colonneId });
  }

  /**
   * Arrows, Enter, Home and End move between cells the way a spreadsheet
   * does. Left and right only when the caret cannot move inside the field
   * itself, so editing a two-digit value stays possible.
   */
  protected auClavier(event: KeyboardEvent, standId: string, colonneId: string): void {
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
    const target = deplacement(
      event.key,
      { standId, colonneId },
      this.standIdsAffiches(),
      this.colonnes(),
    );
    if (target === null) {
      return;
    }
    event.preventDefault();
    this.hote.nativeElement
      .querySelector<HTMLInputElement>(`[data-cellule="${key(target.standId, target.colonneId)}"]`)
      ?.focus();
  }

  /** A block copied from a spreadsheet lands from the cell it is pasted in; a single value pastes as typed. */
  protected auCollage(event: ClipboardEvent, standId: string, colonneId: string): void {
    const text = event.clipboardData?.getData('text') ?? '';
    if (!/[\t\n]/.test(text)) {
      return;
    }
    event.preventDefault();
    this.cellules.update((cellules) =>
      collerBloc(
        cellules,
        text,
        { standId, colonneId },
        this.standIdsAffiches(),
        this.colonnes(),
        this.inertes(),
      ),
    );
  }

  /** The day's cells, for every displayed stand, copied onto every other day. */
  protected recopierJour(date: string): void {
    this.appliquerRecopie((cellules) =>
      recopierJour(cellules, date, this.standIdsAffiches(), this.colonnes(), this.inertes()),
    );
  }

  /** One stand's day — the focused one when it is on that row, else its first day with a headcount — copied onto its other days. */
  protected recopierLigne(standId: string): void {
    const active = this.celluleActive();
    const date =
      active?.standId === standId
        ? (this.colonnes().find((colonne) => colonne.colonneId === active.colonneId)?.date ?? null)
        : jourDeReference(this.cellules(), standId, this.colonnes());
    if (date !== null) {
      this.appliquerRecopie((cellules) =>
        recopierJour(cellules, date, [standId], this.colonnes(), this.inertes()),
      );
    }
  }

  /**
   * Applies a copy and says how many cells it changed. A day whose créneaux
   * were sliced differently matches none of the target columns, and the copy
   * then does nothing at all — silence would read as success.
   */
  private appliquerRecopie(recopie: (cellules: Cellules) => Cellules): void {
    const before = this.cellules();
    const after = recopie(before);
    const changees = countCopied(before, after, this.colonnes());
    this.cellules.set(after);
    if (changees === 0) {
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.recopieVide:Aucune case recopiée : les créneaux des autres jours n'ont pas les mêmes horaires.`,
        variant: 'warning',
        timeout: 6000,
      });
    }
  }

  /**
   * Cuts a column at `heure`, in the screen only: two columns where there
   * was one, every stand's value carried onto both, nothing modified until a
   * cell under them is typed. Saved, such a cell writes a window at the
   * column's bounds, and the server reports the boundary from then on. An
   * hour on the column's edge, or outside it, cuts nothing and says so.
   */
  protected scinder(colonne: ColonneGrille, heure: string): void {
    this.scissionEnCours.set(null);
    const nouvelles = scinder(this.colonnes(), colonne.colonneId, heure);
    if (nouvelles === null) {
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.scissionInvalide:L'heure de coupe doit tomber strictement entre ${libelleColonne(colonne)}:colonne:.`,
        variant: 'warning',
        timeout: 6000,
      });
      return;
    }
    const ids = [
      colonneId(colonne.creneauId, colonne.heureDebut, heure),
      colonneId(colonne.creneauId, heure, colonne.heureFin),
    ];
    const ancienne = colonne.colonneId;
    this.colonnes.set(nouvelles);
    this.cellules.update((cellules) => propagerScission(cellules, ancienne, ids));
    this.reference.update((cellules) => propagerScission(cellules, ancienne, ids));
    this.segments.update((segments) => propagerClefs(segments, ancienne, ids));
    this.partielles.update((partielles) => this.propagerEnsemble(partielles, ancienne, ids));
    this.inertes.update((inertes) => this.propagerEnsemble(inertes, ancienne, ids));
    this.celluleActive.set(null);
  }

  private propagerEnsemble(
    ensemble: ReadonlySet<string>,
    ancienne: string,
    nouvelles: readonly string[],
  ): Set<string> {
    const marques = new Map(Array.from(ensemble, (clef) => [clef, true] as const));
    return new Set(propagerClefs(marques, ancienne, nouvelles).keys());
  }

  /** The columns of one day, cuts included — what the day header spans. */
  protected colonnesDuJour(date: string): ColonneGrille[] {
    return this.colonnes().filter((colonne) => colonne.date === date);
  }

  protected annuler(): void {
    this.cellules.set(this.reference());
  }

  /**
   * Sends the modified stands, each with its whole schedule. A partial cell
   * saved as shown keeps its stretches; one that was retyped is named first,
   * because the value typed will then cover the whole créneau.
   */
  protected async enregistrer(): Promise<void> {
    const modifies = this.standsModifies();
    if (modifies.length === 0 || this.enregistrement()) {
      return;
    }
    const aplatis = modifies.filter((standId) =>
      Array.from(this.partielles()).some((clef) => {
        const [stand, id] = clef.split('#');
        return stand === standId && this.estModifiee(standId, id);
      }),
    );
    if (aplatis.length > 0) {
      const confirme = await this.confirm.ask({
        title: $localize`:@@ouvertures.saisie.aplatirTitle:Remplacer des cases à plusieurs valeurs ?`,
        message: $localize`:@@ouvertures.saisie.aplatirMessage:${aplatis.join(', ')}:stands: : des cases qui portaient plusieurs valeurs ont été modifiées ; la valeur tapée s'appliquera à tout le créneau.`,
        confirmLabel: $localize`:@@ouvertures.saisie.aplatirLabel:Enregistrer`,
      });
      if (!confirme) {
        return;
      }
    }
    await this.send(modifies, false);
  }

  /**
   * Every stand the server reported partial, sent back with `aplatir`: the
   * one gesture that extends a partial cell to its whole créneau. The
   * confirmation prices it first — stands, cells, hours of opening added —
   * because on the reference event that is 12 stands and 74 hours. Refused
   * while cells are modified: the reload after the save would drop them.
   */
  protected async alignerPartiels(): Promise<void> {
    const partiels = this.standsPartiels();
    if (partiels.length === 0 || this.standsModifies().length > 0 || this.enregistrement()) {
      return;
    }
    const cout = aplatissement(this.segments(), this.colonnes());
    const heures = Math.round(cout.minutes / 6) / 10;
    const confirme = await this.confirm.ask({
      title: $localize`:@@ouvertures.saisie.alignerTitle:Aligner les fenêtres sur les créneaux ?`,
      message: $localize`:@@ouvertures.saisie.alignerMessage:${partiels.length}:stands: stand(s), ${cout.cases}:cases: case(s) : chaque case sera étendue à son créneau entier, à sa valeur la plus haute, soit ${heures}:heures: h d'ouverture en plus (${partiels.join(', ')}:liste:).`,
      confirmLabel: $localize`:@@ouvertures.saisie.alignerLabel:Aligner`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    await this.send(partiels, true);
  }

  /** The save itself: the named stands, each with all its cells, then a reload and a line on what was written. */
  private async send(standIds: readonly string[], aplatir: boolean): Promise<void> {
    this.enregistrement.set(true);
    try {
      const rapport = await this.standsApi.saveOpeningsGrid(
        saisie(this.cellules(), standIds, this.colonnes(), {
          inertes: this.inertes(),
          modifieLeParStand: this.modifieLeParStand(),
          aplatir,
        }),
      );
      const regles = rapport.stands.reduce((total, ligne) => total + ligne.regles, 0);
      const exceptions = rapport.stands.reduce((total, ligne) => total + ligne.exceptions, 0);
      // A stand whose rules would not reproduce its own segments stays fully
      // dated, and the server says so per stand — worth a line rather than a
      // number the reader cannot explain.
      const nonCompactes = rapport.stands
        .filter((ligne) => !ligne.compacte)
        .map((ligne) => ligne.standId);
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.doneTitle:Horaires enregistrés`,
        message:
          $localize`:@@ouvertures.saisie.doneMessage:${rapport.stands.length}:stands: stand(s) réécrit(s) en ${regles}:regles: règle(s) et ${exceptions}:exceptions: exception(s) datée(s).` +
          (nonCompactes.length > 0
            ? ' ' +
              $localize`:@@ouvertures.saisie.doneNonCompactes:${nonCompactes.join(', ')}:stands: sont restés en fenêtres datées : leur motif ne se répète pas.`
            : ''),
        variant: 'success',
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
      minutes: $localize`:@@ouvertures.duree.minutes:min`,
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
    return anomalies.length === 0 ? '' : anomalies.map((anomaly) => anomaly.message).join('\n');
  }

  /** Everything a cell says, for its tooltip — state, windows, decisive layer, postes. */
  protected infobulleCellule(cellule: CelluleJourOuverture): string {
    const lignes: string[] = [this.libelleEtat(cellule)];
    if (cellule.fenetres.length > 0) {
      lignes.push(
        cellule.fenetres
          .map((fenetre) => `${this.heure(fenetre.heureDebut)} → ${this.heure(fenetre.heureFin)}`)
          .join(', '),
      );
    }
    lignes.push(
      $localize`:@@ouvertures.tooltip.ouvert:Ouvert ${this.duree(cellule.minutesOuvertes)}:ouvert: sur ${this.duree(cellule.minutesAmplitude)}:amplitude:`,
    );
    lignes.push(
      $localize`:@@ouvertures.tooltip.postes:${cellule.postes}:postes: poste(s) généré(s)`,
    );
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
