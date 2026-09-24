import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ComptesApi } from '../../core/api/comptes-api';
import { errorMessage } from '../../core/error-message';
import { Compte } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';
import { emailError } from './comptes';

/**
 * Creates an account ahead of the person's first Keycloak sign-in, so a right
 * can be granted before they arrive. Closes with the created account; a
 * refusal (the address already has one, 409) stays in the dialog, next to what
 * was typed.
 */
@Component({
  selector: 'app-add-account-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    StatusMessage,
  ],
  template: `
    <h2 mat-dialog-title i18n="@@comptes.add.title">Ajouter un compte</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <p class="comptes-dialog-note" i18n="@@comptes.add.note">Un compte naît d'ordinaire à la première connexion Keycloak. Le créer d'avance permet d'accorder un droit avant l'arrivée de la personne.</p>
        <mat-form-field appearance="outline" class="comptes-dialog-field">
          <mat-label i18n="@@comptes.field.email">Adresse e-mail</mat-label>
          <input matInput type="email" name="email" required autocomplete="off" cdkFocusInitial
                 [attr.aria-invalid]="!!visibleEmailError()"
                 [ngModel]="email()" (ngModelChange)="email.set($event)" />
          <mat-hint i18n="@@comptes.add.emailHint">Celle du compte Keycloak de la personne.</mat-hint>
        </mat-form-field>
        @if (visibleEmailError(); as problem) {
          <p class="field-error" role="alert">{{ problem }}</p>
        }
        <mat-form-field appearance="outline" class="comptes-dialog-field">
          <mat-label i18n="@@comptes.field.nom">Nom</mat-label>
          <input matInput name="nom" autocomplete="off" [ngModel]="nom()" (ngModelChange)="nom.set($event)" />
        </mat-form-field>
        <app-status-message [text]="serverError()" tone="error" />
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button matButton type="button" (click)="dialogRef.close()" i18n="@@common.cancel">Annuler</button>
        <button matButton="filled" type="submit" [disabled]="busy()">
          <mat-icon>person_add</mat-icon>
          <ng-container i18n="@@comptes.add.submit">Créer le compte</ng-container>
        </button>
      </mat-dialog-actions>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddAccountDialog {
  protected readonly dialogRef = inject<MatDialogRef<AddAccountDialog, Compte>>(MatDialogRef);
  private readonly comptesApi = inject(ComptesApi);

  protected readonly email = signal('');
  protected readonly nom = signal('');
  protected readonly busy = signal(false);
  protected readonly serverError = signal('');
  /** The address is judged once a submit was tried, not on the first keystroke. */
  private readonly submitted = signal(false);

  protected readonly visibleEmailError = computed(() =>
    this.submitted() ? emailError(this.email()) : null,
  );

  protected async save(): Promise<void> {
    this.submitted.set(true);
    if (emailError(this.email()) !== null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.serverError.set('');
    try {
      const compte = await this.comptesApi.create({
        email: this.email().trim(),
        nom: this.nom().trim() || null,
      });
      this.dialogRef.close(compte);
    } catch (error) {
      this.serverError.set(errorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
