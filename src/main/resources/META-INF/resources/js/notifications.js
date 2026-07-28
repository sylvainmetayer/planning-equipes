// Non-blocking user feedback: on-page toasts, plus desktop notifications when
// the browser tab is in the background and the user granted the permission.

const TOAST_TIMEOUT_MS = 9000;

let container = null;

function ensureContainer() {
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    container.setAttribute('aria-live', 'polite');
    document.body.appendChild(container);
  }
  return container;
}

export function notify({ title, message, variant = 'info', timeout = TOAST_TIMEOUT_MS, desktop = false }) {
  const toast = document.createElement('div');
  toast.className = `toast toast-${variant}`;

  const heading = document.createElement('strong');
  heading.textContent = title;
  toast.appendChild(heading);

  if (message) {
    const body = document.createElement('span');
    body.textContent = message;
    toast.appendChild(body);
  }

  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'toast-close';
  close.setAttribute('aria-label', 'Dismiss notification');
  close.textContent = '×';
  close.addEventListener('click', () => toast.remove());
  toast.appendChild(close);

  ensureContainer().appendChild(toast);
  if (timeout > 0) {
    setTimeout(() => toast.remove(), timeout);
  }

  if (desktop) {
    showDesktopNotification(title, message);
  }
}

// Asks once, from a user gesture, so a long job can notify a backgrounded tab.
export function requestDesktopPermission() {
  if (!('Notification' in window) || Notification.permission !== 'default') {
    return;
  }
  Notification.requestPermission().catch(() => {
    /* permission prompts can be blocked; toasts remain the fallback */
  });
}

function showDesktopNotification(title, message) {
  if (!('Notification' in window) || Notification.permission !== 'granted') {
    return;
  }
  if (document.visibilityState === 'visible') {
    return;
  }
  try {
    // eslint-disable-next-line no-new
    new Notification(title, { body: message || '' });
  } catch {
    /* some browsers require a service worker; the toast already covered it */
  }
}
