import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  DOCUMENT,
  Injector,
  ViewEncapsulation,
  afterNextRender,
  computed,
  effect,
  inject,
  resource,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EditionsApi } from '../../core/api/editions-api';
import { ConsignesStore } from '../../core/consignes.store';
import { errorText, retainedValue } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import {
  LienEtat,
  buildJour,
  buildLignes,
  buildToday,
  isEmptyEdition,
  statutIcon,
  statutLabel,
  summarizeLignes,
} from './accueil';
import { coherenceGroups } from './coherence';
import { bandeLabel, libelleDate } from '../../core/consigne-wording';
import { ScoreReadingPanel } from '../../shared/lecture-score';
import { VoirPlanningButton } from '../../shared/voir-planning-button';
import { MessagesRecents } from './messages-recents';

/** The two places of this page an address may name, the bell's and the night alerts'. */
const ANCHORS = ['a-traiter', 'alertes-nuit'];

/**
 * « État de l'édition », the home screen (issue #485): the cycle of the
 * guide as a checklist, each line with its state, its figures and the screen
 * that moves it. One call, `GET /api/editions/courant/etat`; the states are
 * decided server-side so an assistant reading `etat_edition` sees the same
 * thing as the organiser.
 *
 * Its form follows the phase the server judges on its own today: while
 * preparing, « À traiter aujourd'hui » then the checklist; during the event,
 * the day under way first — the line opening the mode jour J — and the
 * checklist folded; after it, the archive first. An edition with nothing
 * entered gets three ways to start instead of a checklist of empty steps.
 * « À traiter » is always drawn: the toolbar's bell lands on it (`/#a-traiter`),
 * and it carries the recent messages the Notifications page used to hold.
 *
 * The screen only orders and words what it receives. Nothing is computed
 * here, and nothing is written: every action lives on the page each line
 * links to.
 */
@Component({
  selector: 'app-accueil-page',
  imports: [
    VoirPlanningButton,
    ScoreReadingPanel,
    MessagesRecents,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './accueil-page.html',
  styleUrl: './accueil-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccueilPage {
  private readonly editionsApi = inject(EditionsApi);
  private readonly jobs = inject(SolverJobService);
  private readonly consignes = inject(ConsignesStore);

  /** The consignes still to come or in force today (issue #4), worded for the banner; empty when none. */
  protected readonly consignesAVenir = computed(() => {
    const aujourdhui = this.consignes.aujourdhui();
    return this.consignes
      .consignes()
      .filter((consigne) => aujourdhui !== null && consigne.date >= aujourdhui)
      .sort((gauche, droite) => gauche.date.localeCompare(droite.date))
      .map((consigne) => ({
        date: consigne.date,
        libelleDate: libelleDate(consigne.date),
        bande: bandeLabel(consigne.fermetureDebut, consigne.fermetureFin),
        motif: consigne.motif,
      }));
  });

  private readonly etat = resource({ loader: () => this.editionsApi.etat() });
  /** Kept across a failed refresh: the checklist stays on screen behind the failure sentence. */
  protected readonly etatEdition = retainedValue(this.etat);
  protected readonly chargement = this.etat.isLoading;
  protected readonly erreur = errorText(this.etat);

  protected readonly lignes = computed(() => {
    const etat = this.etatEdition();
    return etat ? buildLignes(etat, this.coherenceReport()) : [];
  });
  protected readonly phase = computed(() => this.etatEdition()?.evenement.phase ?? 'PREPARATION');
  /** « Aujourd'hui — J5 · … », during the event only. */
  protected readonly jour = computed(() => {
    const etat = this.etatEdition();
    return etat ? buildJour(etat) : null;
  });
  /** Nothing entered at all: three ways to start rather than twelve steps « à faire ». */
  protected readonly vide = computed(() => {
    const etat = this.etatEdition();
    return etat !== null && isEmptyEdition(etat);
  });
  /** Folded during the event, where the day comes first; open otherwise. */
  private readonly checklistToggled = signal<boolean | null>(null);
  protected readonly checklistOpen = computed(
    () => this.checklistToggled() ?? this.phase() !== 'EVENEMENT',
  );
  protected readonly bilan = computed(() => summarizeLignes(this.lignes()));
  /** « À traiter aujourd'hui »: the subjects with something to say; the box is not drawn when empty. */
  protected readonly today = computed(() => {
    const etat = this.etatEdition();
    return etat ? buildToday(etat) : [];
  });

  /** Whether the coherence line is unfolded. */
  protected readonly coherenceOpen = signal(false);
  /**
   * Read once the line is unfolded, and not before: the counts are in the
   * state already, and the whole checklist of the referential is not worth a
   * second request on every visit of the home screen. Identical anomalies
   * are then merged into one subject, the count of subjects said in the line.
   */
  private readonly coherence = resource({
    params: () => (this.coherenceOpen() ? true : undefined),
    loader: () => this.editionsApi.coherence(),
  });
  protected readonly coherenceReport = retainedValue(this.coherence);
  protected readonly coherenceLoading = this.coherence.isLoading;
  protected readonly coherenceError = errorText(this.coherence);
  protected readonly coherenceGroups = computed(() => {
    const rapport = this.coherenceReport();
    return rapport ? coherenceGroups(rapport) : [];
  });

  protected readonly statutLabel = statutLabel;
  protected readonly statutIcon = statutIcon;

  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly messagesRecents = viewChild(MessagesRecents);
  /** The lines of « À traiter » about the night's alerts unfold the recent messages. */
  protected readonly alertesDemandees = computed(() => this.fragment() === 'alertes-nuit');

  constructor() {
    // `/#a-traiter` from the bell, `/#alertes-nuit` from a line of « À
    // traiter »: the router does not scroll to a fragment here, so the page
    // does, once what it names is drawn.
    effect(() => {
      const anchor = this.fragment();
      if (anchor && ANCHORS.includes(anchor)) {
        afterNextRender(() => this.scrollTo(anchor), { injector: this.injector });
      }
    });
    // A solve started from anywhere (this browser or another) moves three
    // lines at once: refresh once it lands. Unregistered with the page.
    //
    // Both kinds, as the shell and the two other screens already do: an
    // incremental replan rewrites the plan and its score just the same, and
    // listening for `SOLVE` alone left the checklist stale until an F5 —
    // « Corriger après un changement » is precisely what a reader watching this
    // page launches from another tab.
    const destroyRef = inject(DestroyRef);
    for (const type of ['SOLVE', 'SOLVE_INCREMENTAL'] as const) {
      destroyRef.onDestroy(
        this.jobs.onResult(type, () => {
          this.etat.reload();
          // The unfolded checklist of the referential reads the plan's
          // staffing too: shown, it follows the solve; folded, it is read
          // afresh the next time it opens.
          if (this.coherenceOpen()) {
            this.coherence.reload();
          }
        }),
      );
    }
    void this.consignes.reload();
  }

  protected recharger(): void {
    this.etat.reload();
    if (this.coherenceOpen()) {
      this.coherence.reload();
    }
    void this.consignes.reload();
  }

  /**
   * A line of « À traiter » pointing at this very page: when its fragment is
   * already the address's, the router navigates nowhere and the effect above
   * never runs — the page scrolls, and unfolds the alerts, itself.
   */
  protected followOnPage(lien: LienEtat): void {
    const anchor = lien.fragment;
    if (lien.route !== '/' || !anchor || anchor !== this.fragment()) {
      return;
    }
    if (anchor === 'alertes-nuit') {
      this.messagesRecents()?.unfold();
    }
    afterNextRender(() => this.scrollTo(anchor), { injector: this.injector });
  }

  private scrollTo(anchor: string): void {
    this.document.getElementById(anchor)?.scrollIntoView?.({ block: 'start' });
  }

  protected toggleCoherence(): void {
    this.coherenceOpen.update((open) => !open);
  }

  protected toggleChecklist(): void {
    this.checklistToggled.set(!this.checklistOpen());
  }
}
