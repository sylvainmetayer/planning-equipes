import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ImportsApi } from '../../core/api/imports-api';
import { errorMessage } from '../../core/error-message';
import {
  ActionImportReferentiel,
  ReferentielImportTarget,
  RapportImportReferentiel,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';

const CLASSE_ACTION: Record<ActionImportReferentiel, string> = {
  CREE: 'import-ligne-creation',
  MIS_A_JOUR: 'import-ligne-maj',
  REFUSE: 'import-ligne-rejet',
};

const ICONE_ACTION: Record<ActionImportReferentiel, string> = {
  CREE: 'add',
  MIS_A_JOUR: 'edit',
  REFUSE: 'block',
};

/**
 * One referential read from a CSV: pick the file, read what would happen, then
 * and only then write it.
 *
 * <p>The three referentials it serves — typologies, emplacements, stands —
 * differ only by their columns and their words, so they share one component
 * rather than three near-copies. The file is posted as text, twice on purpose
 * (the write re-reads and re-checks it), and never kept on either side.</p>
 */
@Component({
  selector: 'app-import-referentiel',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule],
  templateUrl: './import-referentiel-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportReferentielCard {
  readonly target = input.required<ReferentielImportTarget>();
  /** What the tab says the file must hold, in the words of that referential. */
  readonly colonnes = input.required<string>();
  readonly aide = input.required<string>();

  private readonly api = inject(ImportsApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly store = inject(ReferenceDataStore);

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('csvInput');

  /** The file's text, held only for the lifetime of the screen. */
  private readonly contenu = signal('');
  private derniereAnalyse = 0;

  protected readonly nomFichier = signal('');
  protected readonly rapport = signal<RapportImportReferentiel | null>(null);
  protected readonly erreur = signal('');
  protected readonly analyseEnCours = signal(false);
  protected readonly importEnCours = signal(false);
  protected readonly telechargementEnCours = signal(false);

  protected readonly fichierCharge = computed(() => this.nomFichier() !== '');
  protected readonly peutImporter = computed(
    () =>
      this.fichierCharge() &&
      !this.rapport()?.applied &&
      (this.rapport()?.accepted ?? 0) > 0 &&
      !this.analyseEnCours() &&
      !this.importEnCours(),
  );

  protected classeAction(action: ActionImportReferentiel): string {
    return CLASSE_ACTION[action];
  }

  protected iconeAction(action: ActionImportReferentiel): string {
    return ICONE_ACTION[action];
  }

  protected async telechargerExemple(): Promise<void> {
    this.telechargementEnCours.set(true);
    try {
      await this.api.telechargerExemple(this.target());
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.telechargementEnCours.set(false);
    }
  }

  protected choisirFichier(): void {
    this.fileInput().nativeElement.click();
  }

  protected async onFichierChoisi(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) {
      return;
    }
    this.derniereAnalyse++;
    this.rapport.set(null);
    this.nomFichier.set(file.name);
    this.contenu.set(await file.text());
    await this.analyser();
  }

  /** The preview, numbered so only the answer to the last request is kept. */
  protected async analyser(): Promise<void> {
    if (!this.fichierCharge()) {
      return;
    }
    const numero = ++this.derniereAnalyse;
    this.analyseEnCours.set(true);
    this.erreur.set('');
    try {
      const rapport = await this.api.analyse(this.target(), this.demande());
      if (numero === this.derniereAnalyse) {
        this.rapport.set(rapport);
      }
    } catch (error) {
      if (numero === this.derniereAnalyse) {
        this.rapport.set(null);
        this.erreur.set(errorMessage(error));
      }
    } finally {
      if (numero === this.derniereAnalyse) {
        this.analyseEnCours.set(false);
      }
    }
  }

  protected async importer(): Promise<void> {
    const rapport = this.rapport();
    if (!rapport || !this.peutImporter()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@importRef.confirmer.titre:Confirmer l'import`,
      message: $localize`:@@importRef.confirmer.message:Écrire ${rapport.created}:crees: création(s) et ${rapport.updated}:majs: mise(s) à jour ? Les fiches absentes du fichier ne sont pas touchées, et une colonne que le fichier ne porte pas n'efface rien.`,
    });
    if (!confirme) {
      return;
    }
    this.importEnCours.set(true);
    this.erreur.set('');
    try {
      const applique = await this.api.importer(this.target(), this.demande());
      this.rapport.set(applique);
      await this.store.reload();
      this.notifications.notify({
        title: $localize`:@@importRef.succes.titre:Import terminé`,
        message: $localize`:@@importRef.succes.message:${applique.created}:crees: création(s), ${applique.updated}:majs: mise(s) à jour, ${applique.rejected}:refus: ligne(s) refusée(s).`,
        variant: 'success',
      });
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.importEnCours.set(false);
    }
  }

  protected reinitialiser(): void {
    this.derniereAnalyse++;
    this.analyseEnCours.set(false);
    this.contenu.set('');
    this.nomFichier.set('');
    this.rapport.set(null);
    this.erreur.set('');
  }

  private demande() {
    return { fileName: this.nomFichier(), content: this.contenu() };
  }
}
