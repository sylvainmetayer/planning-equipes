import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { errorMessage } from '../../core/error-message';
import {
  ConfigurationCollecte,
  DeclarationAdminView,
  TeammateRequestView,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';
import { keepViewInQueryParams } from '../../core/view-query-params';
import {
  DisponibilitesTab,
  PENDING,
  oldestFirst,
  readDisponibilitesTab,
  readPendingOnly,
} from './declarations-filter';

/** Longest reason « Écarter » accepts — the server's own bound. */
const REASON_MAX = 500;

/**
 * Admin review of the self-service declarations (issue #291): the collection
 * window on top, then two tabs chosen by `?onglet=` — the declarations of
 * availability, and the covoiturage requests (« Je viens avec… »), sent from
 * their own tab of the espace and decided one by one, never with a
 * declaration.
 *
 * <p>The decision is deliberately <b>all or nothing</b>: there is no way to
 * accept a day and drop another. Applying writes the whole proposal onto the
 * animateur's fiche through the same service the CRUD screen uses, which is
 * what makes the planning show as stale afterwards; refusing changes nothing
 * and sends back a reason the animateur reads in their espace.</p>
 */
@Component({
  selector: 'app-disponibilites-page',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
  ],
  templateUrl: './disponibilites-page.html',
  styleUrls: ['../../../styles/espace-disponibilites.css', '../../../styles/demandes.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DisponibilitesPage implements OnInit {
  private readonly disponibilitesApi = inject(DisponibilitesApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  /**
   * « Appliquer » writes the fiche through the same service as the CRUD
   * screen, which a solve holding the edition refuses in 409: the buttons wait
   * for the solve instead of letting the confirmation find out afterwards.
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly chargement = signal(false);
  protected readonly declarations = signal<DeclarationAdminView[]>([]);
  /** `null` while the configuration has not been fetched yet. */
  protected readonly configuration = signal<ConfigurationCollecte | null>(null);
  protected readonly fenetreEnCours = signal(false);
  protected readonly decisionEnCours = signal<string | null>(null);

  /** Form state of the window, edited before being sent in one go. */
  protected readonly debut = signal('');
  protected readonly fin = signal('');
  /**
   * Ticked per opening, never stored: the invitation is indispensable on the
   * first round and merely tiresome when the window is reopened after a
   * correction.
   */
  protected readonly prevenir = signal(false);

  /**
   * « En attente seulement »: the declarations waiting for a decision — the
   * ones « À traiter aujourd'hui » counts — the oldest first, and the ones
   * already decided out of sight. `?statut=en-attente`, which is what the
   * home screen links to; unticking it is the reset.
   */
  protected readonly pendingOnly = signal(false);

  protected readonly pending = computed(() => {
    const pending = this.declarations().filter(
      (declaration) => declaration.statut === 'EN_ATTENTE',
    );
    return this.pendingOnly() ? oldestFirst(pending) : pending;
  });
  protected readonly decidees = computed(() =>
    this.declarations().filter((declaration) => declaration.statut !== 'EN_ATTENTE'),
  );

  /** Which tab is on screen; the declarations, the default, write nothing to the URL. */
  protected readonly tab = signal<DisponibilitesTab>('declarations');

  /** « Je viens avec… »: the covoiturage requests, sent and decided apart from the declarations. */
  protected readonly carpools = signal<TeammateRequestView[]>([]);
  protected readonly pendingCarpools = computed(() =>
    this.carpools().filter((carpool) => carpool.status === 'EN_ATTENTE'),
  );
  protected readonly decidedCarpools = computed(() =>
    this.carpools().filter((carpool) => carpool.status !== 'EN_ATTENTE'),
  );
  /**
   * The demands that carry « Annuler l'arrivée groupée »: one per validated
   * grouped arrival — the most recent of the demands validated against it —
   * since cancelling one cancels them all.
   */
  protected readonly cancellableCarpoolIds = computed(() => {
    const byGroup = new Map<string, string>();
    for (const carpool of this.carpools()) {
      if (
        carpool.status === 'VALIDEE' &&
        carpool.contrainteId &&
        !byGroup.has(carpool.contrainteId)
      ) {
        byGroup.set(carpool.contrainteId, carpool.id);
      }
    }
    return new Set(byGroup.values());
  });

  constructor() {
    const route = inject(ActivatedRoute);
    this.pendingOnly.set(readPendingOnly(route.snapshot.queryParamMap.get('statut')));
    // Followed rather than read once, like Paramètres: a link to this very
    // route with another `onglet` reuses the component.
    route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.tab.set(readDisponibilitesTab(params.get('onglet')));
    });
    keepViewInQueryParams(() => ({
      onglet: this.tab() === 'declarations' ? null : this.tab(),
      statut: this.pendingOnly() ? PENDING : null,
    }));
  }

  protected changeTab(tab: DisponibilitesTab): void {
    this.tab.set(tab);
  }

  ngOnInit(): void {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.chargement.set(true);
    try {
      // The covoiturages are a tab of their own, read beside: without them
      // the declarations still read.
      void this.loadCarpools();
      const [declarations, configuration] = await Promise.all([
        this.disponibilitesApi.declarations(),
        this.disponibilitesApi.configuration(),
      ]);
      this.declarations.set(declarations);
      this.configuration.set(configuration);
      this.debut.set(configuration.debut ?? '');
      this.fin.set(configuration.fin ?? '');
    } catch (error) {
      this.report(error);
    } finally {
      this.chargement.set(false);
    }
  }

  private async loadCarpools(): Promise<void> {
    try {
      const carpools = await this.disponibilitesApi.carpools();
      this.carpools.set(Array.isArray(carpools) ? carpools : []);
    } catch {
      this.carpools.set([]);
    }
  }

  /**
   * Opens or closes the window. Closing is enforced server-side: the espaces
   * refuse a declaration, they do not merely hide the form.
   */
  protected async saveWindow(open: boolean): Promise<void> {
    this.fenetreEnCours.set(true);
    try {
      const reponse = await this.disponibilitesApi.saveConfiguration({
        collecteOuverte: open,
        debut: this.debut() || null,
        fin: this.fin() || null,
        prevenirAnimateurs: open && this.prevenir(),
      });
      this.configuration.set(reponse);
      this.prevenir.set(false);
      this.notifications.notify({
        title: reponse.collecteOuverte
          ? $localize`:@@dispo.ouverteNotif:Collecte ouverte : les animateurs peuvent déclarer leurs disponibilités.`
          : $localize`:@@dispo.fermeeNotif:Collecte fermée : les espaces animateurs n'acceptent plus de déclaration.`,
        variant: 'success',
      });
      if (reponse.invitation) {
        this.notifications.notify({
          title: $localize`:@@dispo.invitationNotif:${reponse.invitation.envoyes}:envoyes: invitation(s) envoyée(s).`,
          message:
            reponse.invitation.sansEmail.length + reponse.invitation.echecs.length === 0
              ? undefined
              : $localize`:@@dispo.invitationRestes:Sans adresse : ${reponse.invitation.sansEmail.join(', ')}:sansEmail:. Échecs : ${reponse.invitation.echecs.join(', ')}:echecs:.`,
          variant: reponse.invitation.echecs.length > 0 ? 'error' : 'success',
          timeout: 8000,
        });
      }
    } catch (error) {
      this.report(error);
      // Re-read the truth rather than guessing what the toggle should show.
      void this.reload();
    } finally {
      this.fenetreEnCours.set(false);
    }
  }

  protected async apply(declaration: DeclarationAdminView): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@dispo.appliquerTitre:Appliquer la déclaration de ${declaration.animateurNom}:animateur: ?`,
      message: $localize`:@@dispo.appliquerMessage:${declaration.joursIndisponibles.length}:jours: jour(s) d'indisponibilité et ${declaration.souhaitsLabels.length}:souhaits: souhait(s) remplaceront le contenu de sa fiche. Le planning enregistré devra être régénéré.`,
      confirmLabel: $localize`:@@dispo.appliquerConfirm:Appliquer`,
    });
    if (!confirmed || this.solveStartedMeanwhile()) {
      return;
    }
    await this.decider(
      declaration,
      'application',
      null,
      $localize`:@@dispo.appliquee:Déclaration appliquée. Régénérez le planning depuis la page Solveur pour qu'il en tienne compte.`,
    );
  }

  protected async refuser(declaration: DeclarationAdminView): Promise<void> {
    const commentaire = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@dispo.refuserTitre:Refuser la déclaration de ${declaration.animateurNom}:animateur:`,
      label: $localize`:@@dispo.refuserLabel:Motif du refus (lu par l'animateur dans son espace)`,
      confirmLabel: $localize`:@@dispo.refuserConfirm:Refuser`,
    });
    if (commentaire === null || this.solveStartedMeanwhile()) {
      return;
    }
    await this.decider(
      declaration,
      'refus',
      commentaire,
      $localize`:@@dispo.refusee:Déclaration refusée, les données restent inchangées.`,
    );
  }

  private async decider(
    declaration: DeclarationAdminView,
    action: 'application' | 'refus',
    commentaire: string | null,
    confirmation: string,
  ): Promise<void> {
    this.decisionEnCours.set(declaration.id);
    try {
      await this.disponibilitesApi.decide(declaration.id, action, commentaire);
      this.notifications.notify({ title: confirmation, variant: 'success', timeout: 5000 });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  /**
   * « Valider l'arrivée groupée »: creates the `ARRIVEE_GROUPEE` exception
   * naming the car's members. Refused, with the server's sentence, when two of
   * them are declared incompatible.
   */
  protected async validateCarpool(carpool: TeammateRequestView): Promise<void> {
    const membres = this.membersLabel(carpool);
    const confirmed = await this.confirm.ask({
      title: $localize`:@@dispo.covoiturage.validerTitre:Valider l'arrivée groupée de ${membres}:membres: ?`,
      message: $localize`:@@dispo.covoiturage.validerMessage:Un ajustement « arrivée groupée » sera créé : mêmes jours, arrivées et départs à la tolérance près. Il s'annule ensuite depuis cet onglet.`,
      confirmLabel: $localize`:@@dispo.covoiturage.valider:Valider l'arrivée groupée`,
    });
    if (!confirmed || this.solveStartedMeanwhile()) {
      return;
    }
    this.decisionEnCours.set(carpool.id);
    try {
      const reponse = await this.disponibilitesApi.validateCarpool(carpool.id);
      this.notifications.notify({
        title: $localize`:@@dispo.covoiturage.validee:Arrivée groupée créée, ses membres sont prévenus. Régénérez le planning pour qu'il en tienne compte.`,
        message:
          (reponse.avertissements ?? []).map((avertissement) => avertissement.message).join(' ') ||
          undefined,
        variant: (reponse.avertissements ?? []).length > 0 ? 'warning' : 'success',
        timeout: 8000,
      });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  /**
   * « Écarter »: the request is filed without any effect, with an optional
   * reason the animateur reads in their espace and in the mail telling them.
   * Nothing is written to the referential, so a running solve does not block it.
   */
  protected async setCarpoolAside(carpool: TeammateRequestView): Promise<void> {
    const reason = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@dispo.covoiturage.ecarterTitre:Écarter le covoiturage de ${this.membersLabel(carpool)}:membres: ?`,
      message: $localize`:@@dispo.covoiturage.ecarterMessage:Aucun ajustement n'est créé. Le demandeur est prévenu par e-mail et peut envoyer une nouvelle demande tant que la collecte est ouverte.`,
      label: $localize`:@@dispo.covoiturage.ecarterMotif:Motif (facultatif, lu par l'animateur dans son espace)`,
      confirmLabel: $localize`:@@dispo.covoiturage.ecarter:Écarter`,
      optional: true,
      maxLength: REASON_MAX,
    });
    if (reason === null) {
      return;
    }
    this.decisionEnCours.set(carpool.id);
    try {
      await this.disponibilitesApi.setCarpoolAside(carpool.id, reason || null);
      this.notifications.notify({
        title: $localize`:@@dispo.covoiturage.ecartee:Covoiturage écarté, rien n'a été créé. Le demandeur est prévenu.`,
        variant: 'success',
        timeout: 5000,
      });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  /**
   * « Annuler l'arrivée groupée »: deletes the exception a validation created
   * or joined, files every demand validated against it as cancelled with the
   * optional reason, and tells each member by mail. The one way to undo a
   * validated car: the Ajustements manuels screen refuses to touch it.
   */
  protected async cancelCarpool(carpool: TeammateRequestView): Promise<void> {
    const reason = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@dispo.covoiturage.annulerTitre:Annuler l'arrivée groupée de ${this.membersLabel(carpool)}:membres: ?`,
      message: $localize`:@@dispo.covoiturage.annulerMessage:L'ajustement « arrivée groupée » est supprimé. Chaque membre est prévenu par e-mail ; une nouvelle demande reste possible tant que la collecte est ouverte.`,
      label: $localize`:@@dispo.covoiturage.annulerMotif:Motif (facultatif, lu par les membres dans leur espace)`,
      confirmLabel: $localize`:@@dispo.covoiturage.annuler:Annuler l'arrivée groupée`,
      optional: true,
      maxLength: REASON_MAX,
    });
    if (reason === null || this.solveStartedMeanwhile()) {
      return;
    }
    this.decisionEnCours.set(carpool.id);
    try {
      await this.disponibilitesApi.cancelCarpool(carpool.id, reason || null);
      this.notifications.notify({
        title: $localize`:@@dispo.covoiturage.annulee:Arrivée groupée annulée, ses membres sont prévenus. Régénérez le planning pour qu'il en tienne compte.`,
        variant: 'success',
        timeout: 8000,
      });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  protected membersLabel(carpool: TeammateRequestView): string {
    return carpool.members.map((membre) => membre.fullName).join(', ');
  }

  protected carpoolStatusLabel(carpool: TeammateRequestView): string {
    switch (carpool.status) {
      case 'VALIDEE':
        return $localize`:@@dispo.covoiturage.statut.validee:Arrivée groupée validée`;
      case 'ANNULEE':
        return $localize`:@@dispo.covoiturage.statut.annulee:Annulée`;
      default:
        return $localize`:@@dispo.covoiturage.statut.ecartee:Écarté`;
    }
  }

  /**
   * A solve took the edition while the confirmation was open: said before
   * anything is sent, rather than by the 409 that would follow. A solve that
   * ended meanwhile lets the decision go through as usual.
   */
  private solveStartedMeanwhile(): boolean {
    if (!this.editingLocked()) {
      return false;
    }
    this.notifications.notify({
      title: $localize`:@@dispo.verrouDemarre:Une résolution vient de démarrer : rien n'a été envoyé. Décidez à la fin du calcul.`,
      variant: 'warning',
      timeout: 8000,
    });
    return true;
  }

  protected statutLabel(declaration: DeclarationAdminView): string {
    switch (declaration.statut) {
      case 'APPLIQUEE':
        return $localize`:@@dispo.statut.appliquee:Appliquée`;
      case 'REFUSEE':
        return $localize`:@@dispo.statut.refusee:Refusée`;
      default:
        return $localize`:@@dispo.statut.enAttente:En attente`;
    }
  }

  private report(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}
