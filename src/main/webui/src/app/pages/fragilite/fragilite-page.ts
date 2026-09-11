import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
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
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { AnimateurFragilite, CompetenceRare, SeveriteFragilite } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { WorkInProgressBanner } from '../../shared/work-in-progress-banner';
import {
  classeSeverite,
  FiltreFragilite,
  filtrerAnimateurs,
  filtrerCompetences,
  heure,
  iconeSeverite,
  libelleJour,
  lireFiltre,
  lireVue,
  synthese,
  VueFragilite,
} from './fragilite';

/**
 * « Fragilité du planning » : who is a single point of failure, and which stand
 * rests on one competent person.
 *
 * A route of its own rather than a section of `/staffing` or `/problemes`:
 * those answer « combien faut-il recruter » and « pourquoi ce planning ne tient
 * pas », where this one answers « qui est irremplaçable ». Everything shown
 * comes from `GET /api/fragilite`, computed server-side from the persisted plan
 * and the competence referential — no solve is launched, here or there.
 */
@Component({
  selector: 'app-fragilite-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
    WorkInProgressBanner,
  ],
  templateUrl: './fragilite-page.html',
  styleUrl: './fragilite-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FragilitePage {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);

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
  protected readonly competences = computed<CompetenceRare[]>(() =>
    filtrerCompetences(this.rapport(), this.filtre(), this.recherche()),
  );
  /** True as soon as the screen shows something other than its default view. */
  protected readonly viewChanged = computed(
    () =>
      this.view() !== 'ANIMATEURS' || this.filtre() !== 'TOUS' || this.recherche().trim() !== '',
  );

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.view.set(lireVue(params.get('vue')));
    this.filtre.set(lireFiltre(params.get('filtre')));
    this.recherche.set(params.get('q') ?? '');
    keepViewInQueryParams(() => ({
      vue: this.view() === 'ANIMATEURS' ? null : this.view(),
      filtre: this.filtre() === 'TOUS' ? null : this.filtre(),
      q: optionalParam(this.recherche()),
    }));
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

  /**
   * Why this screen sits under « En cours de développement ». Not the shared
   * default sentence: nothing is entered here and the solver reads nothing back
   * from it. What is provisional is the screen itself — it ships to be tried
   * out, and goes away if it earns nothing.
   */
  protected messageEssai(): string {
    return $localize`:@@fragilite.essai:Cet écran est livré à l'essai : il pourra être retiré s'il ne s'avère pas utile. Dites-nous s'il vous sert.`;
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
