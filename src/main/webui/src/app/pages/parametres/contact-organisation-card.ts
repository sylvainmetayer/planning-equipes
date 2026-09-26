import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { AdminApi } from '../../core/api/admin-api';
import { errorPrefix } from '../../core/error-message';
import { ContactOrganisation } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * The organisation's contact shown in the espace animateur — a phone number
 * and an address, both optional, per edition. Stored as typed; the server
 * trims, stores a blank half as nothing, and refuses a number with letters or
 * an address without its `@`.
 */
@Component({
  selector: 'app-contact-organisation-card',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    StatusMessage,
  ],
  template: `
    <mat-card appearance="outlined" class="page-card" id="contact">
      <mat-card-header>
        <h2 mat-card-title i18n="@@parametres.contact.title">Contact de l'organisation</h2>
        <mat-card-subtitle i18n="@@parametres.contact.subtitle"
          >Affiché aux animateurs dans leur espace. Laissez vide ce que vous ne publiez
          pas.</mat-card-subtitle
        >
      </mat-card-header>
      <mat-card-content>
        <form class="form-grid" (ngSubmit)="save()">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label i18n="@@parametres.contact.telephone">Téléphone</mat-label>
            <input
              matInput
              type="tel"
              name="telephone"
              autocomplete="off"
              maxlength="40"
              [disabled]="busy()"
              [ngModel]="telephone()"
              (ngModelChange)="telephone.set($event)"
            />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label i18n="@@parametres.contact.email">Adresse e-mail</mat-label>
            <input
              matInput
              type="email"
              name="email"
              autocomplete="off"
              maxlength="254"
              [disabled]="busy()"
              [ngModel]="email()"
              (ngModelChange)="email.set($event)"
            />
          </mat-form-field>
          <div class="form-actions">
            <button matButton="filled" type="submit" [disabled]="busy() || !dirty()">
              <mat-icon>save</mat-icon>
              <ng-container i18n="@@common.save">Enregistrer</ng-container>
            </button>
          </div>
        </form>
        <app-status-message [text]="error()" tone="error" />
      </mat-card-content>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactOrganisationCard implements OnInit {
  private readonly adminApi = inject(AdminApi);
  private readonly notifications = inject(NotificationService);

  protected readonly busy = signal(false);
  protected readonly error = signal('');
  private readonly stored = signal<ContactOrganisation>({ telephone: null, email: null });
  protected readonly telephone = signal('');
  protected readonly email = signal('');

  protected readonly dirty = computed(
    () =>
      this.telephone().trim() !== (this.stored().telephone ?? '') ||
      this.email().trim() !== (this.stored().email ?? ''),
  );

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.apply(await this.adminApi.organisationContact());
    } catch (error) {
      this.error.set(errorPrefix(error));
    }
  }

  private apply(contact: ContactOrganisation): void {
    this.stored.set(contact);
    this.telephone.set(contact.telephone ?? '');
    this.email.set(contact.email ?? '');
  }

  protected async save(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      this.apply(
        await this.adminApi.saveOrganisationContact({
          telephone: this.telephone().trim() || null,
          email: this.email().trim() || null,
        }),
      );
      this.notifications.notify({
        title: $localize`:@@parametres.contact.saved:Contact de l'organisation enregistré`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }
}
