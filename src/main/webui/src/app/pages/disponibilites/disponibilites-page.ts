import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { errorMessage } from '../../core/error-message';
import { ConfigurationCollecte, DeclarationAdminView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';

/**
 * Admin review of the self-service declarations (issue #291): the collection
 * window on top, the proposals waiting below.
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
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule
  ],
  templateUrl: './disponibilites-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DisponibilitesPage {
  private readonly disponibilitesApi = inject(DisponibilitesApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

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

  protected readonly enAttente = computed(() =>
    this.declarations().filter((declaration) => declaration.statut === 'EN_ATTENTE')
  );
  protected readonly decidees = computed(() =>
    this.declarations().filter((declaration) => declaration.statut !== 'EN_ATTENTE')
  );

  constructor() {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.chargement.set(true);
    try {
      const [declarations, configuration] = await Promise.all([
        this.disponibilitesApi.declarations(),
        this.disponibilitesApi.configuration()
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
        prevenirAnimateurs: open && this.prevenir()
      });
      this.configuration.set(reponse);
      this.prevenir.set(false);
      this.notifications.notify({
        title: reponse.collecteOuverte
          ? $localize`:@@dispo.ouverteNotif:Collecte ouverte : les animateurs peuvent déclarer leurs disponibilités.`
          : $localize`:@@dispo.fermeeNotif:Collecte fermée : les espaces animateurs n'acceptent plus de déclaration.`,
        variant: 'success'
      });
      if (reponse.invitation) {
        this.notifications.notify({
          title: $localize`:@@dispo.invitationNotif:${reponse.invitation.envoyes}:envoyes: invitation(s) envoyée(s).`,
          message:
            reponse.invitation.sansEmail.length + reponse.invitation.echecs.length === 0
              ? undefined
              : $localize`:@@dispo.invitationRestes:Sans adresse : ${reponse.invitation.sansEmail.join(', ')}:sansEmail:. Échecs : ${reponse.invitation.echecs.join(', ')}:echecs:.`,
          variant: reponse.invitation.echecs.length > 0 ? 'error' : 'success',
          timeout: 8000
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
      message: $localize`:@@dispo.appliquerMessage:Sa fiche dira désormais ce qu'il a déclaré : ${declaration.joursIndisponibles.length}:jours: jour(s) d'indisponibilité et ${declaration.souhaitsLabels.length}:souhaits: souhait(s) remplaceront ce qu'elle contient. Le planning enregistré devra être régénéré.`,
      confirmLabel: $localize`:@@dispo.appliquerConfirm:Appliquer`
    });
    if (!confirmed) {
      return;
    }
    await this.decider(
      declaration,
      'application',
      null,
      $localize`:@@dispo.appliquee:Déclaration appliquée. Régénérez le planning depuis la page Solveur pour qu'il en tienne compte.`
    );
  }

  protected async refuser(declaration: DeclarationAdminView): Promise<void> {
    const commentaire = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@dispo.refuserTitre:Refuser la déclaration de ${declaration.animateurNom}:animateur:`,
      label: $localize`:@@dispo.refuserLabel:Motif du refus (lu par l'animateur dans son espace)`,
      confirmLabel: $localize`:@@dispo.refuserConfirm:Refuser`
    });
    if (commentaire === null) {
      return;
    }
    await this.decider(
      declaration,
      'refus',
      commentaire,
      $localize`:@@dispo.refusee:Déclaration refusée, les données restent inchangées.`
    );
  }

  private async decider(
    declaration: DeclarationAdminView,
    action: 'application' | 'refus',
    commentaire: string | null,
    confirmation: string
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
      variant: 'error'
    });
  }
}
