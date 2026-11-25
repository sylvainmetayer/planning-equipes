import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RhApi } from '../../core/api/rh-api';
import { errorMessage } from '../../core/error-message';
import { ContactAnimateurView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { errorText, retainedValue } from '../../core/resource-state';
import { StatusMessage } from '../../shared/status-message';
import {
  BrouillonContacts,
  brouillonDe,
  contactsDe,
  hasRepresentative,
  journalText,
  telHref,
} from './contacts';

/**
 * The emergency contacts and the legal representatives of one edition, for the RH right and the
 * administrators: every animateur holding some, and every minor — the gap of a minor with no
 * representative is the thing worth seeing. Each reading and each write is journalled by the server.
 */
@Component({
  selector: 'app-contacts-rh',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    StatusMessage,
  ],
  templateUrl: './contacts-rh.html',
  styleUrl: './conformite-rapport.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactsRh {
  private readonly api = inject(RhApi);
  private readonly notifications = inject(NotificationService);

  readonly editionId = input.required<string>();

  private readonly contacts = resource({
    params: () => this.editionId(),
    loader: ({ params }) => this.api.contacts(params),
  });
  protected readonly lignes = retainedValue(this.contacts);
  protected readonly erreur = errorText(this.contacts);

  protected readonly editing = signal<string | null>(null);
  protected readonly brouillon = signal<BrouillonContacts>(brouillonDe(null));
  protected readonly saving = signal(false);

  protected readonly hasRepresentative = hasRepresentative;
  protected readonly telHref = telHref;

  protected edit(ligne: ContactAnimateurView): void {
    this.brouillon.set(brouillonDe(ligne.contacts));
    this.editing.set(ligne.animateurId);
  }

  protected set(champ: keyof BrouillonContacts, valeur: string): void {
    this.brouillon.update((b) => ({ ...b, [champ]: valeur }));
  }

  protected async save(ligne: ContactAnimateurView): Promise<void> {
    this.saving.set(true);
    try {
      const saved = await this.api.saveContacts(
        this.editionId(),
        ligne.animateurId,
        contactsDe(this.brouillon()),
      );
      for (const avertissement of saved.avertissements) {
        this.notifications.notify({
          title: $localize`:@@contacts.avertissement:Représentant légal manquant`,
          message: avertissement.message,
          messageJournal: journalText(avertissement),
          variant: 'warning',
        });
      }
      this.editing.set(null);
      this.contacts.reload();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    } finally {
      this.saving.set(false);
    }
  }
}
