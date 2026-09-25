import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { standNames } from '../../core/reference-labels';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  ImportGrilleAction,
  ImportGrilleDemande,
  ImportGrilleLigne,
  ImportGrilleRapport,
} from '../../core/models';

/**
 * Importing the stand matrix — one row per stand, one column per (date,
 * band), an integer per cell — the way the organiser's own workbook holds it:
 * pick the file, read what would happen, then and only then apply.
 *
 * <p>Same contract as the animateur import: the file is posted as text, twice
 * on purpose (the write re-reads and re-checks it), never written to disk on
 * either side. What differs is the identity (a stand, by code or exact name)
 * and what a column that matches nothing does: it is ignored and listed, not
 * a reason to refuse the file.</p>
 */
@Component({
  selector: 'app-import-grille-stands-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './import-grille-stands-page.html',
  styleUrl: '../../../styles/import-animateurs.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportGrilleStandsPage {
  /** False inside the Imports page, which carries the title of the screen itself. */
  readonly entete = input(true);

  private readonly standsApi = inject(StandsApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly store = inject(ReferenceDataStore);

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('csvInput');

  /** The file's text, held only for the lifetime of the screen. */
  private readonly contenu = signal('');
  private derniereAnalyse = 0;

  protected readonly nomFichier = signal('');
  protected readonly rapport = signal<ImportGrilleRapport | null>(null);
  protected readonly erreur = signal('');
  protected readonly analyseEnCours = signal(false);
  protected readonly importEnCours = signal(false);
  protected readonly telechargementEnCours = signal(false);

  protected readonly fichierCharge = computed(
    () => this.contenu() !== '' && this.rapport() !== null,
  );
  protected readonly colonnesReconnues = computed(() =>
    (this.rapport()?.columns ?? []).filter((colonne) => colonne.creneauId !== null),
  );
  protected readonly colonnesIgnorees = computed(() =>
    (this.rapport()?.columns ?? []).filter((colonne) => colonne.creneauId === null),
  );
  /** A band a staggered grid holds twice: the column carries its cell to each créneau of it. */
  protected readonly peutImporter = computed(
    () =>
      this.fichierCharge() &&
      !this.rapport()?.applied &&
      (this.rapport()?.accepted ?? 0) > 0 &&
      !this.analyseEnCours() &&
      !this.importEnCours(),
  );

  /** Stand id → name: a row names its stand by id, and the file by its own label. */
  private readonly nomsStands = computed(() => standNames(this.store.stands()));

  constructor() {
    // The names of the report's stands; a failure only leaves them unnamed.
    void this.store.reload(['stands']).catch(() => undefined);
  }

  /**
   * The name of the stand a row matched, when it says more than the label the
   * file used (a code, say); empty otherwise — never the generated id.
   */
  protected nomStand(ligne: ImportGrilleLigne): string {
    const nom = ligne.standId ? this.nomsStands().get(ligne.standId) : undefined;
    return nom && nom !== ligne.label ? nom : '';
  }

  protected classeAction(action: ImportGrilleAction): string {
    return action === 'UPDATED' ? 'import-ligne-maj' : 'import-ligne-rejet';
  }

  protected iconeAction(action: ImportGrilleAction): string {
    return action === 'UPDATED' ? 'edit' : 'block';
  }

  protected async telechargerExemple(): Promise<void> {
    this.telechargementEnCours.set(true);
    try {
      await this.standsApi.downloadGridExample();
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
    if (this.nomFichier() === '') {
      return;
    }
    const numero = ++this.derniereAnalyse;
    this.analyseEnCours.set(true);
    this.erreur.set('');
    try {
      const rapport = await this.standsApi.analyseGridImport(this.demande());
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
      title: $localize`:@@importGrille.confirmer.titre:Confirmer l'import`,
      message: $localize`:@@importGrille.confirmer.message:Réécrire l'horaire de ${rapport.accepted}:acceptees: stand(s) depuis le fichier ? Les stands absents du fichier ne sont pas touchés.`,
    });
    if (!confirme) {
      return;
    }
    this.importEnCours.set(true);
    this.erreur.set('');
    try {
      const applique = await this.standsApi.applyGridImport(this.demande());
      this.rapport.set(applique);
      await this.store.reload();
      const regles = applique.rows.reduce((total, ligne) => total + ligne.regles, 0);
      const exceptions = applique.rows.reduce((total, ligne) => total + ligne.exceptions, 0);
      this.notifications.notify({
        title: $localize`:@@importGrille.succes.titre:Import terminé`,
        message: $localize`:@@importGrille.succes.message:${applique.accepted}:stands: stand(s) réécrit(s) en ${regles}:regles: règle(s) et ${exceptions}:exceptions: exception(s) datée(s).`,
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

  private demande(): ImportGrilleDemande {
    return { fileName: this.nomFichier(), content: this.contenu() };
  }
}
