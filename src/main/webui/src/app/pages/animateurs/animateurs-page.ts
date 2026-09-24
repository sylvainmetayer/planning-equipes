import { LiveAnnouncer } from '@angular/cdk/a11y';
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
import { firstValueFrom } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { intlLocale } from '../../core/locale';
import {
  Animateur,
  ConfirmationView,
  StatutConfirmation,
  SyntheseConfirmations,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { labelAnimateursPluriel } from '../../core/entity-labels';
import { ProblemesStore } from '../../core/problemes.store';
import { libelleDernierePublication } from '../../core/publication';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableNavigation } from '../../core/table-navigation';
import { TableSelection } from '../../core/table-selection';
import { correspondAuFiltre } from '../../core/text-filter';
import {
  NO_SORT,
  consumeQueryParam,
  keepViewInQueryParams,
  optionalParam,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';
import { SESSION_DRAFT_STORAGE } from '../../core/brouillon-formulaire';
import { reportOrphanDrafts } from '../../shared/brouillon-dialog';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DetailData, DetailDialog } from '../../shared/detail-dialog';
import { SortHeaderName } from '../../shared/sort-header-name';
import { TableFilter } from '../../shared/table-filter';
import { AnimateurBulkEditData, AnimateurBulkEditDialog } from './animateur-bulk-edit-dialog';
import { buildAnimateurDetail } from './animateur-detail';
import { AnimateurFormData, AnimateurFormDialog } from './animateur-form-dialog';
import { errorMessage } from '../../core/error-message';
import {
  ModeAccuses,
  SILENCE_JOURS_DEFAUT,
  readModeAccuses,
  readNeverReminded,
  keptByAcknowledgement,
} from './confirmation-filter';
import { resumeRelance } from './relance-resume';

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
 *
 * The acknowledgement column (issue #293) grew two URL-borne filters and one
 * bulk action (issue #504): « jamais confirmés », « silencieux depuis N
 * jours », and « Relancer maintenant », which mails the selected people the
 * same reminder the night would — once per publication, whichever hand.
 */
@Component({
  selector: 'app-animateurs-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatChipsModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    FormsModule,
    MatTableModule,
    MatSortModule,
    MatTooltipModule,
    BulkActionsBar,
    SortHeaderName,
    TableFilter,
  ],
  templateUrl: './animateurs-page.html',
  styleUrl: './animateurs-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnimateursPage {
  protected readonly columns = [
    'select',
    'id',
    'nom',
    'majorite',
    'manager',
    'indisponibilites',
    'confirmation',
    'actions',
  ];
  protected readonly sort = signal<Sort>(NO_SORT);
  /** Quick filter of the table: id, identity and compétences. Applied before the sort. */
  protected readonly filtre = signal('');
  /** The « Accusés » select: everybody, the never-confirmed, or the silent for N days (issue #504). */
  protected readonly accuses = signal<ModeAccuses>('tous');
  /** N of « silencieux depuis N jours »; kept, and in the URL, only while that mode is on. */
  protected readonly silenceJours = signal(SILENCE_JOURS_DEFAUT);
  /**
   * « Jamais relancés », on top of an acknowledgement mode: only the people no
   * reminder reached — the list the home screen's « silencieux à relancer »
   * counts. In the URL (`relance=jamais`) only while a mode other than
   * « Tous » is on, since it narrows that mode and nothing else.
   */
  protected readonly neverReminded = signal(false);
  /** True as soon as the table shows something other than the whole referential, unsorted. */
  /**
   * Typologie ids, comma-separated, the list is narrowed to: only the
   * animateurs holding an appreciation on one of them. Set by the links that
   * name a game category nobody masters (the staffing bottleneck, a scarce
   * competence of the fragility screen) — the `typologie` query param.
   */
  protected readonly typologie = signal('');
  protected readonly typologiesFiltrees = computed(() =>
    this.typologie()
      .split(',')
      .map((id) => id.trim())
      .filter((id) => id !== ''),
  );
  protected readonly typologieLabel = computed(() => {
    const ids = this.typologiesFiltrees();
    const libelles = this.store.typologies();
    return ids
      .map((id) => libelles.find((typologie) => typologie.id === id)?.label ?? id)
      .join(', ');
  });
  protected readonly viewChanged = computed(
    () =>
      this.filtre().trim() !== '' ||
      this.accuses() !== 'tous' ||
      this.typologiesFiltrees().length > 0 ||
      (this.sort().active !== '' && this.sort().direction !== ''),
  );
  protected readonly animateursFiltres = computed(() => {
    const mode = this.accuses();
    const jours = this.silenceJours();
    const neverReminded = this.neverReminded();
    const confirmations = this.confirmations();
    const lastPublishedAt = this.synthese()?.dernierePublicationLe ?? null;
    const maintenant = new Date();
    return this.store.animateurs().filter(
      (animateur) =>
        keptByAcknowledgement(
          mode,
          jours,
          confirmations.get(animateur.id),
          lastPublishedAt,
          maintenant,
          neverReminded,
        ) &&
        this.matchesTypologieFilter(animateur) &&
        correspondAuFiltre(this.filtre(), [
          animateur.id,
          animateur.prenom,
          animateur.nom,
          ...Object.keys(animateur.competences ?? {}),
          // The acknowledgement label travels with the row so the existing
          // quick filter finds « relancé » or « silencieux » without a control
          // of its own (issue #293).
          this.confirmationLabel(animateur),
        ]),
    );
  });

  /**
   * Acknowledgement of the published planning, by animateur id (issue #293).
   * Loaded apart from the roster: an animateur clicking in their espace moves
   * it with nothing happening on the admin side, so it is not part of the
   * reference-data store that only reloads on a CRUD write.
   */
  protected readonly confirmations = signal<Map<string, ConfirmationView>>(new Map());

  /** The same answers in three numbers, for the head of the page; `null` until read, or when unreadable. */
  protected readonly synthese = signal<SyntheseConfirmations | null>(null);

  /** « Confirmés 12 · Relancés 3 · Silencieux 5 — Dernière publication le … », or nothing to say yet. */
  protected readonly syntheseLabel = computed(() => {
    const synthese = this.synthese();
    if (!synthese) {
      return '';
    }
    const publication = libelleDernierePublication(synthese, intlLocale());
    if (synthese.jamaisPublie) {
      return publication;
    }
    return $localize`:@@animateurs.synthese:Confirmés ${synthese.confirmes}:confirmes: · Relancés ${synthese.relances}:relances: · Silencieux ${synthese.silencieux}:silencieux: — ${publication}:publication:`;
  });

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
    computed(() => this.sortedAnimateurs().map((animateur) => animateur.id)),
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
    announcer: inject(LiveAnnouncer),
  });

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly animateursApi = inject(AnimateursApi);
  private readonly notifications = inject(NotificationService);
  private readonly draftStorage = inject(SESSION_DRAFT_STORAGE);
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
        timeout: 4000,
      });
    } catch {
      this.notifications.notify({
        title: $localize`:@@animateurs.lienCopieEchec:Impossible de copier le lien`,
        message: lien,
        variant: 'warning',
      });
    }
  }

  /** Rotates the espace access token: the link on already-distributed PDFs stops working. */
  protected async regenererJeton(animateur: Animateur): Promise<void> {
    const confirmed = await this.confirmDialog.ask({
      title: $localize`:@@animateurs.regenererJetonTitre:Régénérer le lien de ${animateur.prenom}:prenom: ${animateur.nom}:nom: ?`,
      message: $localize`:@@animateurs.regenererJetonMessage:L'ancien lien (déjà imprimé sur ses plannings PDF) cessera de fonctionner immédiatement.`,
      confirmLabel: $localize`:@@animateurs.regenererJetonConfirm:Régénérer`,
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    try {
      await this.animateursApi.regenerateToken(animateur.id);
      await this.store.reload();
      this.notifications.notify({
        title: $localize`:@@animateurs.jetonRegenere:Nouveau lien généré.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
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
        alertes.set(
          animateur.id,
          this.indisponibiliteCritiqueMessage(jour, causesParDate.get(jour)!.message),
        );
      }
    }
    return alertes;
  });

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.sort.set(readSort(params));
    this.filtre.set(params.get('q') ?? '');
    const accuses = readModeAccuses(params.get('confirmation'), params.get('silence'));
    this.accuses.set(accuses.mode);
    this.silenceJours.set(accuses.jours);
    this.neverReminded.set(readNeverReminded(params.get('relance')));
    this.typologie.set(params.get('typologie') ?? '');
    const chargement = this.crud.reload();
    void chargement.then((loaded) => {
      if (loaded) {
        reportOrphanDrafts(
          this.notifications,
          this.draftStorage,
          'animateur',
          (id) => this.store.animateurs().some((animateur) => animateur.id === id),
          (ids) => $localize`:@@animateurs.brouillon.orphelin:L'animateur ${ids}:ids:`,
        );
      }
    });
    void this.problemes.reloadFeasibility();
    void this.chargerConfirmations();
    keepViewInQueryParams(() => ({
      ...sortQueryParams(this.sort()),
      q: optionalParam(this.filtre()),
      confirmation: this.accuses() === 'jamais' ? 'jamais' : null,
      silence: this.accuses() === 'silence' ? String(this.silenceJours()) : null,
      relance: this.accuses() !== 'tous' && this.neverReminded() ? 'jamais' : null,
      typologie: optionalParam(this.typologie()),
    }));
    // `?edit=<id>`: a link from a symptom (a problem, a warning) lands here
    // with the fiche to open. Followed rather than read once — the link often
    // points at this very screen — then dropped, so a reload does not reopen it.
    consumeQueryParam('edit', async (edit) => {
      await chargement;
      const animateur = this.store.animateurs().find((candidat) => candidat.id === edit);
      if (animateur) {
        this.openDialog(animateur);
      }
    });
  }

  /**
   * A missing answer is not an error worth a snack bar: the column then simply
   * shows nothing, and every other feature of the page still works.
   */
  private async chargerConfirmations(): Promise<void> {
    try {
      const [confirmations, synthese] = await Promise.all([
        this.animateursApi.confirmations(),
        this.animateursApi.syntheseConfirmations(),
      ]);
      this.confirmations.set(
        new Map(confirmations.map((confirmation) => [confirmation.animateurId, confirmation])),
      );
      this.synthese.set(synthese);
    } catch {
      this.confirmations.set(new Map());
      this.synthese.set(null);
    }
  }

  /** A blank or non-positive N keeps the last one: the control never empties the filter. */
  protected changerSilenceJours(valeur: number | string | null): void {
    const jours = Number(valeur);
    if (Number.isInteger(jours) && jours > 0) {
      this.silenceJours.set(jours);
    }
  }

  /**
   * Writes the value actually in force back into the field when it is left.
   * A rejected entry leaves the signal untouched, so Angular has nothing to
   * push back through the one-way binding: « 0 » stayed on screen while the
   * table and the URL went on filtering on the last good number. Done on blur
   * rather than on every keystroke, so clearing the field to type another
   * number still works.
   */
  protected restoreSilenceJours(champ: HTMLInputElement): void {
    const actual = String(this.silenceJours());
    if (champ.value !== actual) {
      champ.value = actual;
    }
  }

  /**
   * « Relancer maintenant » (issue #504): the same reminder the night sends,
   * to the ticked rows, after a confirmation that says mails will leave. The
   * report names who was left alone and why; the answers are reloaded so the
   * column shows « Relancé » at once.
   */
  protected async remindSelection(): Promise<void> {
    const ids = this.selection.selectedIds();
    const confirmed = await this.confirmDialog.ask({
      title: $localize`:@@animateurs.relancer.titre:Relancer ${ids.length}:count: animateur(s) maintenant ?`,
      message: $localize`:@@animateurs.relancer.message:Chacun recevra un e-mail lui demandant de confirmer son planning. Personne n'est relancé deux fois pour une même publication.`,
      confirmLabel: $localize`:@@animateurs.relancer.confirm:Envoyer`,
    });
    if (!confirmed) {
      return;
    }
    try {
      const rapport = await this.animateursApi.remind(ids);
      const noms = new Map(this.store.animateurs().map((each) => [each.id, nomAffiche(each)]));
      const resume = resumeRelance(rapport, (id) => noms.get(id) || id);
      this.notifications.notify({
        title: resume.titre,
        message: resume.details ?? '',
        // Names on screen, counts in the journal: that log is persisted and
        // read back, and this report can name the whole roster.
        messageJournal: resume.detailsJournal ?? '',
        variant: resume.variant,
      });
      this.selection.clear();
      await this.chargerConfirmations();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
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
    return $localize`:@@animateurs.confirmation.aide:Ce que l'animateur a répondu.\n• Relancé : un seul rappel, de nuit ou à la main.\n• — : aucun poste au planning publié.\nRepublier ne remet à « silencieux » que ceux dont le planning a changé.`;
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

  /** The chip's cross: the list widens back to everyone, the rest of the view untouched. */
  protected clearTypologie(): void {
    this.typologie.set('');
  }

  /** Back to the whole referential, in the order the store holds it. */
  protected resetView(): void {
    this.filtre.set('');
    this.typologie.set('');
    this.sort.set(NO_SORT);
    this.accuses.set('tous');
    this.silenceJours.set(SILENCE_JOURS_DEFAUT);
    this.neverReminded.set(false);
  }

  private matchesTypologieFilter(animateur: Animateur): boolean {
    const ids = this.typologiesFiltrees();
    return ids.length === 0 || ids.some((id) => id in (animateur.competences ?? {}));
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
      sections: buildAnimateurDetail(animateur, this.store.typologies()),
    };
    const result = await firstValueFrom(
      this.dialog.open(DetailDialog, { data, width: '40rem', maxWidth: '95vw' }).afterClosed(),
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
      autoFocus: 'first-tabbable',
    });
  }

  protected async remove(animateur: Animateur): Promise<void> {
    await this.crud.remove(
      'animateurs',
      animateur.id,
      $localize`:@@animateurs.entityLabel:Animateur`,
    );
  }

  protected async removeSelection(): Promise<void> {
    await this.crud.removeMany(
      'animateurs',
      this.selection.selectedIds(),
      labelAnimateursPluriel(),
    );
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<AnimateurBulkEditDialog, AnimateurBulkEditData, boolean>(
      AnimateurBulkEditDialog,
      {
        data: {
          animateurs: this.store.animateurs().filter((animateur) => selectionnes.has(animateur.id)),
        },
        width: '48rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
  }
}

/**
 * Called from a method, never at module scope: `$localize` only resolves once
 * `main.ts` has loaded the translations.
 */
const CONFIRMATION_LABELS: Record<StatutConfirmation, () => string> = {
  NON_VU: () => $localize`:@@animateurs.confirmation.nonVu:Silencieux`,
  CONFIRME: () => $localize`:@@animateurs.confirmation.confirme:Confirmé`,
  RELANCE: () => $localize`:@@animateurs.confirmation.relance:Relancé`,
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
  confirmations: Map<string, ConfirmationView>,
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
  return (left ?? '').localeCompare(right ?? '', intlLocale(), {
    numeric: true,
    sensitivity: 'base',
  });
}

function nomAffiche(animateur: Animateur): string {
  return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim();
}

/** "Oui" first, like {@link rankMajorite}. */
function rankBooleen(valeur: boolean): number {
  return valeur ? 0 : 1;
}

function rankConfirmation(
  animateur: Animateur,
  confirmations: Map<string, ConfirmationView>,
): number {
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
  CONFIRME: 2,
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
