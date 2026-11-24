import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ComptesApi } from '../../core/api/comptes-api';
import { EditionStore } from '../../core/edition.store';
import { errorMessage } from '../../core/error-message';
import { Compte, Habilitation, RoleHabilitation } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SessionStore } from '../../core/session.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  ROLES,
  editionLabel,
  isOwnAccount,
  rightLabel,
  rightState,
  roleLabel,
  sortAccounts,
} from './comptes';

/** The edition field's value for « every edition »: a select cannot hold `null`. */
const ALL_EDITIONS = '*';

/**
 * « Comptes » of Paramètres, for the administrators: who may sign in, with
 * which rights in which editions. An account is deactivated and a right
 * withdrawn, never deleted; the refusals of the server (the last
 * administrator, one's own account) are shown as they come.
 */
@Component({
  selector: 'app-comptes-card',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './comptes-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComptesCard implements OnInit {
  private readonly api = inject(ComptesApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly session = inject(SessionStore);
  private readonly editionStore = inject(EditionStore);

  protected readonly roles = ROLES;
  protected readonly editions = this.editionStore.editions;
  protected readonly allEditions = ALL_EDITIONS;

  private readonly liste = signal<Compte[]>([]);
  protected readonly comptes = computed(() => sortAccounts(this.liste()));
  protected readonly busy = signal(false);

  protected readonly email = signal('');
  protected readonly nom = signal('');

  /** The account whose grant form is open, and what it holds. */
  protected readonly grantFor = signal<string | null>(null);
  protected readonly role = signal<RoleHabilitation>('ORGANISATEUR');
  protected readonly edition = signal<string>(ALL_EDITIONS);

  ngOnInit(): void {
    void this.load();
  }

  protected roleLabel(role: RoleHabilitation): string {
    return roleLabel(role);
  }

  protected rightLabel(habilitation: Habilitation): string {
    return rightLabel(habilitation, this.editions());
  }

  protected editionLabel(id: string): string {
    return editionLabel(id === ALL_EDITIONS ? null : id, this.editions());
  }

  /** The rights that still open something: expired and withdrawn ones stay in the history. */
  protected inForce(compte: Compte): Habilitation[] {
    const now = new Date();
    return compte.habilitations.filter((h) => rightState(h, now) === 'active');
  }

  protected own(compte: Compte): boolean {
    return isOwnAccount(compte, this.session.statut()?.nom ?? null);
  }

  protected async add(): Promise<void> {
    const email = this.email().trim();
    if (!email) {
      return;
    }
    await this.run(async () => {
      await this.api.create({ email, nom: this.nom().trim() || null });
      this.email.set('');
      this.nom.set('');
    });
  }

  protected async toggle(compte: Compte): Promise<void> {
    await this.run(() =>
      compte.desactiveLe ? this.api.reactivate(compte.id) : this.api.deactivate(compte.id),
    );
  }

  protected openGrant(compte: Compte): void {
    this.grantFor.set(this.grantFor() === compte.id ? null : compte.id);
  }

  protected async grantRight(compte: Compte): Promise<void> {
    const role = this.role();
    const edition = this.edition();
    await this.run(async () => {
      await this.api.grant(compte.id, {
        role,
        editionId: role === 'ADMIN' || edition === ALL_EDITIONS ? null : edition,
        expireLe: null,
      });
      this.grantFor.set(null);
    });
  }

  protected async retirer(compte: Compte, habilitation: Habilitation): Promise<void> {
    const ok = await this.confirm.ask({
      title: $localize`:@@comptes.retirer.titre:Retirer ce droit ?`,
      message: $localize`:@@comptes.retirer.message:« ${this.rightLabel(habilitation)}:droit: » n'ouvrira plus rien pour ce compte ; il reste dans l'historique.`,
      confirmLabel: $localize`:@@comptes.retirer.confirmer:Retirer`,
      danger: true,
    });
    if (ok) {
      await this.run(() => this.api.withdraw(compte.id, habilitation.id));
    }
  }

  /** One write, then the list read again — the server's refusal is shown, never swallowed. */
  private async run(write: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    try {
      await write();
      await this.load();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    } finally {
      this.busy.set(false);
    }
  }

  private async load(): Promise<void> {
    try {
      this.liste.set(await this.api.list());
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    }
  }
}
