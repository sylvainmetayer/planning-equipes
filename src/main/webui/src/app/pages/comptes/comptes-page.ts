import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  ViewEncapsulation,
  afterNextRender,
  computed,
  inject,
  resource,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AdminApi } from '../../core/api/admin-api';
import { ComptesApi } from '../../core/api/comptes-api';
import { StandsApi } from '../../core/api/stands-api';
import { EditionStore } from '../../core/edition.store';
import { errorMessage, errorPrefix } from '../../core/error-message';
import { Compte, Habilitation } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { TableNavigation } from '../../core/table-navigation';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';
import { TableFilter } from '../../shared/table-filter';
import { AddAccountDialog } from './add-account-dialog';
import {
  RightState,
  editionLabel,
  editionsWithStands,
  filterAccounts,
  isOwnAccount,
  rightState,
  rightsSummary,
  roleLabel,
  sortAccounts,
} from './comptes';
import { GrantRightData, GrantRightDialog } from './grant-right-dialog';

/** One line of the accounts table, its wording computed once rather than per change detection. */
interface AccountRow {
  compte: Compte;
  summary: string;
  own: boolean;
}

/** One line of the rights panel. */
interface RightRow {
  habilitation: Habilitation;
  state: RightState;
  role: string;
  edition: string;
  /** Stand names, `''` for a right without a stand scope. */
  stands: string;
}

/** Rights in force first, then expired, then withdrawn; the most recent first within each. */
const STATE_ORDER: Record<RightState, number> = { active: 0, expired: 1, withdrawn: 2 };

/**
 * Named accounts and the rights delegated to them (ADR 0049): who can sign in,
 * who was deactivated, and which RH or stand-manager right each one holds, in
 * which edition, until when.
 *
 * Nothing here is ever deleted — an account is deactivated, a right withdrawn —
 * and nothing here touches a credential: the password, the second factor and
 * the `admin` realm role are Keycloak's.
 */
@Component({
  selector: 'app-comptes-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
    StatusMessage,
    TableFilter,
  ],
  templateUrl: './comptes-page.html',
  styleUrl: './comptes-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComptesPage {
  private readonly comptesApi = inject(ComptesApi);
  private readonly adminApi = inject(AdminApi);
  private readonly standsApi = inject(StandsApi);
  private readonly editionStore = inject(EditionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly columns = ['email', 'nom', 'derniereConnexion', 'etat', 'droits', 'actions'];

  /** `null` before the first answer; kept on screen across a failed reload. */
  private readonly comptes = signal<Compte[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal('');
  protected readonly busy = signal(false);

  /** Who is signed in, to warn before they lock themselves out. */
  private readonly sessionName = signal<string | null>(null);

  protected readonly filter = signal('');
  /** The account whose rights panel is open. */
  protected readonly selectedId = signal<string | null>(null);

  protected readonly total = computed(() => this.comptes()?.length ?? 0);

  protected readonly rows = computed<AccountRow[]>(() => {
    const now = new Date();
    const editions = this.editionStore.editions();
    const sessionName = this.sessionName();
    return filterAccounts(sortAccounts(this.comptes() ?? []), this.filter(), editions, now).map(
      (compte) => ({
        compte,
        summary: rightsSummary(compte, editions, now),
        own: isOwnAccount(compte, sessionName),
      }),
    );
  });

  protected readonly selected = computed(
    () => this.comptes()?.find((compte) => compte.id === this.selectedId()) ?? null,
  );
  protected readonly selectedIsOwn = computed(() => {
    const compte = this.selected();
    return compte !== null && isOwnAccount(compte, this.sessionName());
  });

  /**
   * Stand names of the editions the selected account's rights name — read with
   * an explicit edition, since a right may sit in an edition other than the one
   * on screen. One key per set of editions, so granting a right in the same
   * edition does not read its stands again.
   */
  private readonly standEditions = computed(() => editionsWithStands(this.selected()).join('\n'));
  private readonly standNames = resource({
    params: () => this.standEditions() || undefined,
    loader: async ({ params }) => {
      const names = new Map<string, string>();
      await Promise.all(
        params.split('\n').map(async (editionId) => {
          // A deleted edition, or one unreadable: the ids stay on screen.
          const stands = await this.standsApi.listInEdition(editionId).catch(() => []);
          for (const stand of stands) {
            names.set(`${editionId}\n${stand.id}`, stand.nom);
          }
        }),
      );
      return names;
    },
  });

  protected readonly rightRows = computed<RightRow[]>(() => {
    const compte = this.selected();
    if (!compte) {
      return [];
    }
    const now = new Date();
    const editions = this.editionStore.editions();
    const names = this.standNames.hasValue() ? this.standNames.value() : new Map<string, string>();
    return compte.habilitations
      .map((habilitation) => ({
        habilitation,
        state: rightState(habilitation, now),
        role: roleLabel(habilitation.role),
        edition: editionLabel(habilitation.editionId, editions),
        stands: habilitation.standIds
          .map((standId) => names.get(`${habilitation.editionId}\n${standId}`) ?? standId)
          .join(', '),
      }))
      .sort(
        (a, b) =>
          STATE_ORDER[a.state] - STATE_ORDER[b.state] ||
          b.habilitation.creeLe.localeCompare(a.habilitation.creeLe),
      );
  });

  /**
   * Roving tabindex over the rows: the arrows move, Entrée opens the rights
   * panel — `core/table-navigation.ts`. Entered from the filter's arrow down.
   */
  protected readonly navigation = new TableNavigation<AccountRow, string>({
    rows: this.rows,
    id: (row) => row.compte.id,
    host: () => this.host.nativeElement,
    open: (row) => {
      this.showRights(row.compte);
      return true;
    },
  });

  private readonly panelHeading = viewChild<ElementRef<HTMLElement>>('panelHeading');

  constructor() {
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    this.filter.set(params.get('q') ?? '');
    this.selectedId.set(params.get('compte'));
    keepViewInQueryParams(() => ({
      q: optionalParam(this.filter()),
      compte: this.selectedId(),
    }));
    void this.reload();
    this.adminApi
      .session()
      .then((statut) => this.sessionName.set(statut.nom))
      // Without it the page only loses the self-deactivation warning.
      .catch(() => undefined);
  }

  protected async reload(): Promise<void> {
    this.loading.set(true);
    try {
      this.comptes.set(await this.comptesApi.list());
      this.loadError.set('');
    } catch (error) {
      this.loadError.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Opens the rights panel and hands it the focus, so a keyboard user lands on what opened. */
  protected showRights(compte: Compte): void {
    this.selectedId.set(compte.id);
    afterNextRender(() => this.panelHeading()?.nativeElement.focus(), {
      injector: this.injector,
    });
  }

  protected closeRights(): void {
    this.selectedId.set(null);
  }

  protected async openAdd(): Promise<void> {
    const compte = await firstValueFrom(
      this.dialog
        .open<AddAccountDialog, void, Compte>(AddAccountDialog, {
          width: '32rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    if (compte) {
      this.replace(compte);
      this.notifications.notify({
        title: $localize`:@@comptes.added:Compte ${compte.email}:email: créé.`,
        variant: 'success',
        timeout: 4000,
      });
      this.showRights(compte);
    }
  }

  protected async deactivate(compte: Compte): Promise<void> {
    const own = isOwnAccount(compte, this.sessionName());
    const confirmed = await this.confirm.ask({
      title: $localize`:@@comptes.deactivate.title:Désactiver le compte ${compte.email}:email: ?`,
      message: $localize`:@@comptes.deactivate.message:La personne perd tous ses rôles dans l'application, ceux du realm Keycloak compris, jusqu'à sa réactivation. Rien n'est supprimé.`,
      detail: own
        ? Promise.resolve(
            $localize`:@@comptes.deactivate.own:Attention : c'est votre propre compte. Vous perdrez l'accès à l'administration dès la requête suivante.`,
          )
        : undefined,
      confirmLabel: $localize`:@@comptes.deactivate.confirm:Désactiver`,
      danger: true,
    });
    if (confirmed) {
      await this.run(() => this.comptesApi.deactivate(compte.id));
    }
  }

  protected async reactivate(compte: Compte): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@comptes.reactivate.title:Réactiver le compte ${compte.email}:email: ?`,
      message: $localize`:@@comptes.reactivate.message:La personne retrouve ses rôles du realm Keycloak et ses droits encore en vigueur.`,
      confirmLabel: $localize`:@@comptes.reactivate.confirm:Réactiver`,
    });
    if (confirmed) {
      await this.run(() => this.comptesApi.reactivate(compte.id));
    }
  }

  protected async openGrant(compte: Compte): Promise<void> {
    const updated = await firstValueFrom(
      this.dialog
        .open<GrantRightDialog, GrantRightData, Compte>(GrantRightDialog, {
          data: {
            compte,
            editions: this.editionStore.editions(),
            currentEditionId: this.editionStore.courant()?.id ?? null,
          },
          width: '36rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    if (updated) {
      this.replace(updated);
      this.notifications.notify({
        title: $localize`:@@comptes.granted:Droit accordé à ${updated.email}:email:.`,
        variant: 'success',
        timeout: 4000,
      });
    }
  }

  protected async withdraw(compte: Compte, right: RightRow): Promise<void> {
    const role = right.role;
    const edition = right.edition;
    const confirmed = await this.confirm.ask({
      title: $localize`:@@comptes.withdraw.title:Retirer ce droit ?`,
      message: $localize`:@@comptes.withdraw.message:${role}:role: (${edition}:edition:) n'ouvrira plus rien. Le droit reste listé et daté sur le compte.`,
      confirmLabel: $localize`:@@comptes.withdraw.confirm:Retirer`,
      danger: true,
    });
    if (confirmed) {
      await this.run(() => this.comptesApi.withdraw(compte.id, right.habilitation.id));
    }
  }

  /** One write, its answer put in place of the account it returns. */
  private async run(action: () => Promise<Compte>): Promise<void> {
    this.busy.set(true);
    try {
      this.replace(await action());
    } catch (error) {
      this.notifications.notify({ title: errorMessage(error), variant: 'error' });
    } finally {
      this.busy.set(false);
    }
  }

  private replace(compte: Compte): void {
    this.comptes.update((comptes) => [
      ...(comptes ?? []).filter((candidate) => candidate.id !== compte.id),
      compte,
    ]);
  }
}
