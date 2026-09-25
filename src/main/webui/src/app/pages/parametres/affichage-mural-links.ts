import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { errorMessage } from '../../core/error-message';
import { AffichageMuralLink, QrCodeView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';

/** A link just created: the one moment its address can be copied. */
interface CreatedLink {
  url: string;
  libelle: string;
  qr: QrCodeView | null;
}

/**
 * The Paramètres tab of the wall display (ADR 0053): the links that open the
 * control room's television without an admin session — create, copy or show
 * as a QR code, list with the last read, revoke.
 *
 * The server keeps a hash of each token and nothing else, so the address is
 * shown once, right after its creation; a lost address is a new link.
 */
@Component({
  selector: 'app-affichage-mural-links',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTooltipModule,
  ],
  templateUrl: './affichage-mural-links.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AffichageMuralLinks implements OnInit {
  private readonly api = inject(AffichageMuralApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  protected readonly referenceData = inject(ReferenceDataStore);

  protected readonly colonnes = [
    'libelle',
    'noms',
    'emplacements',
    'createdAt',
    'lastAccessAt',
    'actions',
  ];

  protected readonly liens = signal<AffichageMuralLink[]>([]);
  protected readonly created = signal<CreatedLink | null>(null);
  protected readonly busy = signal(false);

  protected libelle = '';
  protected fullNames = false;
  protected emplacements: string[] = [];

  private readonly nomsEmplacements = computed(
    () => new Map(this.referenceData.emplacements().map((each) => [each.id, each.nom])),
  );

  ngOnInit(): void {
    void this.load();
    if (this.referenceData.emplacements().length === 0) {
      void this.referenceData.reload(['emplacements']);
    }
  }

  protected emplacementsOf(lien: AffichageMuralLink): string {
    if (!lien.restricted) {
      return $localize`:@@parametres.mural.touteEdition:Toute l'édition`;
    }
    if (lien.emplacements.length === 0) {
      return $localize`:@@parametres.mural.plusAucun:Aucun (supprimés depuis)`;
    }
    const noms = this.nomsEmplacements();
    return lien.emplacements.map((id) => noms.get(id) ?? id).join(', ');
  }

  /** The QR code's dark modules as one SVG path, one unit square per module. */
  protected cheminQr(qr: QrCodeView): string {
    const segments: string[] = [];
    qr.rows.forEach((row, y) => {
      for (let x = 0; x < row.length; x++) {
        if (row[x] === '1') {
          segments.push(`M${x} ${y}h1v1h-1z`);
        }
      }
    });
    return segments.join('');
  }

  protected async create(): Promise<void> {
    if (!this.libelle.trim()) {
      return;
    }
    this.busy.set(true);
    try {
      const created = await this.api.create({
        libelle: this.libelle.trim(),
        fullNames: this.fullNames,
        emplacements: this.emplacements,
      });
      const url = `${window.location.origin}/mural/${encodeURIComponent(created.token)}`;
      let qr: QrCodeView | null = null;
      try {
        qr = await this.api.qrCode(url);
      } catch {
        // The address alone still does the job; the code is a convenience.
      }
      this.created.set({ url, libelle: created.link.libelle, qr });
      this.libelle = '';
      this.fullNames = false;
      this.emplacements = [];
      await this.load();
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async copy(url: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(url);
      this.notifications.notify({
        title: $localize`:@@parametres.mural.copie:Adresse copiée.`,
        variant: 'success',
        timeout: 3000,
      });
    } catch {
      this.notifications.notify({
        title: $localize`:@@parametres.mural.copieImpossible:Copie impossible : sélectionnez l'adresse à la main.`,
        variant: 'warning',
      });
    }
  }

  protected async revoquer(lien: AffichageMuralLink): Promise<void> {
    const ok = await this.confirm.ask({
      title: $localize`:@@parametres.mural.revoquerTitre:Révoquer ce lien ?`,
      message: $localize`:@@parametres.mural.revoquerMessage:L'écran « ${lien.libelle}:libelle: » affichera un lien mort à sa prochaine lecture.`,
      confirmLabel: $localize`:@@parametres.mural.revoquer:Révoquer`,
      danger: true,
    });
    if (!ok) {
      return;
    }
    try {
      await this.api.revoke(lien.id);
      await this.load();
    } catch (error) {
      this.notifyError(error);
    }
  }

  private async load(): Promise<void> {
    try {
      this.liens.set(await this.api.list());
    } catch (error) {
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
