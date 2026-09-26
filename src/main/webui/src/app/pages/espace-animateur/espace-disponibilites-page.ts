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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { toDateKey } from '../../core/date-utils';
import { errorMessage } from '../../core/error-message';
import { DeclarationView } from '../../core/models';
import {
  BrouillonDeclaration,
  basculer,
  brouillonInitial,
  declarationModifiee,
  moisDeCollecte,
  ouvertureAVenir,
  versNouvelleDeclaration,
} from './declaration-brouillon';
import { StatusMessage } from '../../shared/status-message';
import { EspaceContact } from './espace-contact';

/**
 * « Mes disponibilités » (issue #291): the animateur declares the days they
 * cannot come and the kinds of games they would like to animate, on the window
 * the organisation opened.
 *
 * <p>Built for a thumb on a phone, which is what this espace is read on: the
 * days are a grid of large toggles grouped by month, not a table; the game
 * categories are toggles too; and one button sends the lot. What is declared
 * is a <b>proposal</b> — the page says so before, during and after, because an
 * animateur who believes their unavailability is recorded when it is not is
 * exactly the failure this feature must not create.</p>
 */
@Component({
  selector: 'app-espace-disponibilites-page',
  imports: [
    EspaceContact,
    StatusMessage,
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './espace-disponibilites-page.html',
  styleUrl: '../../../styles/espace-disponibilites.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceDisponibilitesPage implements OnInit {
  protected readonly espace = inject(EspaceAnimateurService);

  protected readonly chargement = signal(true);
  protected readonly envoiEnCours = signal(false);
  protected readonly erreur = signal<string | null>(null);
  /** Set on a successful send, cleared as soon as the draft moves again. */
  protected readonly envoye = signal(false);

  protected readonly brouillon = signal<BrouillonDeclaration>({
    joursIndisponibles: [],
    souhaits: [],
    commentaire: '',
  });

  protected readonly view = computed(() => this.espace.declaration());
  protected readonly mois = computed(() => moisDeCollecte(this.view()?.joursEvenement ?? []));
  protected readonly modifiee = computed(() => declarationModifiee(this.view(), this.brouillon()));

  /** No créneau exists yet: the days cannot be offered, but the wishes still can. */
  protected readonly noDays = computed(() => (this.view()?.joursEvenement.length ?? 0) === 0);

  /**
   * The day collection opens, when it is closed only because it has not
   * started yet. `collecteOuverte` alone would tell an animateur that a window
   * opening in two weeks is already over.
   */
  protected readonly pasEncoreOuverte = computed(() =>
    ouvertureAVenir(this.view(), toDateKey(new Date())),
  );

  ngOnInit(): void {
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    this.erreur.set(null);
    try {
      await this.espace.chargerDeclaration();
      this.brouillon.set(brouillonInitial(this.view()));
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.chargement.set(false);
    }
  }

  protected jourCoche(jour: string): boolean {
    return this.brouillon().joursIndisponibles.includes(jour);
  }

  protected souhaitCoche(typologieId: string): boolean {
    return this.brouillon().souhaits.includes(typologieId);
  }

  protected basculerJour(jour: string): void {
    this.envoye.set(false);
    this.brouillon.update((brouillon) => ({
      ...brouillon,
      joursIndisponibles: basculer(brouillon.joursIndisponibles, jour),
    }));
  }

  protected basculerSouhait(typologieId: string): void {
    this.envoye.set(false);
    this.brouillon.update((brouillon) => ({
      ...brouillon,
      souhaits: basculer(brouillon.souhaits, typologieId),
    }));
  }

  protected majCommentaire(commentaire: string): void {
    this.envoye.set(false);
    this.brouillon.update((brouillon) => ({ ...brouillon, commentaire }));
  }

  protected async envoyer(): Promise<void> {
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    try {
      await this.espace.declarer(versNouvelleDeclaration(this.brouillon()));
      this.brouillon.set(brouillonInitial(this.view()));
      this.envoye.set(true);
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.envoiEnCours.set(false);
    }
  }

  /** Same wording as the admin screen, so both sides name the same states. */
  protected statutLabel(declaration: DeclarationView): string {
    switch (declaration.statut) {
      case 'APPLIQUEE':
        return $localize`:@@espace.dispo.statut.appliquee:Prise en compte`;
      case 'REFUSEE':
        return $localize`:@@espace.dispo.statut.refusee:Non retenue`;
      default:
        return $localize`:@@espace.dispo.statut.enAttente:En attente de l'organisation`;
    }
  }
}
