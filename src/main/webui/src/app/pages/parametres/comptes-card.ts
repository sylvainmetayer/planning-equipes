import { DatePipe } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  Injector,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ComptesApi } from '../../core/api/comptes-api';
import { EditionStore } from '../../core/edition.store';
import { errorMessage } from '../../core/error-message';
import { Compte, Habilitation, RoleHabilitation } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SessionStore } from '../../core/session.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ROLES, editionLabel, rightLabel, rightState, roleLabel, sortAccounts } from './comptes';

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
    MatCheckboxModule,
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
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly injector = inject(Injector);

  protected readonly roles = ROLES;
  protected readonly editions = this.editionStore.editions;
  protected readonly allEditions = ALL_EDITIONS;

  /** `null` while the first read is under way: not an empty list. */
  private readonly liste = signal<Compte[] | null>(null);
  protected readonly comptes = computed(() => {
    const liste = this.liste();
    return liste === null ? null : sortAccounts(liste);
  });
  protected readonly busy = signal(false);

  protected readonly email = signal('');
  protected readonly nom = signal('');
  /** Mail the account the link to choose its password — on by default: the local account is the usual case. */
  protected readonly inviter = signal(true);

  /** The account whose grant form is open, and what it holds. */
  protected readonly grantFor = signal<string | null>(null);
  protected readonly role = signal<RoleHabilitation>('ORGANISATEUR');
  protected readonly edition = signal<string>(ALL_EDITIONS);
  /** The stands a responsable de stand's right covers: those of the edition this browser works in. */
  protected readonly stands = this.referenceData.stands;
  protected readonly standIds = signal<string[]>([]);

  ngOnInit(): void {
    void this.load();
  }

  protected roleLabel(role: RoleHabilitation): string {
    return roleLabel(role);
  }

  protected rightLabel(habilitation: Habilitation): string {
    return rightLabel(habilitation, this.editions());
  }

  protected readonly currentEdition = computed(() => this.editionStore.courant()?.nom ?? '');

  protected editionLabel(id: string): string {
    return editionLabel(id === ALL_EDITIONS ? null : id, this.editions());
  }

  /** The rights that still open something: expired and withdrawn ones stay in the history. */
  protected inForce(compte: Compte): Habilitation[] {
    const now = new Date();
    return compte.habilitations.filter((h) => rightState(h, now) === 'active');
  }

  /** The account of the session, compared by id — never by address. */
  protected own(compte: Compte): boolean {
    const id = this.session.statut()?.compteId;
    return !!id && id === compte.id;
  }

  protected async add(): Promise<void> {
    const email = this.email().trim();
    if (!email) {
      return;
    }
    await this.run(async () => {
      await this.api.create({ email, nom: this.nom().trim() || null, invite: this.inviter() });
      this.email.set('');
      this.nom.set('');
      if (this.inviter()) {
        this.notifyLinkSent();
      }
    });
  }

  /** « Inviter » before a first password, « Réinitialiser » after: the link is mailed, never shown. */
  protected async sendLink(compte: Compte): Promise<void> {
    await this.run(async () => {
      await (compte.passwordSet ? this.api.resetPassword(compte.id) : this.api.invite(compte.id));
      this.notifyLinkSent();
    });
  }

  private notifyLinkSent(): void {
    this.notifications.notify({
      title: $localize`:@@comptes.lien.envoye.titre:Lien envoyé`,
      message: $localize`:@@comptes.lien.envoye.message:Le compte reçoit par e-mail un lien à usage unique pour choisir son mot de passe. Vous ne le voyez pas.`,
      variant: 'success',
    });
  }

  protected async toggle(compte: Compte): Promise<void> {
    if (compte.desactiveLe) {
      await this.run(() => this.api.reactivate(compte.id));
      return;
    }
    const nom = compte.nom || compte.email;
    const ok = await this.confirm.ask({
      title: $localize`:@@comptes.desactiver.titre:Désactiver ce compte ?`,
      message: $localize`:@@comptes.desactiver.message:« ${nom}:nom: » ne pourra plus se connecter, quels que soient ses droits. Le compte et son historique sont conservés.`,
      confirmLabel: $localize`:@@comptes.desactiver:Désactiver`,
      danger: true,
    });
    if (ok) {
      await this.run(() => this.api.deactivate(compte.id));
    }
  }

  protected openGrant(compte: Compte): void {
    if (this.grantFor() === compte.id) {
      this.grantFor.set(null);
      return;
    }
    // Never « every edition » by default, and never the previous account's choice.
    this.role.set('ORGANISATEUR');
    this.edition.set(this.editionStore.courant()?.id ?? ALL_EDITIONS);
    this.standIds.set([]);
    this.grantFor.set(compte.id);
    this.focusAfterRender(`comptes-role-${compte.id}`);
  }

  protected async grantRight(compte: Compte): Promise<void> {
    const role = this.role();
    const responsable = role === 'RESPONSABLE_STAND';
    // A responsable's right is one edition's: the one this browser works in.
    const edition = responsable
      ? (this.editionStore.courant()?.id ?? ALL_EDITIONS)
      : this.edition();
    await this.run(async () => {
      await this.api.grant(compte.id, {
        role,
        editionId: role === 'ADMIN' || edition === ALL_EDITIONS ? null : edition,
        expireLe: null,
        ...(responsable ? { standIds: this.standIds() } : {}),
      });
      this.grantFor.set(null);
    });
    this.focusAfterRender(`comptes-accorder-${compte.id}`);
  }

  protected async retirer(compte: Compte, habilitation: Habilitation): Promise<void> {
    const droit = this.rightLabel(habilitation);
    // Withdrawing one's own administrator right ends this session's access to
    // this very screen: said plainly, and the session is read again after.
    const own = this.own(compte) && habilitation.role === 'ADMIN';
    const ok = await this.confirm.ask({
      title: own
        ? $localize`:@@comptes.retirer.soi.titre:Retirer votre propre droit d'administrateur ?`
        : $localize`:@@comptes.retirer.titre:Retirer ce droit ?`,
      message: own
        ? $localize`:@@comptes.retirer.soi.message:Vous ne pourrez plus administrer l'instance ni revenir sur cet écran sans qu'un autre administrateur vous rende ce droit.`
        : $localize`:@@comptes.retirer.message:« ${droit}:droit: » n'ouvrira plus rien pour ce compte ; il reste dans l'historique.`,
      confirmLabel: $localize`:@@comptes.retirer.confirmer:Retirer`,
      danger: true,
    });
    if (!ok) {
      return;
    }
    await this.run(() => this.api.withdraw(compte.id, habilitation.id));
    if (own) {
      await this.session.refresh();
    }
  }

  private focusAfterRender(id: string): void {
    afterNextRender(() => document.getElementById(id)?.focus(), { injector: this.injector });
  }

  /** One write, then the list read again — the server's refusal is shown, never swallowed. */
  private async run(write: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    try {
      await write();
      await this.load();
    } catch (error) {
      this.notifyError(error);
      // A write may have half succeeded (the account created, its invitation
      // not sent): the list shows what is there, not what was expected.
      await this.load();
    } finally {
      this.busy.set(false);
    }
  }

  private async load(): Promise<void> {
    try {
      this.liste.set(await this.api.list());
    } catch (error) {
      this.liste.set([]);
      this.notifyError(error);
    }
  }

  private notifyError(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}
