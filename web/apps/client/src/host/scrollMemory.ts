/**
 * Memoria de posición de desplazamiento de sesión (sólo memoria).
 *
 * Al cambiar de sesión y volver a cambiar, no debe volver a la cima, ni debe verse obligado a permanecer en la parte inferior como si "el usuario estuviera en la parte inferior".——
 * Por lo tanto, aquí solo se almacenan números y el tiempo de recuperación del desplazamiento lo determina la propia página de chat (el contenido se eliminará antes de cambiarlo).
 */
const MAX_ENTRIES = 200;

export class ScrollMemory {
  private readonly tops = new Map<string, number>();

  remember(sessionId: string, top: number): void {
    if (!Number.isFinite(top) || top < 0) return;
    this.tops.set(sessionId, top);
    // Cuando haya muchas sesiones abiertas, exprime los primeros recuerdos y no dejes que la mesa crezca infinitamente.
    while (this.tops.size > MAX_ENTRIES) {
      const oldest = this.tops.keys().next();
      if (oldest.done === true) break;
      this.tops.delete(oldest.value);
    }
  }

  recall(sessionId: string): number | undefined {
    return this.tops.get(sessionId);
  }

  forget(sessionId: string): void {
    this.tops.delete(sessionId);
  }

  size(): number {
    return this.tops.size;
  }
}
