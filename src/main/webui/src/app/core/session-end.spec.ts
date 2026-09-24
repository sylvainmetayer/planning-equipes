import { describe, expect, it, vi } from 'vitest';
import { LOGOUT_SIGNAL_KEY, SessionEndBus, announceLogout, onLogoutElsewhere } from './session-end';

/** Two tabs' channels, delivering to each other and not to themselves, like the real one. */
function channelPair(): SessionEndBus['channel'] {
  const channels: EventTarget[] = [];
  return () => {
    const target = new EventTarget();
    channels.push(target);
    return Object.assign(target, {
      postMessage: (data: unknown) =>
        channels
          .filter((other) => other !== target)
          .forEach((other) => other.dispatchEvent(new MessageEvent('message', { data }))),
      close: () => undefined,
    });
  };
}

describe('session-end', () => {
  it('reaches another tab through the channel', () => {
    const bus: SessionEndBus = { channel: channelPair(), storage: null, window: null };
    const onLogout = vi.fn();
    onLogoutElsewhere(onLogout, bus);

    announceLogout(bus);

    expect(onLogout).toHaveBeenCalledOnce();
  });

  it('falls back on a storage event carrying no data but a moment', () => {
    const target = new EventTarget() as unknown as Window;
    const written: string[] = [];
    const bus: SessionEndBus = {
      channel: null,
      storage: {
        setItem: (key) => void written.push(key),
        removeItem: () => undefined,
      },
      window: target,
    };
    const onLogout = vi.fn();
    const unsubscribe = onLogoutElsewhere(onLogout, bus);

    announceLogout(bus);
    expect(written).toEqual([LOGOUT_SIGNAL_KEY]);
    // What the other tab sees: the write, then the removal (newValue null).
    target.dispatchEvent(new StorageEvent('storage', { key: LOGOUT_SIGNAL_KEY, newValue: '1' }));
    target.dispatchEvent(new StorageEvent('storage', { key: LOGOUT_SIGNAL_KEY, newValue: null }));
    target.dispatchEvent(new StorageEvent('storage', { key: 'autre', newValue: '1' }));
    expect(onLogout).toHaveBeenCalledOnce();

    unsubscribe();
    target.dispatchEvent(new StorageEvent('storage', { key: LOGOUT_SIGNAL_KEY, newValue: '2' }));
    expect(onLogout).toHaveBeenCalledOnce();
  });

  it('never throws on a browser that refuses both', () => {
    const refusing: SessionEndBus = {
      channel: () => {
        throw new Error('refusé');
      },
      storage: null,
      window: null,
    };

    expect(() => announceLogout(refusing)).not.toThrow();
    expect(() => onLogoutElsewhere(() => undefined, refusing)()).not.toThrow();
  });
});
