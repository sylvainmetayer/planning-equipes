import { describe, expect, it } from 'vitest';
import { espaceUrl, muralUrl } from './app-links';

describe('app links', () => {
  it('builds the espace and wall-display addresses, the token encoded', () => {
    expect(espaceUrl('https://planning.test', 'a b')).toBe('https://planning.test/animateur/a%20b');
    expect(muralUrl('https://planning.test', 'x/y')).toBe('https://planning.test/mural/x%2Fy');
  });
});
