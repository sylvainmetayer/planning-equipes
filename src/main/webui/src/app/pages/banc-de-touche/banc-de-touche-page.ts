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
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { WorkInProgressBanner } from '../../shared/work-in-progress-banner';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { BancDeTouche, CreneauSiege } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import {
  creneauxUtiles,
  EtatBanc,
  LigneBanc,
  libelleCreneau,
  libelleStand,
  lignes,
} from './banc-de-touche';

/**
 * « Banc de touche » (issue #303): for one créneau, who is not on duty, and
 * which rule stands between them and a seat.
 *
 * Read-only on purpose — assigning someone from here belongs to the day-J mode
 * (issue #297). And read-only in a stronger sense too: every reason displayed
 * comes from `GET /api/banc-de-touche`, which derives it from the constraints
 * the solver actually enforces. This page words none of them and re-implements
 * none of them; a rule whose threshold changes changes here by itself.
 */
@Component({
  selector: 'app-banc-de-touche-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    WorkInProgressBanner,
  ],
  templateUrl: './banc-de-touche-page.html',
  styleUrl: './banc-de-touche-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BancDeTouchePage {
  /**
   * False when the Diagnostic page hosts this screen as one of its tabs: the
   * page then carries the title, and a second heading would only repeat it.
   */
  readonly entete = input(true);

  private readonly analysesApi = inject(AnalysesApi);
  private readonly store = inject(ReferenceDataStore);
  private readonly route = inject(ActivatedRoute);

  /** The créneau the user chose; null until they do, and the server then picks one. */
  private readonly creneauId = signal<number | null>(null);
  protected readonly standId = signal<string>('');

  protected readonly stands = this.store.stands;

  /**
   * Stands and animateurs come from the référentiel — they name the rows and
   * fill the second selector. The créneaux do not: the answer carries the only
   * ones worth offering. A resource of its own, so a refused référentiel shows
   * as a message and not as a blank card: a `reload()` rejection used to
   * escape into the `void`, the one outcome a screen must never produce.
   */
  private readonly referentiel = resource({
    loader: () => this.store.reload(['stands', 'animateurs']),
  });

  /**
   * The bench, keyed by the two selectors. A resource rather than a method
   * with a request counter: changing créneau then stand quickly fires two
   * calls, and nothing guarantees they come back in order — the resource
   * discards the answer to a request that is no longer the current one, so
   * the table never shows one créneau while the selectors show another.
   *
   * No créneau yet — a cold open, or a bookmark naming one the plan no longer
   * staffs — is a question for the server: it answers on the first créneau it
   * does staff, and says which.
   */
  private readonly bench = resource({
    params: () => ({ creneauId: this.creneauId(), standId: this.standId() }),
    loader: ({ params }) => this.analysesApi.bench(params.creneauId, params.standId),
  });
  // Retained across a re-key: unlike `reload()`, a change of `params` resets
  // the resource's value to undefined for the whole round trip, and the créneau
  // selector is built from the answer — the screen would lose its selectors and
  // its table on every click. Only a refusal clears it.
  private readonly bancRetenu = retainedValue(this.bench);
  protected readonly banc = computed<BancDeTouche | null>(() =>
    this.bench.status() === 'error' ? null : this.bancRetenu(),
  );
  protected readonly chargement = computed(
    () => this.referentiel.isLoading() || this.bench.isLoading(),
  );
  private readonly erreurReferentiel = errorText(this.referentiel);
  private readonly erreurBanc = errorText(this.bench);
  protected readonly erreur = computed(() => this.erreurReferentiel() || this.erreurBanc());

  /**
   * The créneau on screen: the one chosen, or the one the server answered on
   * when none was. Derived rather than written back into `creneauId`, which
   * would re-key the bench and ask the server a second time for what it has
   * just said.
   */
  protected readonly creneauAffiche = computed(() => this.banc()?.creneauId ?? this.creneauId());

  protected readonly lignes = computed<LigneBanc[]>(() =>
    lignes(this.banc(), this.store.animateurs()),
  );
  protected readonly standCible = computed(() =>
    libelleStand(this.store.stands(), this.banc()?.standCibleId ?? null),
  );
  /**
   * The whole of what the créneau selector offers, and it comes from the
   * answer, not from the référentiel: only créneaux the saved plan staffs have
   * anything to show here. That is also why this page never loads the créneau
   * référentiel — on the reference scenario it would be 354 vacations fetched
   * to display a fraction of them.
   */
  protected readonly creneaux = computed(() => creneauxUtiles(this.banc()));

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const creneau = Number(params.get('creneau'));
    this.creneauId.set(Number.isFinite(creneau) && creneau > 0 ? creneau : null);
    this.standId.set(params.get('stand') ?? '');
    keepViewInQueryParams(() => ({
      creneau: this.creneauAffiche() ?? null,
      stand: optionalParam(this.standId()),
    }));
  }

  /** The two selects re-key the bench; the resource does the rest. */
  protected changerCreneau(creneauId: number): void {
    this.creneauId.set(creneauId);
  }

  /**
   * Changing the stand keeps the créneau on screen. After a cold open the
   * chosen créneau is still null — the server picked one — and a request
   * sent with null would let the server pick again: the same créneau today,
   * another one the day a solve published in between staffs an earlier slot.
   * Both signals move in one tick, so the resource sends one request.
   */
  protected changerStand(standId: string): void {
    if (this.creneauId() === null) {
      this.creneauId.set(this.creneauAffiche());
    }
    this.standId.set(standId);
  }

  /**
   * The créneaux carrying no seat stay selectable — the answer for one of them
   * is a legitimate one — but they say so up front, so the user is choosing
   * rather than hunting.
   */
  /**
   * Why this screen still announces itself as on trial. Not the shared
   * default sentence: nothing is entered here and the solver reads nothing back
   * from it — same situation as the fragility screen. What is provisional is
   * the screen itself, and which seat it decides to reason about.
   */
  protected messageEssai(): string {
    return $localize`:@@bancDeTouche.essai:Cet écran est livré à l'essai : il ne modifie rien et pourra être retiré s'il ne s'avère pas utile. Dites-nous s'il vous sert.`;
  }

  /**
   * No « aucun siège » marker any more: the list holds none of those. Marking
   * them was the way out of a selector that offered créneaux the answer could
   * only be empty for — not offering them at all is the better answer.
   */
  protected libelleCreneau(creneau: CreneauSiege): string {
    return libelleCreneau(creneau);
  }

  protected reinitialiser(): void {
    this.standId.set('');
    this.creneauId.set(null);
    // Both already at their defaults re-keys nothing: ask the server again anyway.
    this.bench.reload();
  }

  protected etatLibelle(etat: EtatBanc): string {
    switch (etat) {
      case 'disponible':
        return $localize`:@@bancDeTouche.state.available:Disponible`;
      case 'sousReserve':
        return $localize`:@@bancDeTouche.state.underReserve:Possible, mais casse une règle`;
      default:
        return $localize`:@@bancDeTouche.state.impossible:Impossible`;
    }
  }

  protected etatIcone(etat: EtatBanc): string {
    switch (etat) {
      case 'disponible':
        return 'check_circle';
      case 'sousReserve':
        return 'warning';
      default:
        return 'block';
    }
  }

  /** Tooltip of a reason: the constraint's own catalogue wording, never a second one. */
  protected motifInfobulle(categorie: string | null, description: string | null): string {
    return [categorie, description].filter(Boolean).join(' — ');
  }

  protected siegeLibelle(): string {
    const banc = this.banc();
    if (!banc || !banc.posteCibleId) {
      return '';
    }
    const stand = this.standCible() || banc.standCibleId || '';
    const poste = banc.posteCibleId;
    return $localize`:@@bancDeTouche.seat:Siège évalué : ${poste}:poste: (${stand}:stand:)`;
  }

  /**
   * Why there is nothing to show, when there is nothing to show. Having nothing
   * to say about a créneau is one of this screen's answers, not a failure: the
   * endpoint used to answer 404 for a créneau carrying no seat, which opened
   * the page on « Créneau inconnu » with nothing the user could do about it.
   */
  protected explicationVide(): string {
    const banc = this.banc();
    if (!banc) {
      return '';
    }
    if (banc.statut === 'NO_PLAN') {
      return $localize`:@@bancDeTouche.noPlan:Aucun planning enregistré : lancez une résolution pour que cet écran ait un plan à interroger.`;
    }
    if (banc.statut === 'NO_SEAT') {
      return $localize`:@@bancDeTouche.noSeat:Aucun siège sur ce créneau dans le planning enregistré : aucun stand ouvert, ou créneau créé après la dernière résolution. Choisissez un créneau qui porte des sièges, ou relancez une résolution.`;
    }
    return $localize`:@@bancDeTouche.everyoneOnDuty:Tout le monde est de service sur ce créneau : le banc est vide.`;
  }

  protected occupantLibelle(): string {
    const occupant = this.banc()?.animateurCibleId;
    if (!occupant) {
      return '';
    }
    const animateur = this.store.animateurs().find((candidat) => candidat.id === occupant);
    const nom = animateur ? `${animateur.prenom} ${animateur.nom}` : occupant;
    return $localize`:@@bancDeTouche.seatTaken:Ce siège est tenu par ${nom}:animateur: — la question posée est « qui pourrait le remplacer ? ».`;
  }

  protected resumeLibelle(): string {
    const banc = this.banc();
    if (!banc) {
      return '';
    }
    const disponibles = banc.disponibles;
    const total = banc.total;
    return $localize`:@@bancDeTouche.summary:${disponibles}:available: animateurs disponibles sur ${total}:total: hors service à ce créneau`;
  }
}
