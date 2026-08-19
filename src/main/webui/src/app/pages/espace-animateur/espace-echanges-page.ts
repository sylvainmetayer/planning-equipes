import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { statutDemandeClasse, statutDemandeLabel } from '../../core/demande-echange-labels';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { DemandeEchangeView, PosteAnimateurView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import {
  BrouillonDemande,
  ajouterBrouillon,
  brouillonComplet,
  retirerBrouillon,
  versNouvellesDemandes
} from './echange-brouillon';

interface DemandeRow extends DemandeEchangeView {
  statutLabel: string;
  statutClasse: string;
}

/**
 * The échange request form of the espace animateur (issue #165): the
 * animateur lists the créneaux they want to trade (poste + colleague + motif),
 * then submits the whole list at once. Each demande is prevalidated
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
    MatSelectModule
  ],
  templateUrl: './espace-echanges-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspaceEchangesPage {
  protected readonly espace = inject(EspaceAnimateurService);
  private readonly notifications = inject(NotificationService);

  protected readonly posteChoisi = signal<PosteAnimateurView | null>(null);
  protected readonly cibleId = signal('');
  protected readonly motif = signal('');
  protected readonly brouillons = signal<BrouillonDemande[]>([]);
  protected readonly envoiEnCours = signal(false);

  protected readonly formulaireComplet = computed(() =>
    brouillonComplet(this.posteChoisi(), this.cibleId())
  );

  protected readonly demandes = computed<DemandeRow[]>(() =>
    this.espace.demandes().map((demande) => ({
      ...demande,
      statutLabel: statutDemandeLabel(demande.statut),
      statutClasse: statutDemandeClasse(demande.statut)
    }))
  );

  protected ajouter(): void {
    const poste = this.posteChoisi();
    if (!poste || !this.formulaireComplet()) {
      return;
    }
    const cible = this.espace.vue()?.collegues.find((collegue) => collegue.id === this.cibleId());
    this.brouillons.set(
      ajouterBrouillon(this.brouillons(), {
        creneauId: poste.creneauId,
        standId: poste.standId,
        cibleId: this.cibleId(),
        motif: this.motif() || null,
        creneauLabel: `${poste.date ?? ''} ${poste.heureDebut}–${poste.heureFin}`.trim(),
        standNom: poste.standNom,
        cibleNom: cible?.nomComplet ?? this.cibleId()
      })
    );
    this.posteChoisi.set(null);
    this.cibleId.set('');
    this.motif.set('');
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
        message: error instanceof Error ? error.message : String(error),
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
        message: error instanceof Error ? error.message : String(error),
        variant: 'error'
      });
    }
  }
}
