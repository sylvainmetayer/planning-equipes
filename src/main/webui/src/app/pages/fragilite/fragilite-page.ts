import {
  computed,
  inject,
  input,
  resource,
  signal,
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { AnimateurFragilite, CompetenceRare, SeveriteFragilite } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText, retainedValue } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { typologieLabels } from '../../core/typologie-colors';
import { VerrouillageStore } from '../../core/verrouillage.store';
import {
  keepViewInQueryParams,
  optionalParam,
  currentViewParams,
} from '../../core/view-query-params';
import {
  classeSeverite,
  FiltreFragilite,
  filtrerAnimateurs,
  filtrerCompetences,
  heure,
  iconeSeverite,
  libelleJour,
  LienFragilite,
  lireFiltre,
  readView,
  replacementLink,
  synthese,
  trainingLink,
  typologiesAffichees,
  VueFragilite,
} from './fragilite';
import { StatusMessage } from '../../shared/status-message';

/**
 * « Fragilité du planning » : who is a single point of failure, and which stand
 * rests on one competent person.
 *
 * A route of its own rather than a section of `/staffing` or `/problemes`:
 * those answer « combien faut-il recruter » and « pourquoi ce planning ne tient
 * pas », where this one answers « qui est irremplaçable ». Everything shown
 * comes from `GET /api/fragilite`, computed server-side from the persisted plan
 * and the competence referential — no solve is launched, here or there.
 *
 * Each person carries the three gestures that answer their fragility:
 * « Verrouiller » keeps their schedule as it stands at the next solve,
 * « Qui peut remplacer » opens the Siège panel on the seat they would leave
 * hardest to fill, « Former » the competences grid on the game categories of
 * their stands. Their name leads to their fiche, and nowhere else.
 */
@Component({
  selector: 'app-fragilite-page',
  imports: [
    StatusMessage,
    FormsModule,
    MatButtonModule,
    RouterLink,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './fragilite-page.html',
  styleUrl: './fragilite-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FragilitePage {
  /**
   * False when the Diagnostic page hosts this screen as one of its tabs: the
   * page then carries the title, and a second heading would only repeat it.
   */
  readonly entete = input(true);

  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);
  private readonly referentiel = inject(ReferenceDataStore);
  private readonly verrous = inject(VerrouillageStore);
  /** A solve holding the edition refuses the lock: « Verrouiller » waits for it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  private readonly fragilite = resource({ loader: () => this.analysesApi.fragility() });
  /** Kept across a failed refresh; the template shows the failure in its place, not a blank card. */
  protected readonly rapport = retainedValue(this.fragilite);
  protected readonly chargement = this.fragilite.isLoading;
  protected readonly erreur = errorText(this.fragilite);
  protected readonly view = signal<VueFragilite>('ANIMATEURS');
  protected readonly filtre = signal<FiltreFragilite>('TOUS');
  protected readonly recherche = signal('');
  /** Animateur whose detailed seats are unfolded; only one at a time. */
  protected readonly ouvert = signal<string | null>(null);

  protected readonly synthese = computed(() => {
    const rapport = this.rapport();
    return rapport ? synthese(rapport) : null;
  });
  protected readonly animateurs = computed<AnimateurFragilite[]>(() =>
    filtrerAnimateurs(this.rapport(), this.filtre(), this.recherche()),
  );
  /** Typologie id → label: the report names typologies by id, which means nothing on screen. */
  private readonly typologies = computed(() => typologieLabels(this.referentiel.typologies()));
  protected readonly competences = computed<CompetenceRare[]>(() =>
    filtrerCompetences(this.rapport(), this.filtre(), this.recherche(), this.typologies()),
  );
  /** Stand id → the game categories it offers, for « Former ». */
  private readonly standTypologies = computed(
    () =>
      new Map(
        this.referentiel.stands().map((stand) => [stand.id, stand.typologiesProposees ?? []]),
      ),
  );
  /** Animateur id → where their two navigations lead, computed once per report rather than per render. */
  protected readonly gestes = computed(
    () =>
      new Map<string, { remplacer: LienFragilite | null; former: LienFragilite }>(
        this.animateurs().map((ligne) => [
          ligne.animateurId,
          {
            remplacer: replacementLink(ligne),
            former: trainingLink(ligne, this.standTypologies()),
          },
        ]),
      ),
  );
  /** A lock in flight: one at a time. */
  protected readonly locking = signal(false);
  /** What the last « Verrouiller » did, or why it could not. */
  protected readonly lockMessage = signal('');
  protected readonly lockError = signal('');

  /** True as soon as the screen shows something other than its default view. */
  protected readonly viewChanged = computed(
    () =>
      this.view() !== 'ANIMATEURS' || this.filtre() !== 'TOUS' || this.recherche().trim() !== '',
  );

  constructor() {
    // The address bar, not the router snapshot: this page is a tab of
    // « Diagnostic », created afresh every time the tab is opened, and the
    // snapshot still holds what the last real navigation parsed.
    // The labels only: a failure leaves the ids on screen, never the report blank.
    void this.referentiel.reload(['typologies', 'stands']).catch(() => undefined);
    // Who is locked already: their « Verrouiller » says so instead.
    void this.verrous.reload().catch(() => undefined);
    const params = currentViewParams();
    this.view.set(readView(params.get('vue')));
    this.filtre.set(lireFiltre(params.get('filtre')));
    this.recherche.set(params.get('q') ?? '');
    keepViewInQueryParams(() => ({
      vue: this.view() === 'ANIMATEURS' ? null : this.view(),
      filtre: this.filtre() === 'TOUS' ? null : this.filtre(),
      q: optionalParam(this.recherche()),
    }));
  }

  protected typologiesAffichees(ligne: CompetenceRare): string {
    return typologiesAffichees(ligne, this.typologies());
  }

  protected recharger(): void {
    this.fragilite.reload();
  }

  protected reinitialiser(): void {
    this.view.set('ANIMATEURS');
    this.filtre.set('TOUS');
    this.recherche.set('');
  }

  protected basculer(animateurId: string): void {
    this.ouvert.set(this.ouvert() === animateurId ? null : animateurId);
  }

  protected readonly classeSeverite = classeSeverite;
  protected readonly iconeSeverite = iconeSeverite;
  protected readonly libelleJour = libelleJour;
  protected readonly heure = heure;

  /** Four rows of « Verrouiller » alone would be indistinguishable: the button names whom. */
  protected lockLabel(ligne: AnimateurFragilite): string {
    const nom = ligne.nom;
    return $localize`:@@fragilite.geste.verrouiller.label:Verrouiller le planning de ${nom}:nom:`;
  }

  protected isLocked(animateurId: string): boolean {
    return this.verrous.estAnimateurVerrouille(animateurId);
  }

  /**
   * « Verrouiller » : this person's whole schedule kept as it stands at the
   * next solve — the one gesture that protects an irreplaceable person from
   * being moved elsewhere by a solve chasing a better score.
   */
  protected async lock(ligne: AnimateurFragilite): Promise<void> {
    if (this.locking()) {
      return;
    }
    this.locking.set(true);
    this.lockMessage.set('');
    this.lockError.set('');
    try {
      await this.verrous.create({ type: 'ANIMATEUR', animateurId: ligne.animateurId });
      const nom = ligne.nom;
      this.lockMessage.set(
        $localize`:@@fragilite.verrouille:Le planning de ${nom}:nom: est verrouillé : le prochain calcul le gardera tel quel.`,
      );
    } catch (error) {
      this.lockError.set(errorPrefix(error));
    } finally {
      this.locking.set(false);
    }
  }

  protected libelleSeverite(severite: SeveriteFragilite): string {
    switch (severite) {
      case 'CRITIQUE':
        return $localize`:@@fragilite.severite.critique:Critique`;
      case 'ELEVEE':
        return $localize`:@@fragilite.severite.elevee:Élevée`;
      case 'MODEREE':
        return $localize`:@@fragilite.severite.moderee:Modérée`;
    }
  }

  /** What a scarcity row means, spelled out in the tooltip of its severity. */
  protected explicationCompetence(ligne: CompetenceRare): string {
    if (ligne.specialistes === 0) {
      return ligne.renforts > 0
        ? $localize`:@@fragilite.competence.aucunSpecialisteRenfort:Aucun spécialiste de ce stand n'est disponible ; seuls ${ligne.renforts}:renforts: ninjas pourraient le tenir.`
        : $localize`:@@fragilite.competence.aucunSpecialiste:Personne n'est compétent pour ce stand sur ce créneau.`;
    }
    return ligne.renforts > 0
      ? $localize`:@@fragilite.competence.unSpecialisteRenfort:Une seule personne compétente, épaulée par ${ligne.renforts}:renforts: ninjas.`
      : $localize`:@@fragilite.competence.unSpecialiste:Une seule personne compétente, et aucun ninja pour la remplacer.`;
  }
}
