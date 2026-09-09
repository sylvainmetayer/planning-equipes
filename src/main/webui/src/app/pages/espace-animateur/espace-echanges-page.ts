import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { statutDemandeClasse, statutDemandeLabel } from '../../core/demande-echange-labels';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import {
  DemandeEchangeView,
  NatureEchange,
  PosteAnimateurView,
  SuggestionEchangeView,
  SuggestionsEchangeView
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { LegalText } from '../../shared/legal-text';
import {
  BrouillonDemande,
  ajouterBrouillon,
  brouillonComplet,
  retirerBrouillon,
  versNouvellesDemandes
} from './echange-brouillon';
import { errorMessage } from '../../core/error-message';

interface DemandeRow extends DemandeEchangeView {
  statutLabel: string;
  statutClasse: string;
}

/**
 * The échange request form of the espace animateur (issue #165): the
 * animateur lists the créneaux they want to trade (poste + colleague + motif),
 * then submits the whole list at once. When they have nobody in mind — they
 * simply do not want that créneau — « qui peut me remplacer ? » searches every
 * viable way out (being freed, swapping on that same créneau, or trading it
 * against a colleague's seat on another day) and fills the form from the one
 * they pick. Each demande is prevalidated
 * server-side against the hard constraints; an infeasible one is still
 * submitted, but flagged here in business words. Below, the history of their
 * demandes with statut and the admin's comment.
 */
@Component({
  selector: 'app-espace-echanges-page',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    LegalText
  ],
  templateUrl: './espace-echanges-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspaceEchangesPage {
  protected readonly espace = inject(EspaceAnimateurService);
  private readonly notifications = inject(NotificationService);

  protected readonly posteChoisi = signal<PosteAnimateurView | null>(null);
  protected readonly cibleId = signal('');
  /**
   * Directed exchange: the colleague's seat the demandeur wants in return —
   * null keeps the historical "same créneau" semantics. Options are the
   * colleague's real seats, loaded when the colleague is picked.
   */
  protected readonly posteCibleChoisi = signal<PosteAnimateurView | null>(null);
  protected readonly postesCollegue = signal<PosteAnimateurView[]>([]);
  protected readonly motif = signal('');
  /**
   * Answer of « qui peut me remplacer ? » for the currently picked seat, or
   * null while nothing was searched — cleared whenever that seat changes, so a
   * stale list can never be read as an answer about another créneau.
   */
  protected readonly suggestions = signal<SuggestionsEchangeView | null>(null);
  protected readonly rechercheEnCours = signal(false);
  protected readonly brouillons = signal<BrouillonDemande[]>([]);
  protected readonly envoiEnCours = signal(false);

  protected readonly formulaireComplet = computed(() =>
    brouillonComplet(this.posteChoisi(), this.cibleId())
  );

  /** True when the search stopped at its ceiling: the list is the best of what was tried, not everyone. */
  protected readonly suggestionsTronquees = computed(() => this.suggestions()?.listeTronquee ?? false);

  /** Closed foire = read-only history: no submission form, no withdrawals. */
  protected readonly foireOpen = computed(() => this.espace.view()?.foireOuverte ?? true);

  /**
   * The day the foire opens, when it is shut only because it has not started
   * yet — `null` when it is open, or shut for good.
   *
   * The server computes it, deliberately: it holds the bounds and the clock,
   * and « pas encore ouverte » must not depend on the date of the browser that
   * happens to be reading.
   */
  protected readonly ouvertureAVenir = computed(() => this.espace.view()?.foireOuvreLe ?? null);

  /** Last day demandes are accepted, `null` when the window has no end. */
  protected readonly foireClosesOn = computed(() => this.espace.view()?.foireFermeLe ?? null);

  protected readonly demandes = computed<DemandeRow[]>(() =>
    this.espace.demandes().map((demande) => ({
      ...demande,
      statutLabel: statutDemandeLabel(demande.statut),
      statutClasse: statutDemandeClasse(demande.statut)
    }))
  );

  /** Demandes targeting me and still waiting for MY agreement — the actionable ones. */
  protected readonly recuesEnAttente = computed<DemandeRow[]>(() =>
    this.espace.demandesRecues()
      .filter((demande) => demande.statut === 'EN_ATTENTE_CIBLE')
      .map((demande) => ({
        ...demande,
        statutLabel: statutDemandeLabel(demande.statut),
        statutClasse: statutDemandeClasse(demande.statut)
      }))
  );

  protected async accorder(demande: DemandeRow): Promise<void> {
    try {
      await this.espace.accorderRecue(demande.id);
      this.notifications.notify({
        title: $localize`:@@espace.recues.accordee:Votre accord est transmis : l'organisation tranchera.`,
        variant: 'success',
        timeout: 5000
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error'
      });
    }
  }

  protected async decliner(demande: DemandeRow): Promise<void> {
    try {
      await this.espace.declinerRecue(demande.id);
      this.notifications.notify({
        title: $localize`:@@espace.recues.declinee:Demande déclinée — votre collègue en est informé.`,
        variant: 'success',
        timeout: 5000
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error'
      });
    }
  }

  /** Seat picked: the previous search answered about another créneau, so it goes. */
  protected choisirPoste(poste: PosteAnimateurView | null): void {
    this.posteChoisi.set(poste);
    this.suggestions.set(null);
  }

  /**
   * Searches the colleagues this seat could really be traded with. Read-only:
   * it proposes names, it does not create anything — the animateur still adds
   * the demande and submits it, and the colleague still has to agree.
   */
  protected async chercherRemplacants(): Promise<void> {
    const poste = this.posteChoisi();
    if (!poste || this.rechercheEnCours()) {
      return;
    }
    this.rechercheEnCours.set(true);
    this.suggestions.set(null);
    try {
      this.suggestions.set(await this.espace.suggestionsEchange(poste.creneauId, poste.standId));
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error'
      });
    } finally {
      this.rechercheEnCours.set(false);
    }
  }

  /**
   * One suggestion retained: it fills the ordinary form and stops there — the
   * animateur still adds it to the list and submits. A DIRIGE also preselects
   * the colleague's seat wanted in return, picked from the very options the
   * "créneau souhaité" select holds, so the two stay one and the same choice.
   */
  protected async retenirSuggestion(suggestion: SuggestionEchangeView): Promise<void> {
    await this.choisirCible(suggestion.animateurId);
    if (suggestion.nature !== 'DIRIGE') {
      return;
    }
    this.posteCibleChoisi.set(
      this.postesCollegue().find(
        (poste) =>
          poste.creneauId === suggestion.creneauCibleId && poste.standId === suggestion.standCibleId
      ) ?? null
    );
  }

  /** The retained suggestions of one family, in the order the server ranked them. */
  protected suggestionsDe(nature: NatureEchange): SuggestionEchangeView[] {
    return (this.suggestions()?.suggestions ?? []).filter(
      (suggestion) => suggestion.nature === nature
    );
  }

  /** Colleague picked: load their seats so the optional "wanted in return" select has real options. */
  protected async choisirCible(cibleId: string): Promise<void> {
    this.cibleId.set(cibleId);
    this.posteCibleChoisi.set(null);
    this.postesCollegue.set([]);
    if (!cibleId) {
      return;
    }
    try {
      this.postesCollegue.set(await this.espace.postesCollegue(cibleId));
    } catch {
      // No seats loadable (no persisted planning, network...): the picker
      // simply stays empty and the demande falls back to the plain semantics.
    }
  }

  protected ajouter(): void {
    const poste = this.posteChoisi();
    if (!poste || !this.formulaireComplet()) {
      return;
    }
    const target = this.espace.view()?.collegues.find((collegue) => collegue.id === this.cibleId());
    const posteCible = this.posteCibleChoisi();
    this.brouillons.set(
      ajouterBrouillon(this.brouillons(), {
        creneauId: poste.creneauId,
        standId: poste.standId,
        cibleId: this.cibleId(),
        motif: this.motif() || null,
        creneauCibleId: posteCible?.creneauId ?? null,
        standCibleId: posteCible?.standId ?? null,
        creneauLabel: `${poste.date ?? ''} ${poste.heureDebut}–${poste.heureFin}`.trim(),
        standNom: poste.standNom,
        cibleNom: target?.nomComplet ?? this.cibleId(),
        creneauCibleLabel: posteCible
          ? `${posteCible.date ?? ''} ${posteCible.heureDebut}–${posteCible.heureFin} · ${posteCible.standNom}`.trim()
          : null
      })
    );
    this.posteChoisi.set(null);
    this.cibleId.set('');
    this.posteCibleChoisi.set(null);
    this.postesCollegue.set([]);
    this.motif.set('');
    this.suggestions.set(null);
  }

  protected retirer(index: number): void {
    this.brouillons.set(retirerBrouillon(this.brouillons(), index));
  }

  protected async soumettre(): Promise<void> {
    if (this.brouillons().length === 0 || this.envoiEnCours()) {
      return;
    }
    this.envoiEnCours.set(true);
    try {
      const soumises = await this.espace.soumettre(versNouvellesDemandes(this.brouillons()));
      this.brouillons.set([]);
      const infaisables = soumises.filter((demande) => demande.prevalidationOk === false).length;
      if (infaisables > 0) {
        this.notifications.notify({
          title: $localize`:@@espace.echanges.soumisAvecAlerte:Demandes envoyées — attention`,
          message: $localize`:@@espace.echanges.soumisAvecAlerteDetail:${infaisables}:count: demande(s) ne semblent pas réalisables en l'état du planning (voir le détail ci-dessous). Elles ont tout de même été transmises pour arbitrage.`,
          variant: 'warning'
        });
      } else {
        this.notifications.notify({
          title: $localize`:@@espace.echanges.soumis:Vos demandes ont été transmises à l'organisation.`,
          variant: 'success',
          timeout: 5000
        });
      }
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error'
      });
    } finally {
      this.envoiEnCours.set(false);
    }
  }

  protected async annuler(demande: DemandeRow): Promise<void> {
    try {
      await this.espace.annuler(demande.id);
      this.notifications.notify({
        title: $localize`:@@espace.echanges.annulee:Demande annulée.`,
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
}
