import { describe, expect, it } from 'vitest';
import { contactOf } from './espace-contact';

describe('contactOf', () => {
  it('reads the phone and the address the view carries', () => {
    expect(contactOf({ contact: { telephone: ' 04 00 ', email: 'orga@example.test' } })).toEqual({
      telephone: '04 00',
      email: 'orga@example.test',
    });
  });

  /** No view, a view without a contact, or an empty one: the espace says what it said before. */
  it('answers null rather than an empty « Organisation : »', () => {
    expect(contactOf(null)).toBeNull();
    expect(contactOf({})).toBeNull();
    expect(contactOf({ contact: { telephone: '  ', email: null } })).toBeNull();
  });
});
