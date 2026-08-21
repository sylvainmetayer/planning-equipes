import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiService } from '../../core/api.service';
import { statutDemandeClasse, statutDemandeLabel } from '../../core/demande-echange-labels';
import { DemandeEchangeView, EchangeSimulation, HardMediumSoftScore } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { formatDeltaScore } from '../../core/score-format';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';
import { errorMessage } from '../../core/error-message';

interface DemandeRow extends DemandeEchangeView {
  statutLabel: string;
  statutClasse: string;
}

/**
 * Admin review of the demandes d'échange (issue #165). For each pending
 * demande: its fresh impact against the current persisted planning (score
 * delta, hard constraints newly broken, croisé or simple takeover), then two
 * explicit decisions — accept (applies the swap exactly as simulated and pins
 * both animateurs on the créneau) or refuse (with a comment sent back to the
 * animateur). Nothing is ever applied without one of these clicks; relaunching
 * the solver afterwards stays a separate, deliberate action on the solver page.
 */
@Component({
  selector: 'app-echanges-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule, MatSlideToggleModule],
  templateUrl: './echanges-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EchangesPage {
  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  protected readonly chargement = signal(false);
  protected readonly demandes = signal<DemandeEchangeView[]>([]);
  /** Fresh simulations, keyed by demande id — loaded on demand, one at a time. */
  protected readonly impacts = signal<Record<string, EchangeSimulation>>({});
  protected readonly impactEnCours = signal<string | null>(null);
  protected readonly decisionEnCours = signal<string | null>(null);
  /** `null` while the configuration has not been fetched yet. */
  protected readonly foireOuverte = signal<boolean | null>(null);
  protected readonly foireEnCours = signal(false);

  protected readonly rows = computed<DemandeRow[]>(() =>
    this.demandes().map((demande) => ({
      ...demande,
      statutLabel: statutDemandeLabel(demande.statut),
      statutClasse: statutDemandeClasse(demande.statut)
    }))
  );

  /** Actionable queue: the colleague already agreed, only the admin's word is missing. */
  protected readonly enAttente = computed(() => this.rows().filter((row) => row.statut === 'PROPOSEE'));
  /** Still waiting for the targeted colleague: informative — refusable, but not acceptable yet. */
  protected readonly enAttenteCible = computed(() =>
    this.rows().filter((row) => row.statut === 'EN_ATTENTE_CIBLE'));
  protected readonly decidees = computed(() =>
    this.rows().filter((row) => row.statut !== 'PROPOSEE' && row.statut !== 'EN_ATTENTE_CIBLE'));

  constructor() {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.chargement.set(true);
    try {
      const [demandes, configuration] = await Promise.all([
        this.api.get<DemandeEchangeView[]>('/api/echanges'),
        this.api.get<{ foireOuverte: boolean }>('/api/echanges/configuration')
      ]);
      this.demandes.set(demandes);
      this.foireOuverte.set(configuration.foireOuverte);
    } catch (error) {
      this.report(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * Opens or closes the foire. Enforced server-side: closed, the espaces
   * animateurs turn read-only (planning still consultable and downloadable).
   */
  protected async basculerFoire(ouverte: boolean): Promise<void> {
    this.foireEnCours.set(true);
    try {
      const configuration = await this.api.put<{ foireOuverte: boolean }>('/api/echanges/configuration', {
        foireOuverte: ouverte
      });
      this.foireOuverte.set(configuration.foireOuverte);
      this.notifications.notify({
        title: configuration.foireOuverte
          ? $localize`:@@echanges.foireOuverteNotif:Foire au planning ouverte : les animateurs peuvent proposer des échanges.`
          : $localize`:@@echanges.foireFermeeNotif:Foire au planning fermée : les espaces animateurs passent en consultation seule.`,
        variant: 'success'
      });
    } catch (error) {
      this.report(error);
      // Re-read the truth rather than guessing what the toggle should show.
      void this.reload();
    } finally {
      this.foireEnCours.set(false);
    }
  }

  protected impactDe(demande: DemandeRow): EchangeSimulation | null {
    return this.impacts()[demande.id] ?? null;
  }

  /** The delta is a score object — rendered through the shared formatter, never interpolated raw. */
  protected formatDelta(delta: HardMediumSoftScore): string {
    return formatDeltaScore(delta);
  }

  protected async chargerImpact(demande: DemandeRow): Promise<void> {
    this.impactEnCours.set(demande.id);
    try {
      const impact = await this.api.get<EchangeSimulation>(`/api/echanges/${demande.id}/impact`);
      this.impacts.set({ ...this.impacts(), [demande.id]: impact });
    } catch (error) {
      this.report(error);
    } finally {
      this.impactEnCours.set(null);
    }
  }

  protected async accepter(demande: DemandeRow): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@echanges.accepterTitre:Accepter l'échange de ${demande.demandeurNom}:demandeur: ?`,
      message: $localize`:@@echanges.accepterMessage:L'échange avec ${demande.cibleNom}:cible: sera appliqué immédiatement au planning et verrouillé sur ce créneau. La demande ne pourra plus être refusée ensuite.`,
      confirmLabel: $localize`:@@echanges.accepterConfirm:Accepter`
    });
    if (!confirmed) {
      return;
    }
    await this.decider(demande, 'acceptation', null,
      $localize`:@@echanges.acceptee:Échange appliqué au planning et verrouillé. Régénérez le planning depuis la page Solveur pour que le reste du planning en tienne compte.`);
  }

  protected async refuser(demande: DemandeRow): Promise<void> {
    const commentaire = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@echanges.refuserTitre:Refuser la demande de ${demande.demandeurNom}:demandeur:`,
      label: $localize`:@@echanges.refuserLabel:Motif du refus (transmis à l'animateur)`,
      confirmLabel: $localize`:@@echanges.refuserConfirm:Refuser`
    });
    if (commentaire === null) {
      return;
    }
    await this.decider(demande, 'refus', commentaire,
      $localize`:@@echanges.refusee:Demande refusée, le planning reste inchangé.`);
  }

  private async decider(demande: DemandeRow, action: 'acceptation' | 'refus', commentaire: string | null,
      confirmation: string): Promise<void> {
    this.decisionEnCours.set(demande.id);
    try {
      await this.api.post<DemandeEchangeView>(`/api/echanges/${demande.id}/${action}`, { commentaire });
      this.notifications.notify({ title: confirmation, variant: 'success', timeout: 5000 });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  private report(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error'
    });
  }
}
