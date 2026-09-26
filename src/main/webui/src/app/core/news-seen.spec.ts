import { afterEach, describe, expect, it } from 'vitest';
import { memoryStorage } from './testing/brouillon';
import { NewsSeenService, readNewsUnseen, writeNewsSeen } from './news-seen';

const STORAGE_KEY = 'planning-equipes.nouveautes.vues';

describe('readNewsUnseen', () => {
  it('stays silent on a first visit, and remembers the version seen', () => {
    const storage = memoryStorage();

    expect(readNewsUnseen(storage, 'v2')).toBe(false);
    expect(readNewsUnseen(storage, 'v2')).toBe(false);
  });

  it('lights up once the running version differs from the one read', () => {
    const storage = memoryStorage();
    readNewsUnseen(storage, 'v1');

    expect(readNewsUnseen(storage, 'v2')).toBe(true);
    writeNewsSeen(storage, 'v2');
    expect(readNewsUnseen(storage, 'v2')).toBe(false);
  });

  // #706: never blocking when the storage is unavailable.
  it('never throws, and shows nothing, when the storage refuses', () => {
    const refusing = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };

    expect(readNewsUnseen(refusing, 'v2')).toBe(false);
    expect(() => writeNewsSeen(refusing, 'v2')).not.toThrow();
    expect(readNewsUnseen(null, 'v2')).toBe(false);
  });
});

describe('NewsSeenService', () => {
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  // The marker next to Aide and the dot at the foot of the menu both read
  // `unseen`; only the news page, through `markSeen`, turns them off.
  it('turns the marker off once the news are open, for this visit and the next', () => {
    localStorage.setItem(STORAGE_KEY, 'une-version-precedente');
    const service = new NewsSeenService();
    expect(service.unseen()).toBe(true);

    service.markSeen();

    expect(service.unseen()).toBe(false);
    expect(new NewsSeenService().unseen()).toBe(false);
  });
});
