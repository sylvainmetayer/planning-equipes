import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { errorMessage } from '../../core/error-message';
import { QrCodeView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { qrPath } from '../../core/qr-path';

/** A link created from Aujourd'hui: the one moment its address can be shown. */
interface LienTv {
  url: string;
  qr: QrCodeView | null;
  chemin: string;
}

/**
 * « Afficher sur la TV »: the wall display of the control room (ADR 0053),
 * reached from the day's hub rather than from the Paramètres.
 *
 * The server keeps a hash of each token and nothing else, so an existing link
 * cannot be shown again: the panel says how many screens are already linked and
 * creates a new link — whole edition, initials — whose QR code the television
 * (or the phone that sets it up) reads. Managing and revoking stay in the
 * Paramètres.
 */
@Component({
  selector: 'app-aujourdhui-tv',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  templateUrl: './aujourdhui-tv.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AujourdhuiTv {
  private readonly api = inject(AffichageMuralApi);
  private readonly notifications = inject(NotificationService);

  protected readonly ouvert = signal(false);
  /** Links already created, `null` until read. */
  protected readonly existingCount = signal<number | null>(null);
  protected readonly lien = signal<LienTv | null>(null);
  protected readonly busy = signal(false);

  protected async open(): Promise<void> {
    this.ouvert.set(true);
    try {
      const liens = await this.api.list();
      this.existingCount.set(liens.length);
      if (liens.length === 0) {
        // Nothing to show on the television yet: the link is created here.
        await this.create();
      }
    } catch (error) {
      this.notifyError(error);
    }
  }

  protected close(): void {
    this.ouvert.set(false);
    this.lien.set(null);
  }

  protected async create(): Promise<void> {
    this.busy.set(true);
    try {
      const created = await this.api.create({
        libelle: $localize`:@@aujourdhui.tv.libelle:TV (Aujourd'hui)`,
        fullNames: false,
        emplacements: [],
      });
      const url = `${window.location.origin}/mural/${encodeURIComponent(created.token)}`;
      let qr: QrCodeView | null = null;
      try {
        qr = await this.api.qrCode(url);
      } catch {
        // The address alone still does the job; the code is a convenience.
      }
      this.lien.set({ url, qr, chemin: qr ? qrPath(qr) : '' });
      this.existingCount.update((nombre) => (nombre ?? 0) + 1);
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.busy.set(false);
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
