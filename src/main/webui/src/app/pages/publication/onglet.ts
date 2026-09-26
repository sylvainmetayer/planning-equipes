// The tabs of Diffuser, and the values of its `onglet` query param.

export type OngletDiffuser = 'envoyer' | 'documents';

/** Reads `onglet`; anything unknown is « Envoyer », the tab the screen opens on. */
export function readOngletDiffuser(value: string | null): OngletDiffuser {
  return value === 'documents' ? 'documents' : 'envoyer';
}
