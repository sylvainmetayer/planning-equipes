import { NotificationService, NotifyOptions } from './notification.service';

/**
 * Writes `text` to the clipboard and says how it went: `success` when it did
 * (left out, nothing is shown), `failure` when the browser refused — a silent
 * failure would leave the user believing the text is in their buffer.
 * Answers whether the copy went through.
 */
export async function copyToClipboard(
  notifications: Pick<NotificationService, 'notify'>,
  text: string,
  success: NotifyOptions | null,
  failure: NotifyOptions,
): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    notifications.notify(failure);
    return false;
  }
  if (success) {
    notifications.notify(success);
  }
  return true;
}

/** The animateur's espace link, what the PDF prints — from the roster and from the fiche. */
export function copyEspaceLink(
  notifications: Pick<NotificationService, 'notify'>,
  lien: string,
): Promise<boolean> {
  return copyToClipboard(
    notifications,
    lien,
    {
      title: $localize`:@@animateurs.lienCopie:Lien de l'espace animateur copié.`,
      variant: 'success',
      timeout: 4000,
    },
    {
      title: $localize`:@@animateurs.lienCopieEchec:Impossible de copier le lien`,
      message: lien,
      variant: 'warning',
    },
  );
}
