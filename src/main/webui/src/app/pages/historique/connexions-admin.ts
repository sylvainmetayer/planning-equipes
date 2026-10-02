import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { AdminLoginView } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';
import { PAGE_HISTORIQUE, loginLabel, cutPage } from './historique';

/**
 * « Connexions » of the Historique page (ADR 0076): every successful admin
 * login, every failure and every lockout of the form login, with its time and
 * address — the instance's, so the same lines whatever edition is selected.
 * Nothing typed is kept, and the server says so by never sending it.
 */
@Component({
  selector: 'app-connexions-admin',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, StatusMessage],
  template: `
    <p class="historique-connexions-intro" i18n="@@historique.connexions.intro">
      Les connexions à l'administration, quelle que soit l'édition : réussies, échouées, et les adresses verrouillées
      après trop d'échecs. Ni l'identifiant ni le mot de passe saisis ne sont conservés.
    </p>
    <button matButton type="button" (click)="recharger()" [disabled]="chargement()">
      <mat-icon>refresh</mat-icon>
      <ng-container i18n="@@common.refresh">Actualiser</ng-container>
    </button>
    @if (chargement()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <app-status-message [text]="erreur()" tone="error" />
    @if (!chargement() && lignes().length === 0) {
      <p class="empty-hint" i18n="@@historique.connexions.vide">Aucune connexion enregistrée.</p>
    }
    @if (lignes().length > 0) {
      <table class="historique-connexions">
        <thead>
          <tr>
            <th scope="col" i18n="@@historique.connexions.quand">Quand</th>
            <th scope="col" i18n="@@historique.connexions.evenement">Événement</th>
            <th scope="col" i18n="@@historique.connexions.adresse">Adresse</th>
          </tr>
        </thead>
        <tbody>
          @for (ligne of lignes(); track ligne.id) {
            <tr [class.historique-connexions-alerte]="ligne.evenement !== 'CONNEXION'">
              <td>{{ quand(ligne.survenuLe) }}</td>
              <td>{{ libelle(ligne) }}</td>
              <td class="historique-connexions-adresse">{{ ligne.adresse }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
    @if (suivant() !== null) {
      <div class="historique-suite">
        <button matButton type="button" (click)="loadMore()" [disabled]="chargement()">
          <mat-icon>expand_more</mat-icon>
          <ng-container i18n="@@historique.chargerPlus">Charger plus</ng-container>
        </button>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConnexionsAdmin implements OnInit {
  private readonly analysesApi = inject(AnalysesApi);

  protected readonly lignes = signal<AdminLoginView[]>([]);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal('');
  /** The cursor of the next page — the id of the last line shown —, `null` on the last one. */
  protected readonly suivant = signal<number | null>(null);
  /** Which load the list shows: an answer to an older one never overwrites a newer one. */
  private currentLoad = 0;

  ngOnInit(): void {
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    const ticket = ++this.currentLoad;
    await this.load(ticket, null, (page) => this.lignes.set(page));
  }

  /** The page after the last line shown; a reload asked meanwhile wins. */
  protected async loadMore(): Promise<void> {
    const before = this.suivant();
    if (before !== null) {
      await this.load(this.currentLoad, before, (page) =>
        this.lignes.update((deja) => [...deja, ...page]),
      );
    }
  }

  private async load(
    ticket: number,
    before: number | null,
    apply: (page: AdminLoginView[]) => void,
  ): Promise<void> {
    this.chargement.set(true);
    this.erreur.set('');
    try {
      // One line more than a page: when it comes back, there is a next one.
      const page = cutPage(await this.analysesApi.loginJournal(before, PAGE_HISTORIQUE + 1));
      if (ticket === this.currentLoad) {
        apply(page.entrees);
        this.suivant.set(page.suivant);
      }
    } catch (error) {
      if (ticket === this.currentLoad) {
        this.erreur.set(errorPrefix(error));
      }
    } finally {
      if (ticket === this.currentLoad) {
        this.chargement.set(false);
      }
    }
  }

  protected libelle(ligne: AdminLoginView): string {
    return loginLabel(ligne);
  }

  /** « 7 sept. 2026, 14:32 » — a login is read on its own, not grouped by day. */
  protected quand(iso: string): string {
    return new Date(iso).toLocaleString(intlLocale(), {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  }
}
