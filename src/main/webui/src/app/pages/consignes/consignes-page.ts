import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  ViewEncapsulation,
  computed,
  inject,
  output,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { ConsignesApi } from '../../core/api/consignes-api';
import {
  bandeLabel,
  fenetreLabel,
  libelleDate,
  repasLabel,
  repasSurcharge,
} from '../../core/consigne-wording';
import { ConsignesStore } from '../../core/consignes.store';
import {
  ApercuConsigneJour,
  ConsigneEdition,
  IndicateurConsigne,
  PrereglageConsigne,
  RepasConsigne,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { consumeQueryParam } from '../../core/view-query-params';
import { LOCAL_DRAFT_STORAGE } from '../../core/brouillon-formulaire';
import { reportOrphanDrafts } from '../../shared/brouillon-dialog';
import { ConfirmService } from '../../shared/confirm-dialog';
import { consigneDateOf } from './consigne-brouillon';
import { ConsigneFormData, ConsigneFormDialog, ModeConsigne } from './consigne-form-dialog';
import { ConsigneLeveeData, ConsigneLeveeDialog } from './consigne-levee-dialog';
import { datesCandidates, isPast, openedStandsCount, resolveDateParam } from './consignes';
import { PrereglageDialog, PrereglageDialogData } from './prereglage-dialog';

/** One row of the table: the consigne, its figures, and whether it can still move. */
interface LigneConsigne {
  date: string;
  libelleDate: string;
  bande: string;
  motif: string;
  prereglage: string;
  standsOuverts: number;
  fenetres: string;
  /** The tooltip of the « repas surchargé » chip, empty when the edition's meal windows apply. */
  repas: string;
  indicateur: IndicateurConsigne | null;
  passee: boolean;
  consigne: ConsigneEdition;
}

/**
 * « Consignes » (issue #4): one line per date the organiser closed every
 * stand on — an arrêté préfectoral, typically — with the compensation
 * windows chosen, the figures it cost, and the presets an alert is made from.
 *
 * <p>Only the days to come move: poser, modifier, prolonger and lever are
 * refused on a date already begun, server-side first, so the table shows
 * those rows read-only. Every write is previewed in its dialog before it
 * lands.</p>
 */
@Component({
  selector: 'app-consignes-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
  ],
  templateUrl: './consignes-page.html',
  styleUrl: './consignes-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsignesPage implements OnInit {
  /** A consigne was laid down, changed or lifted: the next solve has something new to respect. */
  readonly changed = output<void>();

  private readonly destroyRef = inject(DestroyRef);
  private readonly store = inject(ConsignesStore);
  private readonly api = inject(ConsignesApi);
  private readonly referentiel = inject(ReferenceDataStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly route = inject(ActivatedRoute);
  private readonly draftStorage = inject(LOCAL_DRAFT_STORAGE);
  /** Editing is disabled while a solve runs, like every referential screen. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly columns = [
    'date',
    'bande',
    'motif',
    'prereglage',
    'stands',
    'sieges',
    'minutes',
    'animateurs',
    'etat',
    'actions',
  ];

  protected readonly chargement = this.store.loading;
  protected readonly erreur = this.store.error;
  protected readonly aujourdhui = this.store.aujourdhui;
  protected readonly prereglages = computed(() => this.store.etat()?.prereglages ?? []);
  /** The date a link named (`?date=`), highlighted in the table. */
  protected readonly targetDate = computed(() =>
    resolveDateParam(this.route.snapshot.queryParamMap.get('date'), this.aujourdhui()),
  );

  protected readonly lignes = computed<LigneConsigne[]>(() => {
    const aujourdhui = this.aujourdhui();
    const indicateurs = new Map(
      (this.store.etat()?.indicateurs ?? []).map((indicateur) => [indicateur.date, indicateur]),
    );
    return [...this.store.consignes()]
      .sort((gauche, droite) => gauche.date.localeCompare(droite.date))
      .map((consigne) => ({
        date: consigne.date,
        libelleDate: libelleDate(consigne.date),
        bande: bandeLabel(consigne.fermetureDebut, consigne.fermetureFin),
        motif: consigne.motif,
        prereglage: consigne.prereglage ?? '',
        standsOuverts: openedStandsCount(consigne),
        fenetres: consigne.fenetres.map(fenetreLabel).join(', '),
        repas: repasSurcharge(consigne.repas) ? repasLabel(consigne.repas) : '',
        indicateur: indicateurs.get(consigne.date) ?? null,
        passee: isPast(consigne.date, aujourdhui),
        consigne,
      }));
  });

  /** The grid's dates strictly after today, the only ones a consigne can be laid on. */
  protected readonly datesCandidates = computed(() =>
    datesCandidates(this.referentiel.creneaux(), this.aujourdhui()),
  );
  /** The dates still to come that carry a consigne, the only ones that can be lifted. */
  protected readonly datesLevables = computed(() =>
    this.lignes()
      .filter((ligne) => !ligne.passee)
      .map((ligne) => ligne.date),
  );

  constructor() {
    const referentiel = this.crud.reload();
    // `?date=…&nouvelle=1`: a link from the Journée lands here with the form
    // open on that date. Obeyed once, then dropped — see `view-query-params.ts`.
    // A day already begun is not ticked: the server would refuse it, and the
    // form says so rather than letting « Enregistrer » find out.
    // `date=demain` is what « Fermer des stands demain » sends from the Solveur
    // and Aujourd'hui, which do not read the server's day: resolved here,
    // against the day the server says it is (simulated date included).
    consumeQueryParam('nouvelle', async () => {
      await Promise.all([referentiel, this.store.reload()]);
      const date = resolveDateParam(
        this.route.snapshot.queryParamMap.get('date'),
        this.aujourdhui(),
      );
      const existante = date ? this.store.consigneOf(date) : null;
      this.openForm(
        existante ? 'modifier' : 'poser',
        existante,
        date && this.datesCandidates().includes(date) ? [date] : [],
      );
    });
  }

  ngOnInit(): void {
    void this.recharger().then(() => this.dropOrphanDrafts());
  }

  protected async recharger(): Promise<void> {
    await this.store.reload();
  }

  /**
   * The drafts of a consigne lifted meanwhile are dropped, with a word. Only
   * on a successful read: a failed one leaves no consigne to compare with.
   */
  private dropOrphanDrafts(): void {
    if (this.store.error() !== '' || this.store.etat() === null) {
      return;
    }
    reportOrphanDrafts(
      this.notifications,
      this.draftStorage,
      'consigne',
      (recordId) => this.store.consigneOf(consigneDateOf(recordId)) !== null,
      (ids) =>
        $localize`:@@consignes.brouillon.orphelin:La consigne du ${ids.split(', ').map(consigneDateOf).join(', ')}:dates:`,
    );
  }

  protected bandOf(prereglage: PrereglageConsigne): string {
    return bandeLabel(prereglage.fermetureDebut, prereglage.fermetureFin);
  }

  protected windowsOf(prereglage: PrereglageConsigne): string {
    return prereglage.fenetres.map(fenetreLabel).join(', ');
  }

  /** The chip's tooltip, empty when nothing is restated. */
  protected repasOf(repas: RepasConsigne | null): string {
    return repasSurcharge(repas) ? repasLabel(repas) : '';
  }

  /* ------------------------------ the consignes ------------------------------ */

  protected poser(): void {
    this.openForm('poser', null, []);
  }

  protected modifier(ligne: LigneConsigne): void {
    this.openForm('modifier', ligne.consigne, [ligne.date]);
  }

  protected prolonger(ligne: LigneConsigne): void {
    this.openForm('prolonger', ligne.consigne, []);
  }

  private openForm(
    mode: ModeConsigne,
    consigne: ConsigneEdition | null,
    datesInitiales: string[],
  ): void {
    if (this.editingLocked()) {
      return;
    }
    const ref = this.dialog.open<ConsigneFormDialog, ConsigneFormData, ApercuConsigneJour[] | null>(
      ConsigneFormDialog,
      {
        data: {
          mode,
          consigne,
          datesInitiales,
          datesCandidates: this.datesCandidates(),
          prereglages: this.prereglages(),
          stands: this.referentiel.stands(),
          typologies: this.referentiel.typologies(),
          emplacements: this.referentiel.emplacements(),
        },
        width: '56rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((apercu) => {
        if (apercu) {
          this.notifications.notify({
            title: $localize`:@@consignes.posee:Consigne enregistrée sur ${apercu.length}:count: date(s).`,
            variant: 'success',
            timeout: 6000,
          });
          void this.afterWrite();
        }
      });
  }

  /** « Lever » on one row, or « Lever… » on the dates chosen in the dialog. */
  protected lever(ligne: LigneConsigne | null): void {
    if (this.editingLocked()) {
      return;
    }
    const ref = this.dialog.open<ConsigneLeveeDialog, ConsigneLeveeData, boolean>(
      ConsigneLeveeDialog,
      {
        data: { datesLevables: this.datesLevables(), datesInitiales: ligne ? [ligne.date] : [] },
        width: '40rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((levee) => {
        if (levee) {
          this.notifications.notify({
            title: $localize`:@@consignes.levee.faite:Consigne levée.`,
            variant: 'success',
            timeout: 6000,
          });
          void this.afterWrite();
        }
      });
  }

  /** A consigne adds or removes créneaux: the grid the rest of the page reads has moved too. */
  private async afterWrite(): Promise<void> {
    this.changed.emit();
    await Promise.all([this.store.reload(), this.crud.reload()]);
  }

  /* ------------------------------ the presets ------------------------------ */

  protected openPrereglage(prereglage: PrereglageConsigne | null): void {
    if (this.editingLocked()) {
      return;
    }
    const ref = this.dialog.open<PrereglageDialog, PrereglageDialogData, PrereglageConsigne | null>(
      PrereglageDialog,
      { data: { prereglage }, width: '40rem', maxWidth: '95vw', autoFocus: 'first-tabbable' },
    );
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((written) => {
        if (written) {
          void this.store.reload();
        }
      });
  }

  protected async deletePrereglage(prereglage: PrereglageConsigne): Promise<void> {
    if (this.editingLocked()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@consignes.prereglage.supprimer.title:Supprimer le préréglage « ${prereglage.nom}:nom: »`,
      message: $localize`:@@consignes.prereglage.supprimer.message:Les consignes déjà posées à partir de lui ne changent pas.`,
      confirmLabel: $localize`:@@common.delete:Supprimer`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    try {
      await this.api.deletePrereglage(prereglage.id);
      await this.store.reload();
    } catch (error) {
      this.crud.reportError(error);
    }
  }
}
