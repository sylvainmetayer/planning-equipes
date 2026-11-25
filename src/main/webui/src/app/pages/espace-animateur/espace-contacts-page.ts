import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { errorMessage } from '../../core/error-message';
import { StatusMessage } from '../../shared/status-message';
import { BrouillonContacts, brouillonDe, contactsDe } from '../rh/contacts';

/**
 * « Mes contacts »: the animateur's own emergency contact and, for a minor, their legal
 * representative. Optional, and read by the organisation's administrators and RH right only —
 * never by a colleague.
 */
@Component({
  selector: 'app-espace-contacts-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    StatusMessage,
  ],
  templateUrl: './espace-contacts-page.html',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceContactsPage implements OnInit {
  private readonly espace = inject(EspaceAnimateurService);

  protected readonly loading = signal(true);
  protected readonly sending = signal(false);
  protected readonly error = signal('');
  protected readonly warning = signal('');
  protected readonly saved = signal(false);
  protected readonly brouillon = signal<BrouillonContacts>(brouillonDe(null));

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.brouillon.set(brouillonDe(await this.espace.contacts()));
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected set(champ: keyof BrouillonContacts, valeur: string): void {
    this.saved.set(false);
    this.brouillon.update((b) => ({ ...b, [champ]: valeur }));
  }

  protected async save(): Promise<void> {
    this.sending.set(true);
    this.error.set('');
    this.warning.set('');
    try {
      const answer = await this.espace.enregistrerContacts(contactsDe(this.brouillon()));
      this.brouillon.set(brouillonDe(answer.contacts));
      this.warning.set(answer.avertissements.map((a) => a.message).join(' '));
      this.saved.set(true);
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.sending.set(false);
    }
  }
}
