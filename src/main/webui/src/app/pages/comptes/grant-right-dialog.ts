import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ComptesApi } from '../../core/api/comptes-api';
import { StandsApi } from '../../core/api/stands-api';
import { errorMessage, errorPrefix } from '../../core/error-message';
import { Compte, Edition, RoleHabilitation } from '../../core/models';
import { toDateKey } from '../../core/date-utils';
import { StatusMessage } from '../../shared/status-message';
import {
  GrantDraft,
  ROLES,
  grantErrors,
  initialGrantDraft,
  roleLabel,
  toGrantRequest,
  withEdition,
  withRole,
} from './comptes';

export interface GrantRightData {
  compte: Compte;
  editions: readonly Edition[];
  /** The edition this browser works in — the default scope of a stand manager. */
  currentEditionId: string | null;
}

/**
 * Grants one right to one account. The server's refusals are mirrored here
 * (`grantErrors`) so the dialog says them before sending; what the server
 * still refuses — a stand deleted meanwhile — is shown in the dialog, which
 * stays open. Closes with the updated account.
 *
 * The stands offered are those of the edition picked, read with an explicit
 * edition (`StandsApi.listInEdition`): a right in another edition is scoped to
 * that edition's stands, never to the ones on screen elsewhere.
 */
@Component({
  selector: 'app-grant-right-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    StatusMessage,
  ],
  template: `
    <h2 mat-dialog-title i18n="@@comptes.grant.title">Accorder un droit à {{ data.compte.email }}</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content class="comptes-grant-form">
        <mat-form-field appearance="outline">
          <mat-label i18n="@@comptes.field.role">Rôle</mat-label>
          <mat-select name="role" [ngModel]="draft().role" (ngModelChange)="setRole($event)">
            @for (option of roleOptions; track option.role) {
              <mat-option [value]="option.role">{{ option.label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label i18n="@@comptes.field.edition">Édition</mat-label>
          <mat-select name="edition" [ngModel]="draft().editionId" (ngModelChange)="setEdition($event)">
            @if (draft().role !== 'RESPONSABLE_STAND') {
              <mat-option [value]="null" i18n="@@comptes.edition.toutes">Toutes les éditions</mat-option>
            }
            @for (edition of data.editions; track edition.id) {
              <mat-option [value]="edition.id">{{ edition.nom }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        @if (draft().role === 'RESPONSABLE_STAND') {
          <mat-form-field appearance="outline">
            <mat-label i18n="@@comptes.field.stands">Stands</mat-label>
            <mat-select name="stands" multiple [disabled]="standOptions().length === 0"
                        [ngModel]="draft().standIds" (ngModelChange)="setStands($event)">
              @for (stand of standOptions(); track stand.id) {
                <mat-option [value]="stand.id">{{ stand.nom }}</mat-option>
              }
            </mat-select>
            @if (stands.isLoading()) {
              <mat-hint i18n="@@comptes.grant.standsLoading">Chargement des stands…</mat-hint>
            } @else if (draft().editionId !== null && standOptions().length === 0 && !standsError()) {
              <mat-hint i18n="@@comptes.grant.noStands">Cette édition n'a aucun stand.</mat-hint>
            } @else {
              <mat-hint i18n="@@comptes.grant.standsHint">Les stands de l'édition choisie.</mat-hint>
            }
          </mat-form-field>
          <app-status-message [text]="standsError()" tone="error" />
        }

        <mat-form-field appearance="outline">
          <mat-label i18n="@@comptes.field.expiry">Valable jusqu'au (inclus)</mat-label>
          <input matInput type="date" name="expiry" [min]="today"
                 [ngModel]="draft().expiryDate" (ngModelChange)="setExpiry($event)" />
          <mat-hint i18n="@@comptes.grant.expiryHint">Vide : sans date de fin.</mat-hint>
        </mat-form-field>

        @if (submitted()) {
          @for (problem of errors(); track problem) {
            <p class="field-error" role="alert">{{ problem }}</p>
          }
        }
        <app-status-message [text]="serverError()" tone="error" />
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button matButton type="button" (click)="dialogRef.close()" i18n="@@common.cancel">Annuler</button>
        <button matButton="filled" type="submit" [disabled]="busy()">
          <mat-icon>add_moderator</mat-icon>
          <ng-container i18n="@@comptes.grant.submit">Accorder</ng-container>
        </button>
      </mat-dialog-actions>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GrantRightDialog {
  protected readonly dialogRef = inject<MatDialogRef<GrantRightDialog, Compte>>(MatDialogRef);
  protected readonly data = inject<GrantRightData>(MAT_DIALOG_DATA);
  private readonly comptesApi = inject(ComptesApi);
  private readonly standsApi = inject(StandsApi);

  protected readonly roleOptions = ROLES.map((role) => ({ role, label: roleLabel(role) }));
  /** The date field refuses a past day on its own; `grantErrors` still says it. */
  protected readonly today = toDateKey(new Date());

  protected readonly draft = signal<GrantDraft>(initialGrantDraft());
  protected readonly busy = signal(false);
  protected readonly serverError = signal('');
  protected readonly submitted = signal(false);

  protected readonly errors = computed(() => grantErrors(this.draft(), new Date()));

  /** The picked edition's stands; idle while « every edition » is picked. */
  protected readonly stands = resource({
    params: () => this.draft().editionId ?? undefined,
    loader: ({ params }) => this.standsApi.listInEdition(params),
  });
  protected readonly standOptions = computed(() =>
    this.stands.hasValue()
      ? [...this.stands.value()].sort((a, b) => a.nom.localeCompare(b.nom))
      : [],
  );
  protected readonly standsError = computed(() => {
    const error = this.stands.error();
    return error ? errorPrefix(error) : '';
  });

  protected setRole(role: RoleHabilitation): void {
    this.draft.update((draft) => withRole(draft, role, this.data.currentEditionId));
  }

  protected setEdition(editionId: string | null): void {
    this.draft.update((draft) => withEdition(draft, editionId));
  }

  protected setStands(standIds: string[]): void {
    this.draft.update((draft) => ({ ...draft, standIds }));
  }

  protected setExpiry(expiryDate: string | null): void {
    this.draft.update((draft) => ({ ...draft, expiryDate: expiryDate ?? '' }));
  }

  protected async save(): Promise<void> {
    this.submitted.set(true);
    if (this.errors().length > 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.serverError.set('');
    try {
      this.dialogRef.close(
        await this.comptesApi.grant(this.data.compte.id, toGrantRequest(this.draft())),
      );
    } catch (error) {
      this.serverError.set(errorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
