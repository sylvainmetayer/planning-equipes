import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { intlLocale } from '../../core/locale';
import { Animateur, ConfirmationView, StatutConfirmation } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { labelAnimateursPluriel } from '../../core/entity-labels';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import { correspondAuFiltre } from '../../core/text-filter';
import { NO_SORT, keepViewInQueryParams, optionalParam, readSort, sortQueryParams } from '../../core/view-query-params';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DetailData, DetailDialog } from '../../shared/detail-dialog';
import { SortHeaderName } from '../../shared/sort-header-name';
import { TableFilter } from '../../shared/table-filter';
import { AnimateurBulkEditData, AnimateurBulkEditDialog } from './animateur-bulk-edit-dialog';
import { buildAnimateurDetail } from './animateur-detail';
import { AnimateurFormData, AnimateurFormDialog } from './animateur-form-dialog';
import { errorMessage } from '../../core/error-message';

/**
 * Animateurs CRUD. Minor/adult status is never stored: it is derived from the
 * birth date at the date of each timeslot, so only the birth date is edited.
 * Availability is opt-out: an animator works unless a day is listed here.
 *
 * An animateur declared unavailable on a day that carries a CRITIQUE
 * feasibility cause is flagged: that single unavailability is one of the
 * reasons the day cannot be staffed at all.
 *
 * Rows are multi-selectable, for a bulk delete or a bulk edit of the fields
 * animateurs share (appréciation, souhaits, manager, indisponibilités). The
 * appréciation has no column of its own — it is a list per row, unreadable in
 * a cell — and is read in the detail dialog, edited in the form.
 */
@Component({
  selector: 'app-animateurs-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatTableModule,
    MatSortModule,
    MatTooltipModule,
    BulkActionsBar,
    SortHeaderName,
    TableFilter
  ],
  templateUrl: './animateurs-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AnimateursPage {
  protected readonly columns = ['select', 'id', 'nom', 'majorite', 'manager', 'indisponibilites', 'confirmation', 'actions'];
  protected readonly sort = signal<Sort>(NO_SORT);
  /** Quick filter of the table: id, identity and compétences. Applied before the sort. */
  protected readonly filtre = signal('');
  /** True as soon as the table shows something other than the whole referential, unsorted. */
  protected readonly vueModifiee = computed(
    () => this.filtre().trim() !== '' || (this.sort().active !== '' && this.sort().direction !== '')
  );
  protected readonly animateursFiltres = computed(() =>
    this.store
      .animateurs()
      .filter((animateur) =>
        correspondAuFiltre(this.filtre(), [
          animateur.id,
          animateur.prenom,
          animateur.nom,
          ...Object.keys(animateur.competences ?? {}),
          // The acknowledgement label travels with the row so the existing
          // quick filter finds « relancé » or « silencieux » without a control
          // of its own (issue #293).
          this.confirmationLabel(animateur)
        ])
      )
  );

  /**
   * Acknowledgement of the published planning, by animateur id (issue #293).
   * Loaded apart from the roster: an animateur clicking in their espace moves
   * it with nothing happening on the admin side, so it is not part of the
   * reference-data store that only reloads on a CRUD write.
   */
  protected readonly confirmations = signal<Map<string, ConfirmationView>>(new Map());

  protected readonly sortedAnimateurs = computed(() => {
    const animateurs = this.animateursFiltres();
    const { active, direction } = this.sort();
    if (!active || !direction) {
      return animateurs;
    }
    const confirmations = this.confirmations();
    const factor = direction === 'asc' ? 1 : -1;
    return [...animateurs].sort((a, b) => factor * compareByColumn(a, b, active, confirmations));
  });

  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  /** Keyed on the filtered, sorted rows, so "tout sélectionner" follows what the table shows. */
  protected readonly selection = new TableSelection<string>(
    computed(() => this.sortedAnimateurs().map((animateur) => animateur.id))
  );

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Roving tabindex over the rows: the arrows move the focus, Entrée opens the
   * detail, Espace ticks the row. `core/table-navigation.ts` holds the whole
   * mechanism, shared with the other reference-data tables.
   */
  protected readonly navigation = new TableNavigation({
    rows: this.sortedAnimateurs,
    id: (animateur: Animateur) => animateur.id,
    host: () => this.hote.nativeElement,
    selection: this.selection,
    open: (animateur: Animateur) => {
      void this.consult(animateur);
      return true;
    },
    announcer: inject(LiveAnnouncer)
  });

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirmDialog = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);

  /** Copies the animateur's personal espace link (issue #165) — what the PDF prints. */
  protected async copierLienEspace(animateur: Animateur): Promise<void> {
    if (!animateur.accessToken) {
      return;
    }
    const lien = `${window.location.origin}/animateur/${animateur.accessToken}`;
    try {
      await navigator.clipboard.writeText(lien);
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopie:Lien de l'espace animateur copié.`,
        variant: 'success',
        timeout: 4000
      });
    } catch {
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopieEchec:Impossible de copy le lien`,
        message: lien,
        variant: 'warning'
      });
    }
  }

  /** Rotates the espace access token: the link on already-distributed PDFs stops working. */
  protected async regenererJeton(animateur: Animateur): Promise<void> {
    const confirmed = await this.confirmDialog.ask({
      title: $localize`:@@animateurs.regenererJetonTitre:Régénérer le lien de ${animateur.prenom}:prenom: ${animateur.nom}:nom: ?`,
      message: $localize`:@@animateurs.regenererJetonMessage:L'ancien lien (déjà imprimé sur ses plannings PDF) cessera de fonctionner immédiatement.`,
      confirmLabel: $localize`:@@animateurs.regenererJetonConfirm:Régénérer`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    try {
      await this.api.post(`/api/animateurs/${animateur.id}/token`, null);
      await this.store.reload();
      this.notifications.notify({
        title: $localize`:@@animateurs.jetonRegenere:Nouveau lien généré.`,
        variant: 'success',
        timeout: 4000
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error'
      });
    }
  }

  /**
   * Animateur id → the tooltip explaining that one of their unavailability days
   * is a day with a critical shortfall. Memoised as a map so each row is a
   * lookup rather than a scan of every cause.
   */
  protected readonly alerteParAnimateurId = computed<Map<string, string>>(() => {
    const causesParDate = this.problemes.causeCritiqueParDate();
    const alertes = new Map<string, string>();
    if (causesParDate.size === 0) {
      return alertes;
    }
    for (const animateur of this.store.animateurs()) {
      const jour = (animateur.joursIndisponibles ?? []).find((date) => causesParDate.has(date));
      if (jour) {
        alertes.set(animateur.id, this.indisponibiliteCritiqueMessage(jour, causesParDate.get(jour)!.message));
      }
    }
    return alertes;
  });

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.sort.set(readSort(params));
    this.filtre.set(params.get('q') ?? '');
    void this.crud.reload();
    void this.problemes.reloadFeasibility();
    void this.chargerConfirmations();
    keepViewInQueryParams(() => ({ ...sortQueryParams(this.sort()), q: optionalParam(this.filtre()) }));
  }

  /**
   * A missing answer is not an error worth a snack bar: the column then simply
   * shows nothing, and every other feature of the page still works.
   */
  private async chargerConfirmations(): Promise<void> {
    try {
      const confirmations = await this.api.get<ConfirmationView[]>('/api/animateurs/confirmations');
      this.confirmations.set(new Map(confirmations.map((confirmation) => [confirmation.animateurId, confirmation])));
    } catch {
      this.confirmations.set(new Map());
    }
  }

  /**
   * What the three states mean — none of it is guessable from the labels, and
   * two of the rules actively surprise people who assume otherwise.
   *
   * Built in a method, never at module scope: `$localize` only resolves once
   * `main.ts` has loaded the translations.
   */
  protected confirmationAide(): string {
    return $localize`:@@animateurs.confirmation.aide:Ce que l'animateur a répondu au planning qu'on lui a envoyé.\n\n• Confirmé : il a cliqué « J'ai lu et je serai là ». La date s'affiche au survol.\n• Relancé : un message automatique lui a déjà été envoyé. Il n'y en aura pas d'autre — les derniers silencieux sont à reprendre à la main.\n• Silencieux : rien n'est revenu.\n• — : cette personne n'a aucun poste au planning publié. Ce n'est pas un silence, on ne lui a rien demandé.\n\nRepublier ne remet à « silencieux » que les personnes dont le planning a réellement changé : celles dont les journées n'ont pas bougé gardent leur confirmation.`;
  }

  /** Wording of the acknowledgement column, and the text its quick filter matches on. */
  protected confirmationLabel(animateur: Animateur): string {
    const confirmation = this.confirmations().get(animateur.id);
    if (!confirmation || !confirmation.affecte) {
      return '';
    }
    return CONFIRMATION_LABELS[confirmation.statut]();
  }

  /**
   * The timestamp behind the label, as a tooltip: when they confirmed, or —
   * failing that — when the automatic reminder went out. Empty when there is
   * nothing to date, which is exactly the « silencieux » case.
   */
  protected confirmationDate(animateur: Animateur): string | null {
    const confirmation = this.confirmations().get(animateur.id);
    if (confirmation?.confirmeLe) {
      const date = new Date(confirmation.confirmeLe).toLocaleString(intlLocale());
      return $localize`:@@animateurs.confirmation.confirmeLe:Confirmé le ${date}:date:`;
    }
    if (confirmation?.relanceLe) {
      const date = new Date(confirmation.relanceLe).toLocaleString(intlLocale());
      return $localize`:@@animateurs.confirmation.relanceLe:Relancé le ${date}:date:`;
    }
    return null;
  }

  /** Back to the whole referential, in the order the store holds it. */
  protected reinitialiserVue(): void {
    this.filtre.set('');
    this.sort.set(NO_SORT);
  }

  private indisponibiliteCritiqueMessage(jour: string, cause: string): string {
    return $localize`:@@animateurs.alerte.indisponibiliteCritique:Indisponible le ${jour}:date:, un jour où l'effectif est structurellement insuffisant : ${cause}:cause:`;
  }

  protected ouiNon(value: boolean): string {
    return value ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`;
  }

  protected majoriteLabel(animateur: Animateur): string {
    const statut = majorite(animateur);
    if (statut === 'majeur') {
      return $localize`:@@animateurs.majorite.majeur:Oui`;
    }
    if (statut === 'mineur') {
      return $localize`:@@animateurs.majorite.mineur:Non`;
    }
    return '—';
  }

  /**
   * Read-only detail of one row, with an "Modifier" button handing over to the
   * usual form dialog — locked, there as here, while a solve is running.
   */
  protected async consult(animateur: Animateur): Promise<void> {
    const data: DetailData = {
      title: `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id,
      subtitle: animateur.id,
      sections: buildAnimateurDetail(animateur, this.store.typologies())
    };
    const result = await firstValueFrom(
      this.dialog.open(DetailDialog, { data, width: '40rem', maxWidth: '95vw' }).afterClosed()
    );
    if (result === 'edit') {
      this.edit(animateur);
    }
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(animateur: Animateur): void {
    this.openDialog(animateur);
  }

  private openDialog(animateur: Animateur | null): void {
    this.dialog.open<AnimateurFormDialog, AnimateurFormData, boolean>(AnimateurFormDialog, {
      data: { animateur },
      width: '44rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(animateur: Animateur): Promise<void> {
    await this.crud.remove('animateurs', animateur.id, $localize`:@@animateurs.entityLabel:Animateur`);
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany('animateurs', this.selection.selectedIds(), labelAnimateursPluriel());
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<AnimateurBulkEditDialog, AnimateurBulkEditData, boolean>(AnimateurBulkEditDialog, {
      data: { animateurs: this.store.animateurs().filter((animateur) => selectionnes.has(animateur.id)) },
      width: '48rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }
}

/**
 * Called from a method, never at module scope: `$localize` only resolves once
 * `main.ts` has loaded the translations.
 */
const CONFIRMATION_LABELS: Record<StatutConfirmation, () => string> = {
  NON_VU: () => $localize`:@@animateurs.confirmation.nonVu:Silencieux`,
  CONFIRME: () => $localize`:@@animateurs.confirmation.confirme:Confirmé`,
  RELANCE: () => $localize`:@@animateurs.confirmation.relance:Relancé`
};

/**
 * Order of one column, ascending. Every column here is sorted on something the
 * cell actually shows, so the result reads as sorted rather than shuffled — and
 * where the value is not a text, the ranking is chosen to put what still needs
 * doing on top of the ascending order:
 *
 *   - `majorite` and `manager` are booleans: "Oui" first, so the people the
 *     column exists to spot come up on the first click;
 *   - `indisponibilites` is a list, and its cell shows a count, so the count is
 *     what is compared;
 *   - `confirmation` is a status with no natural order: silencieux, then
 *     relancé, then confirmé, and last the people who were asked nothing —
 *     ascending is then "who is left to chase".
 *
 * An unknown column answers 0, which leaves the rows in source order: a link
 * carrying a `?sort=` of a column since removed degrades to an unsorted table
 * (see `core/view-query-params.ts`).
 */
function compareByColumn(
  a: Animateur,
  b: Animateur,
  column: string,
  confirmations: Map<string, ConfirmationView>
): number {
  switch (column) {
    case 'id':
      return compareTexte(a.id, b.id);
    case 'nom':
      // On the string the cell shows, not on the family name: the column reads
      // « Prénom Nom », and sorting on anything else looks broken on screen.
      return compareTexte(nomAffiche(a), nomAffiche(b));
    case 'majorite':
      return rankMajorite(a) - rankMajorite(b);
    case 'manager':
      return rankBooleen(a.manager) - rankBooleen(b.manager);
    case 'indisponibilites':
      return (a.joursIndisponibles?.length ?? 0) - (b.joursIndisponibles?.length ?? 0);
    case 'confirmation':
      return rankConfirmation(a, confirmations) - rankConfirmation(b, confirmations);
    default:
      return 0;
  }
}

/**
 * Numeric-aware and accent-insensitive: ids run A1, A2 … A10, which a plain
 * code-point comparison files as A1, A10, A2 — and « Élodie » must not land
 * after « Zoé ».
 */
function compareTexte(left: string, right: string): number {
  return (left ?? '').localeCompare(right ?? '', intlLocale(), { numeric: true, sensitivity: 'base' });
}

function nomAffiche(animateur: Animateur): string {
  return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim();
}

/** "Oui" first, like {@link rankMajorite}. */
function rankBooleen(valeur: boolean): number {
  return valeur ? 0 : 1;
}

function rankConfirmation(animateur: Animateur, confirmations: Map<string, ConfirmationView>): number {
  const confirmation = confirmations.get(animateur.id);
  if (!confirmation || !confirmation.affecte) {
    // Nothing was asked of them: last, because there is nothing to chase.
    return 3;
  }
  return CONFIRMATION_RANKS[confirmation.statut];
}

const CONFIRMATION_RANKS: Record<StatutConfirmation, number> = {
  NON_VU: 0,
  RELANCE: 1,
  CONFIRME: 2
};

function rankMajorite(animateur: Animateur): number {
  const statut = majorite(animateur);
  if (statut === 'majeur') {
    return 0;
  }
  if (statut === 'mineur') {
    return 1;
  }
  return 2;
}

function majorite(animateur: Animateur): 'majeur' | 'mineur' | 'inconnu' {
  const dateNaissance = animateur.dateNaissance;
  if (!dateNaissance) {
    return 'inconnu';
  }
  const [year, month, day] = dateNaissance.split('-').map((value) => Number(value));
  if (!year || !month || !day) {
    return 'inconnu';
  }
  const now = new Date();
  let age = now.getFullYear() - year;
  if (now.getMonth() + 1 < month || (now.getMonth() + 1 === month && now.getDate() < day)) {
    age -= 1;
  }
  return age >= 18 ? 'majeur' : 'mineur';
}
