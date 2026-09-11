import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { errorMessage } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { TableFilter } from '../../shared/table-filter';
import {
  Animateur,
  ImportCompetencesAction,
  ImportCompetencesDemande,
  ImportCompetencesRapport,
  LigneSaisieCompetences,
  NiveauCompetence,
  RapportSaisieCompetences,
  TypologieItem,
} from '../../core/models';
import {
  CompetenceAddress,
  CellulesCompetences,
  cellKey,
  cellsFrom,
  filterAnimateurs,
  isCellModified,
  keepLocalRows,
  levelAt,
  levelForKey,
  modifiedAnimateurs,
  moveFrom,
  nextLevel,
  readTypologiesParam,
  saisie,
  writeCell,
} from './grille-competences';

/**
 * The animateur × typologie grid of appreciations, typed in place: a cell
 * changes on a click (the levels cycle) or on a key (0 empties it, 1 to 3 set
 * the level), the arrows walk the grid, and nothing is written before
 * « Enregistrer ». Only the animateurs whose cells changed are sent, each
 * with their whole map and the stamp their fiche carried when the grid read
 * it — a fiche another session wrote meanwhile is refused alone, and the
 * screen then asks: reload, or overwrite.
 *
 * <p>The wishes an animateur declared are shown in the same cells, read-only,
 * so the appreciation is typed in sight of the wish. The grid also leaves and
 * comes back as a CSV of ids and levels: exported as it stands, imported
 * after a row-by-row preview — a blank cell of the file leaves the stored
 * appreciation alone, removing stays a gesture of this screen.</p>
 */
@Component({
  selector: 'app-competences-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
    TableFilter,
  ],
  templateUrl: './competences-page.html',
  styleUrls: ['./competences-page.css', '../../../styles/import-animateurs.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompetencesPage {
  private readonly animateursApi = inject(AnimateursApi);
  private readonly store = inject(ReferenceDataStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Typing is disabled while a solve runs: the server would refuse the save, and the landing persist would revert it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('csvInput');

  protected readonly chargement = signal(true);
  protected readonly enregistrement = signal(false);

  /* -------------------------------- view state ------------------------------- */

  protected readonly filtre = signal(this.route.snapshot.queryParamMap.get('q') ?? '');
  private readonly typologiesParam = signal(this.route.snapshot.queryParamMap.get('typologies'));
  protected readonly typologies = computed<TypologieItem[]>(() => this.store.typologies());
  private readonly typologieIds = computed(() =>
    this.typologies().map((typologie) => typologie.id),
  );
  /** The columns chosen, kept to the ones the referential has; empty means every column. */
  protected readonly typologiesChoisies = computed(() =>
    readTypologiesParam(this.typologiesParam(), this.typologieIds()),
  );
  protected readonly colonnes = computed<TypologieItem[]>(() => {
    const choisies = new Set(this.typologiesChoisies());
    return choisies.size === 0
      ? this.typologies()
      : this.typologies().filter((typologie) => choisies.has(typologie.id));
  });
  protected readonly lignes = computed<Animateur[]>(() =>
    filterAnimateurs(this.store.animateurs(), this.filtre()),
  );
  protected readonly totalAnimateurs = computed(() => this.store.animateurs().length);
  protected readonly filteredView = computed(
    () => this.filtre().trim() !== '' || this.typologiesChoisies().length > 0,
  );

  /* -------------------------------- entry grid ------------------------------- */

  /** The cells as typed; reset from the roster on every reload. */
  protected readonly cells = signal<CellulesCompetences>(new Map());
  /** The cells as the roster last reported them: what "modified" is measured against. */
  private readonly reference = signal<CellulesCompetences>(new Map());
  /** The stamps the displayed grid was built from, sent back as preconditions (issue #362). */
  private readonly changedById = computed(
    () =>
      new Map(
        this.store.animateurs().map((animateur) => [animateur.id, animateur.modifieLe ?? null]),
      ),
  );
  private readonly souhaits = computed(
    () =>
      new Map(
        this.store
          .animateurs()
          .map((animateur) => [animateur.id, new Set(animateur.souhaits ?? [])]),
      ),
  );
  /** The cell carrying the roving tabindex: the way into the grid from the filter, and where the focus comes back. */
  protected readonly celluleActive = signal<CompetenceAddress | null>(null);
  protected readonly animateursModifies = computed(() =>
    modifiedAnimateurs(this.cells(), this.reference()),
  );
  private readonly animateurIdsAffiches = computed(() =>
    this.lignes().map((animateur) => animateur.id),
  );
  private readonly typologieIdsAffiches = computed(() =>
    this.colonnes().map((typologie) => typologie.id),
  );

  /* --------------------------------- import ---------------------------------- */

  protected readonly importOuvert = signal(false);
  /** The file's text, held only for the lifetime of the screen. */
  private readonly contenu = signal('');
  private lastAnalysis = 0;
  protected readonly fileName = signal('');
  protected readonly rapportImport = signal<ImportCompetencesRapport | null>(null);
  protected readonly erreurImport = signal('');
  protected readonly analysisPending = signal(false);
  protected readonly importPending = signal(false);
  protected readonly downloadPending = signal(false);
  protected readonly colonnesIgnorees = computed(() =>
    (this.rapportImport()?.columns ?? []).filter((colonne) => colonne.typologieId === null),
  );
  protected readonly peutImporter = computed(
    () =>
      this.contenu() !== '' &&
      this.rapportImport() !== null &&
      !this.rapportImport()?.applied &&
      (this.rapportImport()?.accepted ?? 0) > 0 &&
      !this.analysisPending() &&
      !this.importPending() &&
      !this.editingLocked(),
  );

  constructor() {
    keepViewInQueryParams(() => ({
      q: optionalParam(this.filtre()),
      typologies: optionalParam(this.typologiesChoisies().join(',')),
    }));
    void this.recharger();
  }

  /**
   * Reloads the roster and the typologies, and rebuilds the cells from them —
   * except for the rows in `kept`, which keep what was typed (a refused row
   * the user chose to keep).
   */
  protected async recharger(kept: ReadonlySet<string> = new Set()): Promise<void> {
    this.chargement.set(true);
    try {
      await this.store.reload(['animateurs', 'typologies']);
      const reference = cellsFrom(this.store.animateurs(), this.typologieIds());
      this.reference.set(reference);
      this.cells.set(keepLocalRows(reference, this.cells(), kept));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /* ---------------------------------- cells ---------------------------------- */

  protected niveau(animateurId: string, typologieId: string): NiveauCompetence | null {
    return levelAt(this.cells(), { animateurId, typologieId });
  }

  protected isChanged(animateurId: string, typologieId: string): boolean {
    return isCellModified(this.cells(), this.reference(), { animateurId, typologieId });
  }

  protected isWished(animateurId: string, typologieId: string): boolean {
    return this.souhaits().get(animateurId)?.has(typologieId) ?? false;
  }

  protected identifiant(animateurId: string, typologieId: string): string {
    return cellKey(animateurId, typologieId);
  }

  /** The first displayed cell carries the tabindex until one is focused; a filtered-out active cell hands it back. */
  protected tabindex(animateurId: string, typologieId: string): number {
    const active = this.celluleActive();
    const ids = this.animateurIdsAffiches();
    const colonnes = this.typologieIdsAffiches();
    const current =
      active && ids.includes(active.animateurId) && colonnes.includes(active.typologieId)
        ? active
        : { animateurId: ids[0], typologieId: colonnes[0] };
    return current.animateurId === animateurId && current.typologieId === typologieId ? 0 : -1;
  }

  protected libelleNiveau(niveau: NiveauCompetence | null): string {
    switch (niveau) {
      case 'DEBUTANT':
        return $localize`:@@competences.niveau.debutant:Débutant`;
      case 'AUTONOME':
        return $localize`:@@competences.niveau.autonome:Autonome`;
      case 'REFERENT':
        return $localize`:@@competences.niveau.referent:Référent`;
      default:
        return $localize`:@@competences.niveau.aucun:Aucune appréciation`;
    }
  }

  /** What the cell shows: one letter, the legend says which. */
  protected lettreNiveau(niveau: NiveauCompetence | null): string {
    switch (niveau) {
      case 'DEBUTANT':
        return $localize`:@@competences.lettre.debutant:D`;
      case 'AUTONOME':
        return $localize`:@@competences.lettre.autonome:A`;
      case 'REFERENT':
        return $localize`:@@competences.lettre.referent:R`;
      default:
        return '';
    }
  }

  protected classeNiveau(niveau: NiveauCompetence | null): string {
    return niveau === null ? 'niveau-aucun' : 'niveau-' + niveau.toLowerCase();
  }

  /** Everything a cell says, for its accessible name: who, which typologie, the level, the wish. */
  protected libelleCellule(animateur: Animateur, typologie: TypologieItem): string {
    const parts = [
      `${animateur.prenom} ${animateur.nom}`,
      typologie.label || typologie.id,
      this.libelleNiveau(this.niveau(animateur.id, typologie.id)),
    ];
    if (this.isWished(animateur.id, typologie.id)) {
      parts.push($localize`:@@competences.cellule.souhait:souhaitée par l'animateur`);
    }
    return parts.join(' · ');
  }

  protected focaliser(animateurId: string, typologieId: string): void {
    this.celluleActive.set({ animateurId, typologieId });
  }

  /** Arrow down from the filter lands on the current cell. */
  protected focusCurrent(): void {
    const ids = this.animateurIdsAffiches();
    const colonnes = this.typologieIdsAffiches();
    if (ids.length === 0 || colonnes.length === 0) {
      return;
    }
    const active = this.celluleActive();
    const target =
      active && ids.includes(active.animateurId) && colonnes.includes(active.typologieId)
        ? active
        : { animateurId: ids[0], typologieId: colonnes[0] };
    this.focusCell(target);
  }

  private focusCell(target: CompetenceAddress): void {
    this.host.nativeElement
      .querySelector<HTMLElement>(
        `[data-cellule="${cellKey(target.animateurId, target.typologieId)}"]`,
      )
      ?.focus();
  }

  private write(animateurId: string, typologieId: string, niveau: NiveauCompetence | null): void {
    if (this.editingLocked()) {
      return;
    }
    this.cells.update((cells) => writeCell(cells, { animateurId, typologieId }, niveau));
  }

  /** A click cycles the level; the button's own Enter and Space are handled on keydown. */
  protected cycler(animateurId: string, typologieId: string): void {
    this.write(animateurId, typologieId, nextLevel(this.niveau(animateurId, typologieId)));
  }

  /**
   * The keys of a cell: 0 to 3 set the level, Space cycles it, the arrows,
   * Home, End and Enter move — Enter down, the way a spreadsheet does. Bound
   * to the cell, consuming only what it uses: everything else travels up to
   * the application's global listener.
   */
  protected onKey(event: KeyboardEvent, animateurId: string, typologieId: string): void {
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    const niveau = levelForKey(event.key);
    if (niveau !== undefined) {
      event.preventDefault();
      this.write(animateurId, typologieId, niveau);
      return;
    }
    if (event.key === ' ') {
      event.preventDefault();
      this.cycler(animateurId, typologieId);
      return;
    }
    const target = moveFrom(
      event.key,
      { animateurId, typologieId },
      this.animateurIdsAffiches(),
      this.typologieIdsAffiches(),
    );
    if (target === null) {
      return;
    }
    event.preventDefault();
    this.focusCell(target);
  }

  /* --------------------------------- filters --------------------------------- */

  protected choisirTypologies(ids: string[]): void {
    this.typologiesParam.set(ids.join(','));
  }

  protected resetView(): void {
    this.filtre.set('');
    this.typologiesParam.set(null);
  }

  /* ---------------------------------- save ----------------------------------- */

  protected discard(): void {
    this.cells.set(this.reference());
  }

  /** Leaving with unsaved cells asks first — they would silently survive, invisible, until the next reload. */
  async canLeave(): Promise<boolean> {
    if (this.animateursModifies().length === 0) {
      return true;
    }
    return this.confirm.ask({
      title: $localize`:@@competences.quitter.titre:Abandonner les modifications ?`,
      message: $localize`:@@competences.quitter.message:${this.animateursModifies().length}:animateurs: fiche(s) ont des cases modifiées non enregistrées.`,
      confirmLabel: $localize`:@@competences.quitter.label:Abandonner`,
      danger: true,
    });
  }

  /** Sends the modified animateurs, each with their whole map, and reads how each row ended. */
  protected async save(): Promise<void> {
    const modifies = this.animateursModifies();
    if (modifies.length === 0 || this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    try {
      const rapport = await this.animateursApi.saveCompetencesGrid(
        saisie(this.cells(), modifies, this.changedById()),
      );
      await this.traiterRapport(rapport, 0);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }

  /**
   * What the report says, row by row. A rejected row is named and kept as
   * typed. A stale row is the one refusal with an answer (issue #362): the
   * user reloads — the other session's version wins, nothing of theirs is
   * written — or overwrites — the same rows go again without their
   * precondition. Dismissing keeps the rows as typed, unsent.
   */
  private async traiterRapport(
    rapport: RapportSaisieCompetences,
    dejaEcrits: number,
  ): Promise<void> {
    const ecrites = rapport.animateurs.filter((ligne) => ligne.resultat === 'WRITTEN');
    const perimees = rapport.animateurs.filter((ligne) => ligne.resultat === 'STALE');
    const refusees = rapport.animateurs.filter((ligne) => ligne.resultat === 'REJECTED');
    const total = dejaEcrits + ecrites.length;
    if (refusees.length > 0) {
      this.notifications.notify({
        title: $localize`:@@competences.refusees.titre:${refusees.length}:lignes: ligne(s) refusée(s), non enregistrée(s)`,
        message: refusees
          .map((ligne) => `${ligne.animateurId} : ${ligne.message ?? ''}`)
          .join('\n'),
        variant: 'error',
        timeout: 0,
      });
    }
    const gardees = new Set(refusees.map((ligne) => ligne.animateurId));
    if (perimees.length > 0) {
      const choix = await this.confirm.askThreeWay({
        title: $localize`:@@crud.error.conflit:Modifiée entre-temps`,
        message: this.messageConflit(perimees),
        confirmLabel: $localize`:@@crud.concurrent.overwrite:Écraser quand même`,
        cancelLabel: $localize`:@@crud.concurrent.reload:Recharger`,
        danger: true,
      });
      if (choix === true) {
        const ids = perimees.map((ligne) => ligne.animateurId);
        const relance = await this.animateursApi.saveCompetencesGrid(
          saisie(this.cells(), ids).map((ligne) => ({ ...ligne, modifieLe: null })),
        );
        await this.traiterRapport(relance, total);
        return;
      }
      if (choix === null) {
        perimees.forEach((ligne) => gardees.add(ligne.animateurId));
      } else {
        this.notifications.notify({
          title: $localize`:@@competences.recharge.titre:${perimees.length}:lignes: fiche(s) rechargée(s), vos modifications n'y ont pas été enregistrées.`,
          variant: 'warning',
          timeout: 8000,
        });
      }
    }
    if (total > 0) {
      this.notifications.notify({
        title: $localize`:@@competences.enregistre.titre:Compétences enregistrées`,
        message: $localize`:@@competences.enregistre.message:${total}:animateurs: fiche(s) mise(s) à jour.`,
        variant: 'success',
      });
    }
    await this.recharger(gardees);
  }

  private messageConflit(perimees: LigneSaisieCompetences[]): string {
    const quand = (ligne: LigneSaisieCompetences) =>
      ligne.modifieLe ? ` (${new Date(ligne.modifieLe).toLocaleString(intlLocale())})` : '';
    return (
      $localize`:@@competences.conflit.message:${perimees.length}:lignes: fiche(s) ont été modifiées par une autre session après l'ouverture de la grille : ${perimees.map((ligne) => ligne.animateurId + quand(ligne)).join(', ')}:fiches:.` +
      ' ' +
      $localize`:@@competences.conflit.choix:Rechargez pour voir ce qui a changé, ou écrasez avec la saisie de cet écran.`
    );
  }

  /* ---------------------------------- CSV ------------------------------------ */

  protected async exporter(): Promise<void> {
    this.downloadPending.set(true);
    try {
      await this.animateursApi.downloadCompetencesGrid();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.downloadPending.set(false);
    }
  }

  protected openImport(): void {
    this.importOuvert.set(true);
  }

  protected chooseFile(): void {
    this.fileInput().nativeElement.click();
  }

  protected async onFileChosen(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) {
      return;
    }
    this.lastAnalysis++;
    this.rapportImport.set(null);
    this.fileName.set(file.name);
    this.contenu.set(await file.text());
    await this.analyze();
  }

  /** The preview, numbered so only the answer to the last request is kept. */
  protected async analyze(): Promise<void> {
    if (this.fileName() === '') {
      return;
    }
    const numero = ++this.lastAnalysis;
    this.analysisPending.set(true);
    this.erreurImport.set('');
    try {
      const rapport = await this.animateursApi.analyseCompetencesImport(this.demande());
      if (numero === this.lastAnalysis) {
        this.rapportImport.set(rapport);
      }
    } catch (error) {
      if (numero === this.lastAnalysis) {
        this.rapportImport.set(null);
        this.erreurImport.set(errorMessage(error));
      }
    } finally {
      if (numero === this.lastAnalysis) {
        this.analysisPending.set(false);
      }
    }
  }

  protected async importer(): Promise<void> {
    const rapport = this.rapportImport();
    if (!rapport || !this.peutImporter()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@competences.import.confirmer.titre:Confirmer l'import`,
      message: $localize`:@@competences.import.confirmer.message:Mettre à jour les appréciations de ${rapport.accepted}:acceptees: fiche(s) depuis le fichier ? Rien n'est retiré, les fiches absentes du fichier ne sont pas touchées.`,
    });
    if (!confirme) {
      return;
    }
    this.importPending.set(true);
    this.erreurImport.set('');
    try {
      const applique = await this.animateursApi.applyCompetencesImport(this.demande());
      this.rapportImport.set(applique);
      this.notifications.notify({
        title: $localize`:@@competences.import.succes.titre:Import terminé`,
        message: $localize`:@@competences.import.succes.message:${applique.accepted}:fiches: fiche(s) mise(s) à jour.`,
        variant: 'success',
      });
      await this.recharger(new Set(this.animateursModifies()));
    } catch (error) {
      this.erreurImport.set(errorMessage(error));
    } finally {
      this.importPending.set(false);
    }
  }

  protected reinitialiserImport(): void {
    this.lastAnalysis++;
    this.analysisPending.set(false);
    this.contenu.set('');
    this.fileName.set('');
    this.rapportImport.set(null);
    this.erreurImport.set('');
  }

  protected fermerImport(): void {
    this.reinitialiserImport();
    this.importOuvert.set(false);
  }

  protected classeAction(action: ImportCompetencesAction): string {
    switch (action) {
      case 'UPDATED':
        return 'import-ligne-maj';
      case 'UNCHANGED':
        return 'import-ligne-inchangee';
      default:
        return 'import-ligne-rejet';
    }
  }

  protected iconeAction(action: ImportCompetencesAction): string {
    switch (action) {
      case 'UPDATED':
        return 'edit';
      case 'UNCHANGED':
        return 'check';
      default:
        return 'block';
    }
  }

  private demande(): ImportCompetencesDemande {
    return { fileName: this.fileName(), content: this.contenu() };
  }
}
