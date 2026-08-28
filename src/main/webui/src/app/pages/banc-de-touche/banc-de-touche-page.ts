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
import { ApiService } from '../../core/api.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { BancDeTouche, Creneau } from '../../core/models';
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
    MatTooltipModule
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

  protected readonly creneaux = this.store.creneaux;
  protected readonly stands = this.store.stands;

  protected readonly lignes = computed<LigneBanc[]>(() => lignes(this.banc(), this.store.animateurs()));
  protected readonly standCible = computed(() =>
    libelleStand(this.store.stands(), this.banc()?.standCibleId ?? null)
  );
  /** Which créneaux the saved plan holds a seat on, to mark the others in the selector. */
  protected readonly creneauxUtiles = computed(() => creneauxUtiles(this.banc()));
  private readonly familleUtile = computed(() => familleUtile(this.store.creneaux()));

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

  private async premierChargement(): Promise<void> {
    await this.store.reload(['creneaux', 'stands', 'animateurs']);
    this.defautCreneau();
    await this.charger();
  }

  /**
   * Nothing chosen yet, or a link pointing at a créneau this edition no longer
   * has: fall back to the first one rather than leaving an empty screen with no
   * explanation.
   */
  private defautCreneau(): void {
    const creneaux = this.store.creneaux();
    if (creneaux.length === 0) {
      return;
    }
    const courant = this.creneauId();
    if (courant === null || !creneaux.some((creneau) => creneau.id === courant)) {
      this.creneauId.set(creneaux[0].id);
    }
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

  private async charger(): Promise<void> {
    const creneauId = this.creneauId();
    if (creneauId === null) {
      this.banc.set(null);
      return;
    }
    const standId = this.standId();
    this.chargement.set(true);
    try {
      const query = standId ? `?standId=${encodeURIComponent(standId)}` : '';
      this.banc.set(await this.api.get<BancDeTouche>(`/api/banc-de-touche/${creneauId}${query}`));
      this.erreur.set('');
    } catch (error) {
      this.banc.set(null);
      this.erreur.set(errorPrefix(error));
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * The créneaux carrying no seat stay selectable — the answer for one of them
   * is a legitimate one — but they say so up front, so the user is choosing
   * rather than hunting.
   */
  protected libelleCreneau(creneau: Creneau): string {
    const utiles = this.creneauxUtiles();
    const marque = utiles.size > 0 && !utiles.has(creneau.id)
      ? ' — ' + $localize`:@@bancDeTouche.slotWithoutSeat:aucun siège`
      : '';
    return libelleCreneau(creneau, this.familleUtile()) + marque;
  }

  protected reinitialiser(): void {
    this.standId.set('');
    this.defautCreneau();
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
