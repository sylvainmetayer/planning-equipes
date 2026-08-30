import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
import { ApiService } from '../../core/api.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { BancDeTouche, CreneauSiege } from '../../core/models';
import { errorPrefix } from '../../core/error-message';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import {
  creneauxUtiles,
  EtatBanc,
  familleUtile,
  LigneBanc,
  libelleCreneau,
  libelleStand,
  lignes
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
    WorkInProgressBanner
  ],
  templateUrl: './banc-de-touche-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BancDeTouchePage {
  private readonly api = inject(ApiService);
  private readonly store = inject(ReferenceDataStore);
  private readonly route = inject(ActivatedRoute);

  protected readonly creneauId = signal<number | null>(null);
  protected readonly standId = signal<string>('');
  protected readonly banc = signal<BancDeTouche | null>(null);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal('');

  protected readonly stands = this.store.stands;

  protected readonly lignes = computed<LigneBanc[]>(() => lignes(this.banc(), this.store.animateurs()));
  protected readonly standCible = computed(() =>
    libelleStand(this.store.stands(), this.banc()?.standCibleId ?? null)
  );
  /**
   * The whole of what the créneau selector offers, and it comes from the
   * answer, not from the référentiel: only créneaux the saved plan staffs have
   * anything to show here. That is also why this page never loads the créneau
   * référentiel — on the reference scenario it would be 354 vacations fetched
   * to display a fraction of them.
   */
  protected readonly creneaux = computed(() => creneauxUtiles(this.banc()));
  private readonly familleUtile = computed(() => familleUtile(this.creneaux()));

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const creneau = Number(params.get('creneau'));
    this.creneauId.set(Number.isFinite(creneau) && creneau > 0 ? creneau : null);
    this.standId.set(params.get('stand') ?? '');
    keepViewInQueryParams(() => ({
      creneau: this.creneauId() ?? null,
      stand: optionalParam(this.standId())
    }));
    void this.premierChargement();
  }

  /**
   * Stands and animateurs come from the référentiel — they name the rows and
   * fill the second selector. The créneaux do not: the answer carries the only
   * ones worth offering, so on a cold open the server is asked to pick, and the
   * screen adopts whichever créneau it answered on.
   *
   * A `reload()` rejection used to escape into the `void`, leaving the card
   * blank with no message at all, which is the one outcome a screen must never
   * produce — hence the same `catch` as the bench request.
   */
  private async premierChargement(): Promise<void> {
    try {
      await this.store.reload(['stands', 'animateurs']);
    } catch (error) {
      this.erreur.set(errorPrefix(error));
      return;
    }
    await this.charger();
  }

  /**
   * Fetching is driven by the two selects rather than by an `effect()` on their
   * signals: an effect would also fire on the default créneau this component
   * sets itself, and the order of the two requests would then depend on which
   * write landed first.
   */
  protected changerCreneau(creneauId: number): void {
    this.creneauId.set(creneauId);
    void this.charger();
  }

  protected changerStand(standId: string): void {
    this.standId.set(standId);
    void this.charger();
  }

  /**
   * Which request is allowed to write the screen. Changing créneau then stand
   * quickly fires two calls, and nothing guarantees they come back in order:
   * without this, the older answer could land last and leave the table showing
   * one créneau while the selectors show another. The same counter keeps the
   * progress bar up until the *current* request is done, instead of dropping it
   * on the first answer to arrive.
   */
  private requeteCourante = 0;

  private async charger(): Promise<void> {
    const requete = ++this.requeteCourante;
    const creneauId = this.creneauId();
    const standId = this.standId();
    this.chargement.set(true);
    try {
      // No créneau yet — a cold open, or a bookmark naming one the plan no
      // longer staffs — is a question for the server: it answers on the first
      // créneau it does staff, and says which.
      const chemin = creneauId === null ? '/api/banc-de-touche' : `/api/banc-de-touche/${creneauId}`;
      const query = standId ? `?standId=${encodeURIComponent(standId)}` : '';
      const banc = await this.api.get<BancDeTouche>(`${chemin}${query}`);
      if (requete !== this.requeteCourante) {
        return;
      }
      this.banc.set(banc);
      this.creneauId.set(banc.creneauId);
      this.erreur.set('');
    } catch (error) {
      if (requete !== this.requeteCourante) {
        return;
      }
      this.banc.set(null);
      this.erreur.set(errorPrefix(error));
    } finally {
      if (requete === this.requeteCourante) {
        this.chargement.set(false);
      }
    }
  }

  /**
   * The créneaux carrying no seat stay selectable — the answer for one of them
   * is a legitimate one — but they say so up front, so the user is choosing
   * rather than hunting.
   */
  /**
   * Why this screen sits under « En cours de développement ». Not the shared
   * default sentence: nothing is entered here and the solver reads nothing back
   * from it — same situation as the fragility screen. What is provisional is
   * the screen itself, and which seat it decides to reason about.
   */
  protected messageEssai(): string {
    return $localize`:@@bancDeTouche.essai:Cet écran est livré à l'essai : il ne modifie rien et pourra être retiré s'il ne s'avère pas utile. Ce qu'il affiche est en revanche exact, puisque chaque motif vient des contraintes elles-mêmes — dites-nous s'il vous sert.`;
  }

  /**
   * No « aucun siège » marker any more: the list holds none of those. Marking
   * them was the way out of a selector that offered créneaux the answer could
   * only be empty for — not offering them at all is the better answer.
   */
  protected libelleCreneau(creneau: CreneauSiege): string {
    return libelleCreneau(creneau, this.familleUtile());
  }

  protected reinitialiser(): void {
    this.standId.set('');
    this.creneauId.set(null);
    void this.charger();
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
      return $localize`:@@bancDeTouche.noSeat:Aucun siège sur ce créneau dans le planning enregistré : aucun stand n'y est ouvert, ou le créneau a été créé après la dernière résolution. Choisissez un créneau qui porte des sièges, ou relancez une résolution.`;
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
