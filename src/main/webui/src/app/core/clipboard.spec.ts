import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyToClipboard } from './clipboard';

function clipboard(writeText: () => Promise<void>): void {
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
}

describe('copyToClipboard', () => {
  const notify = vi.fn();
  afterEach(() => notify.mockReset());

  it('says it copied, and answers true', async () => {
    clipboard(() => Promise.resolve());
    const copied = await copyToClipboard({ notify }, 'texte', { title: 'ok' }, { title: 'ko' });
    expect(copied).toBe(true);
    expect(notify).toHaveBeenCalledExactlyOnceWith({ title: 'ok' });
  });

  it('shows nothing on success when no success message is given', async () => {
    clipboard(() => Promise.resolve());
    expect(await copyToClipboard({ notify }, 'texte', null, { title: 'ko' })).toBe(true);
    expect(notify).not.toHaveBeenCalled();
  });

  it('reports a refused clipboard instead of rejecting', async () => {
    clipboard(() => Promise.reject(new Error('denied')));
    const copied = await copyToClipboard({ notify }, 'texte', { title: 'ok' }, { title: 'ko' });
    expect(copied).toBe(false);
    expect(notify).toHaveBeenCalledExactlyOnceWith({ title: 'ko' });
  });
});
