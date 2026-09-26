import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { LigneEnvoi } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { currentViewParams, keepViewInQueryParams } from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import {
  FiltreEnvois,
  acknowledgementLabel,
  countByFilter,
  daysToAnnounce,
  deliveryState,
  filterEnvois,
  isSilent,
  readFiltreEnvois,
  readJourEnvois,
  relanceLabel,
  reminderLabel,
  versionLabel,
} from './envois';
import { PublicationSelection } from './publication-selection';

/**
 * « Qui a reçu quelle version »: the permanent table of every person of the
 * edition, whatever the next publication concerns — the version they were
 * last sent, how that mail went (sent, failed and why, no address, deferred),
 * the night's reminder, the latest reminder of the silent and their
 * acknowledgement. Per row, the gestures that follow from it: resend their
 * planning, remind them, defer them from the next publication.
 *
 * <p>The filter and the day are view state in the URL (`?filtre=`, `?jour=`);
 * the day narrows to the people whose pending changes belong to it, on the
 * rule the Journée's « Changements » applies.</p>
 */
@Component({
  selector: 'app-envois-table',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './envois-table.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EnvoisTable {
  private readonly planningApi = inject(PlanningApi);
  private readonly animateursApi = inject(AnimateursApi);
  protected readonly selection = inject(PublicationSelection);
  private readonly params = currentViewParams();

  /** Bumped by the page after a publication: the states have moved. */
  readonly version = input(0);
  /** The line the page shows in its output panel. */
  readonly reported = output<string>();

  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  private readonly reload = signal(0);
  private readonly etat = resource({
    params: () => ({ version: this.version(), reload: this.reload() }),
    loader: () => this.planningApi.deliveryState(),
  });
  protected readonly state = retainedValue(this.etat);
  protected readonly error = errorText(this.etat);
  protected readonly loading = computed(() => this.etat.isLoading());

  protected readonly filtre = signal<FiltreEnvois>(readFiltreEnvois(this.params.get('filtre')));
  protected readonly jour = signal<string | null>(readJourEnvois(this.params.get('jour')));
  protected readonly busy = signal<string | null>(null);

  private readonly lignes = computed(() => this.state()?.personnes ?? []);
  protected readonly comptes = computed(() => countByFilter(this.lignes()));
  protected readonly jours = computed(() => daysToAnnounce(this.lignes()));
  protected readonly rows = computed(() => filterEnvois(this.lignes(), this.filtre(), this.jour()));
  /** The silent who can be reminded right now: seated, silent, reachable. */
  protected readonly silentReachable = computed(() =>
    this.lignes().filter((ligne) => isSilent(ligne) && ligne.email && ligne.version !== null),
  );
  protected readonly lastVersion = computed(() => {
    const latest = this.state()?.derniereVersion;
    if (!latest?.publieLe) {
      return '';
    }
    const quand = new Date(latest.publieLe).toLocaleString(intlLocale(), {
      dateStyle: 'short',
      timeStyle: 'short',
    });
    return $localize`:@@diffuser.derniere:Dernière publication : ${quand}:quand: (v${latest.numero}:numero:)`;
  });

  constructor() {
    keepViewInQueryParams(() => ({
      filtre: this.filtre() === 'tous' ? null : this.filtre(),
      jour: this.jour(),
    }));
  }

  protected chooseFilter(filtre: FiltreEnvois): void {
    this.filtre.set(filtre);
  }

  protected chooseDay(jour: string | null): void {
    this.jour.set(jour);
  }

  protected dayLabel(jour: string): string {
    return new Date(`${jour}T12:00:00`).toLocaleDateString(intlLocale(), {
      weekday: 'short',
      day: '2-digit',
      month: '2-digit',
    });
  }

  protected versionOf(ligne: LigneEnvoi): string {
    return versionLabel(ligne, intlLocale());
  }

  protected delivery(ligne: LigneEnvoi): { texte: string; ton: string } {
    return deliveryState(ligne, intlLocale());
  }

  protected reminder(ligne: LigneEnvoi): string {
    return reminderLabel(ligne, intlLocale());
  }

  protected relance(ligne: LigneEnvoi): string {
    return relanceLabel(ligne, intlLocale());
  }

  protected acknowledgement(ligne: LigneEnvoi): string {
    return acknowledgementLabel(ligne, intlLocale());
  }

  protected pending(ligne: LigneEnvoi): string {
    if (!ligne.aPrevenir) {
      return $localize`:@@diffuser.change.aJour:à jour`;
    }
    if (this.selection.isExcluded(ligne.animateurId)) {
      return $localize`:@@diffuser.change.differe:différé de la prochaine publication`;
    }
    return ligne.differe
      ? $localize`:@@diffuser.change.reporte:à prévenir, différé la dernière fois`
      : $localize`:@@diffuser.change.aPrevenir:à prévenir`;
  }

  protected canResend(ligne: LigneEnvoi): boolean {
    return ligne.email && ligne.version !== null;
  }

  protected canRemind(ligne: LigneEnvoi): boolean {
    return ligne.email && ligne.version !== null && isSilent(ligne);
  }

  /** « Renvoyer son planning »: the published plan, mailed again to this one person. */
  protected async resend(ligne: LigneEnvoi): Promise<void> {
    await this.act(ligne.animateurId, async () => {
      const compteRendu = await this.planningApi.sendToAnimateur(ligne.animateurId);
      return compteRendu.echecs.length > 0
        ? compteRendu.echecs.join(' — ')
        : $localize`:@@diffuser.renvoi.ok:Planning renvoyé à ${ligne.nomAffiche}:nom:`;
    });
  }

  /** « Relancer »: the confirmation reminder, now, to this one person. */
  protected async remind(ligne: LigneEnvoi): Promise<void> {
    await this.act(ligne.animateurId, async () => this.remindReport([ligne.animateurId]));
  }

  /** « Relancer les N silencieux »: the same reminder, to every silent person reachable. */
  protected async remindAll(): Promise<void> {
    const ids = this.silentReachable().map((ligne) => ligne.animateurId);
    if (ids.length === 0) {
      return;
    }
    await this.act('*', async () => this.remindReport(ids));
  }

  /** Holds somebody back from the next publication, or puts them back in it. */
  protected toggleDefer(ligne: LigneEnvoi): void {
    this.selection.setExcluded(ligne.animateurId, !this.selection.isExcluded(ligne.animateurId));
  }

  private async remindReport(ids: string[]): Promise<string> {
    const rapport = await this.animateursApi.remind(ids);
    const envoyes = rapport.envoyes.length;
    const nonEnvoyes =
      rapport.echecs.length +
      rapport.sansEmail.length +
      rapport.dejaRelancesPourCettePublication.length +
      rapport.dejaConfirmes.length +
      rapport.sansPoste.length;
    return nonEnvoyes === 0
      ? $localize`:@@diffuser.relance.rapport:${envoyes}:count: relance(s) envoyée(s)`
      : $localize`:@@diffuser.relance.rapportPartiel:${envoyes}:count: relance(s) envoyée(s), ${nonEnvoyes}:autres: non envoyée(s)`;
  }

  private async act(cle: string, action: () => Promise<string>): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(cle);
    try {
      this.reported.emit(await action());
    } catch (error) {
      this.reported.emit(errorPrefix(error));
    } finally {
      this.busy.set(null);
      this.reload.update((n) => n + 1);
    }
  }
}
