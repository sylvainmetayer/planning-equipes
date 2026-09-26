import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
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
import { GelNotice } from '../../shared/gel-notice';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { FreezeFamily } from '../../core/models';
import { CollageTableur } from './collage-tableur';
import { PASTE_FILE_NAME } from './collage';
import { importedRowIds } from './imported-rows';
import { IMPORTED_IDS_PARAM } from '../../core/imported-rows';

/** The referential screen a file of this target fills: where « Voir les lignes importées » leads. */
export function referentialRouteOf(target: ReferentielImportTarget): string {
  switch (target) {
    case 'TYPOLOGIES':
      return '/typologies';
    case 'EMPLACEMENTS':
      return '/emplacements';
    case 'STANDS':
      return '/stands';
    case 'CRENEAUX':
    case 'JOURNEES_TYPES':
      return '/creneaux';
  }
}

/** « Voir les stands »: the link when the imported rows cannot be singled out on that screen. */
export function referentialLinkLabel(target: ReferentielImportTarget): string {
  switch (target) {
    case 'TYPOLOGIES':
      return $localize`:@@importRef.voir.typologies:Voir les typologies`;
    case 'EMPLACEMENTS':
      return $localize`:@@importRef.voir.emplacements:Voir les emplacements`;
    case 'STANDS':
      return $localize`:@@importRef.voir.stands:Voir les stands`;
    case 'CRENEAUX':
      return $localize`:@@importRef.voir.creneaux:Voir les créneaux`;
    case 'JOURNEES_TYPES':
      return $localize`:@@importRef.voir.journeesTypes:Voir les journées types`;
  }
}

/** The family a file of this referential writes (ADR 0052); the day templates are none. */
export function frozenFamilyOf(target: ReferentielImportTarget): FreezeFamily | null {
  switch (target) {
    case 'TYPOLOGIES':
    case 'EMPLACEMENTS':
      return 'TYPOLOGIES_EMPLACEMENTS';
    case 'STANDS':
      return 'STANDS';
    case 'CRENEAUX':
      return 'CRENEAUX';
    case 'JOURNEES_TYPES':
      return null;
  }
}

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
 * <p>The referentials it serves — typologies, emplacements, stands,
 * timeslots, day templates — differ only by their columns and their words, so
 * they share one component rather than five near-copies. The file — or the
 * cells pasted from a spreadsheet — is posted as text, twice on purpose (the
 * write re-reads and re-checks it), and never kept on either side.</p>
 */
@Component({
  selector: 'app-import-referentiel',
  imports: [
    CollageTableur,
    GelNotice,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './import-referentiel-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportReferentielCard {
  readonly target = input.required<ReferentielImportTarget>();
  /** What the tab says the file must hold, in the words of that referential. */
  readonly colonnes = input.required<string>();
  readonly aide = input.required<string>();
  /**
   * False inside the Importer dialog of the referential screen itself: the
   * rows are then on the page the dialog closes onto, and a link to it would
   * lead nowhere new.
   */
  readonly lienReferentiel = input(true);
  /** A write went through: the host reloads what it shows. */
  readonly imported = output<void>();

  private readonly api = inject(ImportsApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly store = inject(ReferenceDataStore);
  private readonly gel = injectGelReferentiel();

  /** The family this file would write, when a freeze can cover it. */
  protected readonly family = computed(() => frozenFamilyOf(this.target()));
  /** The server refuses the whole file while its family is frozen: the screen says so before. */
  protected readonly frozen = computed(() => {
    const family = this.family();
    return family !== null && this.gel.isFrozen(family);
  });

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
  /**
   * Once a write went through: the screen its rows live on, opened on those
   * rows alone (`?ids=`) — or on the whole list, said so, when they cannot be
   * singled out there (the day templates). `null` before, and when the write
   * created and updated nothing.
   */
  protected readonly lienLignes = computed(() => {
    const rapport = this.rapport();
    if (!rapport?.applied || rapport.created + rapport.updated === 0) {
      return null;
    }
    const target = this.target();
    const route = referentialRouteOf(target);
    const ids = importedRowIds(target, rapport.rows, {
      typologies: this.store.typologies(),
      emplacements: this.store.emplacements(),
      stands: this.store.stands(),
      creneaux: this.store.creneaux(),
    });
    if (ids === null || ids.length === 0) {
      return { route, queryParams: null, libelle: referentialLinkLabel(target) };
    }
    const count = ids.length;
    return {
      route,
      queryParams: { [IMPORTED_IDS_PARAM]: ids.join(',') },
      libelle: $localize`:@@importRef.voirLignes:Voir les ${count}:count: lignes importées`,
    };
  });
  protected readonly peutImporter = computed(
    () =>
      this.fichierCharge() &&
      !this.rapport()?.applied &&
      (this.rapport()?.accepted ?? 0) > 0 &&
      !this.analyseEnCours() &&
      !this.importEnCours() &&
      !this.frozen(),
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
    await this.load(file.name, await file.text());
  }

  /** Cells pasted from a spreadsheet: read exactly as a file of that content would be. */
  protected onColle(csv: string): Promise<void> {
    return this.load(PASTE_FILE_NAME, csv);
  }

  /**
   * A text to read, whichever way it came in — a file or a paste: the last
   * answer forgotten, the text held under its name, then previewed.
   */
  private async load(name: string, content: string): Promise<void> {
    this.derniereAnalyse++;
    this.rapport.set(null);
    this.nomFichier.set(name);
    this.contenu.set(content);
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
      this.imported.emit();
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
