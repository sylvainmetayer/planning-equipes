import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild, ElementRef } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { AnimateurCsvMapping, ImportCsvDemande, ImportCsvRapport } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  CHAMPS_IMPORT,
  ChampImport,
  classeAction,
  iconeAction,
  libelleColonne,
  mappingNommeQuelquun,
  withColonne
} from './import-animateurs';

/**
 * Importing a roster from a spreadsheet export: pick the file, say which
 * column is which, read what would happen, then — and only then — apply.
 *
 * <p>Its own route rather than a section of Paramètres, and that is a
 * convention of this repository rather than a taste: one route is one page is
 * one block. It also earns the page its own URL, which is what an organiser
 * bookmarks the week they receive their file.</p>
 *
 * <p>The file is read in the browser and posted as text. It is posted
 * <b>twice</b>, on purpose: the preview and the import take the same body, and
 * the server re-reads and re-validates the file before writing rather than
 * trusting the report this screen was shown. Nothing here is ever written to
 * disk, on either side (see {@code docs/rgpd.md}).</p>
 */
@Component({
  selector: 'app-import-animateurs-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink
  ],
  templateUrl: './import-animateurs-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ImportAnimateursPage {
  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly store = inject(ReferenceDataStore);

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('csvInput');

  /** The file's text, held only for the lifetime of the screen. */
  private readonly contenu = signal('');

  /** Issue number of the last preview asked for — see {@link analyser}. */
  private derniereAnalyse = 0;

  protected readonly nomFichier = signal('');
  protected readonly mapping = signal<AnimateurCsvMapping | null>(null);
  protected readonly rapport = signal<ImportCsvRapport | null>(null);
  protected readonly erreur = signal('');
  protected readonly analyseEnCours = signal(false);
  protected readonly importEnCours = signal(false);
  protected readonly telechargementEnCours = signal(false);

  protected readonly remplacerAnimateurs = signal(false);
  protected readonly remplacerJours = signal(false);

  protected readonly champs = CHAMPS_IMPORT;

  protected readonly colonnes = computed(() => this.rapport()?.columns ?? []);

  /** True once a file has been read and the server answered at least once. */
  protected readonly fichierCharge = computed(() => this.contenu() !== '' && this.rapport() !== null);

  /**
   * A full replacement over a file that still has a rejected row is refused by
   * the server, always (`AnimateurCsvImportService.apply`). Leaving the button
   * live for it buys the operator a confirmation dialog announcing deletions
   * and then a 400 — the refusal is stated by the checkbox's help text, so it
   * belongs on the button too.
   */
  protected readonly remplacementBloque = computed(
    () => this.remplacerAnimateurs() && (this.rapport()?.rejected ?? 0) > 0
  );

  protected readonly peutImporter = computed(
    () =>
      this.fichierCharge() &&
      !this.rapport()?.applied &&
      mappingNommeQuelquun(this.mapping()) &&
      (this.rapport()?.accepted ?? 0) > 0 &&
      !this.remplacementBloque() &&
      !this.analyseEnCours() &&
      !this.importEnCours()
  );

  protected readonly classeAction = classeAction;
  protected readonly iconeAction = iconeAction;

  protected libelleColonne(index: number): string {
    return libelleColonne(this.colonnes(), index);
  }

  /** Human name of a target field — built here, never at module scope. */
  protected libelleChamp(champ: ChampImport): string {
    switch (champ) {
      case 'id':
        return $localize`:@@importCsv.champ.id:Identifiant`;
      case 'prenom':
        return $localize`:@@importCsv.champ.prenom:Prénom`;
      case 'nom':
        return $localize`:@@importCsv.champ.nom:Nom`;
      case 'dateNaissance':
        return $localize`:@@importCsv.champ.dateNaissance:Date de naissance`;
      case 'email':
        return $localize`:@@importCsv.champ.email:E-mail`;
      case 'manager':
        return $localize`:@@importCsv.champ.manager:Manager`;
      case 'competences':
        return $localize`:@@importCsv.champ.competences:Compétences`;
      case 'souhaits':
        return $localize`:@@importCsv.champ.souhaits:Souhaits`;
      default:
        return $localize`:@@importCsv.champ.joursIndisponibles:Jours d'indisponibilité`;
    }
  }

  protected colonneDe(champ: ChampImport): number | null {
    return this.mapping()?.[champ] ?? null;
  }

  /**
   * Downloads the example roster the application ships with — the nine columns
   * this screen reads, filled with the animateurs of the anonymised
   * `festival-realiste` scenario.
   *
   * Fetched from the API rather than linked as a static asset: the file lives
   * once, on the classpath next to the scenario it derives from, and a backend
   * test re-imports that same resource through the real parser. A copy in
   * `public/` would be a second file to keep true, which is how an example
   * stops matching the format it illustrates.
   */
  protected async telechargerExemple(): Promise<void> {
    this.telechargementEnCours.set(true);
    try {
      await this.api.downloadGet(
        '/api/animateurs/import-csv/exemple',
        'festival-realiste-animateurs.csv',
        'text/csv'
      );
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
    const fichier = input.files?.[0] ?? null;
    input.value = '';
    if (!fichier) {
      return;
    }
    this.derniereAnalyse++;
    this.rapport.set(null);
    this.mapping.set(null);
    this.nomFichier.set(fichier.name);
    this.contenu.set(await fichier.text());
    await this.analyser();
  }

  /**
   * Re-runs the preview: on load, on every mapping change, on every option
   * change.
   *
   * Numbered, and only the answer to the last request issued is kept. Two
   * column changes in a row send two previews, and nothing orders their
   * answers: an early one landing last would put back the mapping it was
   * computed with — over the choice the operator has just made — and
   * `importer()` would then post that stale mapping. On a file of 150 rows
   * that is an import written on the wrong column, behind a preview that
   * agrees with itself.
   *
   * The guard is on the file *name*, not on its text: a 0-byte CSV has no text
   * and still deserves the server's « Le fichier est vide. » rather than a
   * screen that shows the file name and nothing else.
   */
  protected async analyser(): Promise<void> {
    if (this.nomFichier() === '') {
      return;
    }
    const numero = ++this.derniereAnalyse;
    this.analyseEnCours.set(true);
    this.erreur.set('');
    try {
      const rapport = await this.api.post<ImportCsvRapport>(
        '/api/animateurs/import-csv/analyse',
        this.demande()
      );
      if (numero !== this.derniereAnalyse) {
        return;
      }
      this.rapport.set(rapport);
      this.mapping.set(rapport.mapping);
    } catch (error) {
      if (numero !== this.derniereAnalyse) {
        return;
      }
      this.rapport.set(null);
      this.erreur.set(errorMessage(error));
    } finally {
      if (numero === this.derniereAnalyse) {
        this.analyseEnCours.set(false);
      }
    }
  }

  protected async changerColonne(champ: ChampImport, colonne: number | null): Promise<void> {
    const courant = this.mapping();
    if (!courant) {
      return;
    }
    this.mapping.set(withColonne(courant, champ, colonne));
    await this.analyser();
  }

  protected async changerRemplacerAnimateurs(valeur: boolean): Promise<void> {
    this.remplacerAnimateurs.set(valeur);
    await this.analyser();
  }

  protected async changerRemplacerJours(valeur: boolean): Promise<void> {
    this.remplacerJours.set(valeur);
    await this.analyser();
  }

  protected async importer(): Promise<void> {
    const rapport = this.rapport();
    if (!rapport) {
      return;
    }
    const accepted = rapport.accepted;
    const supprimes = rapport.deleted;
    const question = this.remplacerAnimateurs()
      ? $localize`:@@importCsv.confirmer.remplacement:Importer ${accepted}:acceptees: ligne(s) et supprimer ${supprimes}:supprimes: animateur(s) absent(s) du fichier ?`
      : $localize`:@@importCsv.confirmer.ajout:Importer ${accepted}:acceptees: ligne(s) ? Les animateurs absents du fichier sont conservés.`;
    const confirme = await this.confirm.ask({
      title: $localize`:@@importCsv.confirmer.titre:Confirmer l'import`,
      message: question
    });
    if (!confirme) {
      return;
    }
    this.importEnCours.set(true);
    this.erreur.set('');
    try {
      const applique = await this.api.post<ImportCsvRapport>(
        '/api/animateurs/import-csv',
        this.demande()
      );
      this.rapport.set(applique);
      await this.store.reload();
      this.notifications.notify({
        title: $localize`:@@importCsv.succes.titre:Import terminé`,
        message: $localize`:@@importCsv.succes.message:${applique.created}:creations: fiche(s) créée(s), ${applique.updated}:maj: mise(s) à jour, ${applique.deleted}:supprimes: supprimée(s).`,
        variant: 'success'
      });
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.importEnCours.set(false);
    }
  }

  /** Forgets the file — the personal data it carries leaves the browser's memory with it. */
  protected reinitialiser(): void {
    this.derniereAnalyse++;
    this.analyseEnCours.set(false);
    this.contenu.set('');
    this.nomFichier.set('');
    this.mapping.set(null);
    this.rapport.set(null);
    this.erreur.set('');
  }

  private demande(): ImportCsvDemande {
    return {
      fileName: this.nomFichier(),
      content: this.contenu(),
      mapping: this.mapping(),
      replaceAnimateurs: this.remplacerAnimateurs(),
      replaceJoursIndisponibles: this.remplacerJours()
    };
  }
}
