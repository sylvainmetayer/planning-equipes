import { Injectable, signal, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { fakeOf, provideFake } from './fake';

@Injectable({ providedIn: 'root' })
class Greeter {
  readonly name: Signal<string> = signal('Ada');

  greet(who: string, times: number): string {
    return `${who} × ${times}`;
  }

  farewell(): string {
    return 'bye';
  }
}

describe('fakeOf', () => {
  it('turns each method given into the mock of its own signature', () => {
    const greeter = fakeOf<Greeter>({ greet: (who) => `hello ${who}` });

    expect(greeter.greet('Marcel', 2)).toBe('hello Marcel');
    const [who, times] = greeter.greet.mock.calls[0];
    expect(who).toBe('Marcel');
    expect(times).toBe(2);
  });

  it('keeps a mock it is given, and a signal as the signal it was', () => {
    const greet = vi.fn(() => 'hi');
    const name = signal('Proust');
    const greeter = fakeOf<Greeter>({ greet, name });

    expect(greeter.greet).toBe(greet);
    expect(greeter.name).toBe(name);
    expect(greeter.name()).toBe('Proust');
  });

  it('leaves out what the spec did not give, so a call to it fails loudly', () => {
    const greeter = fakeOf<Greeter>({});

    expect(() => greeter.farewell()).toThrow(TypeError);
  });

  it('is what the token injects once provided', () => {
    const greeter = fakeOf<Greeter>({ greet: () => 'provided' });
    TestBed.configureTestingModule({ providers: [provideFake(Greeter, greeter)] });

    expect(TestBed.inject(Greeter).greet('x', 1)).toBe('provided');
    expect(greeter.greet).toHaveBeenCalledWith('x', 1);
  });
});
