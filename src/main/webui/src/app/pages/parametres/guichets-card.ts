import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { DisponibilitesApi } from '../../core/api/disponibilites-api';
import { EchangesApi } from '../../core/api/echanges-api';
import { errorPrefix } from '../../core/error-message';
import { ConfigurationCollecte, ConfigurationFoire } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * « Guichets » — what the animateurs can do from their espace, and when: the
 * collection of availabilities and wishes, the foire au planning, the
 * covoiturage. Each opens and closes here, with its dates (issue #720); the
 * Échanges and Disponibilités screens keep a one-line state of theirs and the
 * decisions, not the configuration.
 *
 * <p>The covoiturage has no switch of its own: « Je viens avec… » is asked for
 * on the collection's own window, so it opens and closes with it. The card
 * says so rather than drawing a switch that would do nothing.</p>
 */
@Component({
  selector: 'app-guichets-card',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './guichets-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuichetsCard implements OnInit {
  private readonly disponibilitesApi = inject(DisponibilitesApi);
  private readonly echangesApi = inject(EchangesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly error = signal('');

  /* ------------------------------- Collecte --------------------------------- */

  protected readonly collecte = signal<ConfigurationCollecte | null>(null);
  protected readonly collectionOpen = signal(false);
  protected readonly collecteDebut = signal('');
  protected readonly collecteFin = signal('');
  /**
   * Ticked per opening, never stored: the invitation is indispensable on the
   * first round and merely tiresome when the window is reopened after a
   * correction.
   */
  protected readonly prevenir = signal(false);
  protected readonly collectionSaving = signal(false);

  /* -------------------------------- Foire ----------------------------------- */

  protected readonly foire = signal<ConfigurationFoire | null>(null);
  protected readonly fairOpen = signal(false);
  protected readonly foireDebut = signal('');
  protected readonly foireFin = signal('');
  protected readonly fairSaving = signal(false);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.error.set('');
    try {
      const [collecte, foire] = await Promise.all([
        this.disponibilitesApi.configuration(),
        this.echangesApi.configuration(),
      ]);
      this.applyCollecte(collecte);
      this.applyFoire(foire);
    } catch (error) {
      this.error.set(errorPrefix(error));
    }
  }

  private applyCollecte(configuration: ConfigurationCollecte): void {
    this.collecte.set(configuration);
    this.collectionOpen.set(configuration.collecteOuverte);
    this.collecteDebut.set(configuration.debut ?? '');
    this.collecteFin.set(configuration.fin ?? '');
  }

  private applyFoire(configuration: ConfigurationFoire): void {
    this.foire.set(configuration);
    this.fairOpen.set(configuration.foireOuverte);
    this.foireDebut.set(configuration.debut ?? '');
    this.foireFin.set(configuration.fin ?? '');
  }

  /**
   * Writes the collection's switch and dates. Closing is enforced
   * server-side: the espaces refuse a declaration, they do not merely hide
   * the form. Opening may mail every animateur their espace link.
   */
  protected async saveCollecte(): Promise<void> {
    this.collectionSaving.set(true);
    this.error.set('');
    try {
      const open = this.collectionOpen();
      const reponse = await this.disponibilitesApi.saveConfiguration({
        collecteOuverte: open,
        debut: this.collecteDebut() || null,
        fin: this.collecteFin() || null,
        prevenirAnimateurs: open && this.prevenir(),
      });
      this.applyCollecte(reponse);
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
      this.error.set(errorPrefix(error));
      void this.load();
    } finally {
      this.collectionSaving.set(false);
    }
  }

  /**
   * Writes the foire's switch and dates. Closed, the espaces turn read-only:
   * the planning stays readable and downloadable, nothing is proposed.
   */
  protected async saveFoire(): Promise<void> {
    this.fairSaving.set(true);
    this.error.set('');
    try {
      const configuration = await this.echangesApi.saveConfiguration({
        foireOuverte: this.fairOpen(),
        debut: this.foireDebut() || null,
        fin: this.foireFin() || null,
      });
      this.applyFoire(configuration);
      this.notifications.notify({
        title: configuration.foireOuverte
          ? $localize`:@@echanges.foireOuverteNotif:Foire au planning ouverte : les animateurs peuvent proposer des échanges.`
          : $localize`:@@echanges.foireFermeeNotif:Foire au planning fermée : les espaces animateurs passent en consultation seule.`,
        variant: 'success',
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
      void this.load();
    } finally {
      this.fairSaving.set(false);
    }
  }
}
