#!/usr/bin/env node
/**
 * card_observer_contract_test.js — Prueba de contrato del guión de observación de la altura de la tarjeta.
 *
 * ## por qué es necesario
 *
 * La observación de altura es una pieza de JS inyectada en la tarjeta WebView (`CardScript.observerJs()`). decide
 * ¿Cuándo se informará al anfitrión de la altura de la tarjeta? Esta lógica tiene tres reglas que se restringen mutuamente:
 *   1. Se debe informar la primera medición (de lo contrario se cortará la tarjeta);
 *   2. Si aumenta su altura, se debe informar inmediatamente; si se acorta, debe ser consistente dos veces seguidas para ser creído (evitando el estado intermedio de la animación de plegado);
 *   3. Después del primer informe,150/400/900ms Los tres temporizadores de bolsillo ya no deberían activar mediciones
 *      —— Cada medida forzará un diseño, y es un puro desperdicio cuando hay demasiadas tarjetas.
 *
 * El artículo 3 se agregó recientemente en la rectificación de desempeño del 1 de noviembre de 2026 y es muy probable que se elimine en cambios posteriores.
 * `setTimeout(schedule, ...)`, y después de la eliminación, la función es completamente normal y ninguna prueba aparecerá en rojo.
 *
 * ## Uso (no conectado a carriles CI, ejecución manual)
 *
 *     node ci/script/card_observer_contract_test.js
 */

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const SCRIPT_KT = path.join(
  PROJECT_ROOT,
  'app/src/main/java/io/zer0/muse/ui/markdown/card/CardScript.kt',
);

function extractObserverJs() {
  const source = fs.readFileSync(SCRIPT_KT, 'utf8');
  const match = source.match(/private fun observerJs\(\): String \{\s*return """([\s\S]*?)"""\.trimIndent\(\)/);
  if (!match) throw new Error('CardScript.kt no puedo encontrarlo observerJs');
  return match[1];
}

/** Cree un entorno de página controlable: altura, temporizador,ResizeObserver Todos se pueden conducir manualmente. */
function makeSandbox(initialHeight) {
  const state = { height: initialHeight, reported: [], timers: [], observers: [], looper: null };
  const sink = {
    reportHeight: (px) => state.reported.push(px),
  };
  const body = {
    getBoundingClientRect: () => ({ height: state.height }),
    // Debe usar captador:measure() Se toma el valor mayor de "altura del cuadro" y "altura del contenido".
    // Si el valor de la instantánea se escribe aquí, la altura medida siempre será la altura inicial y la ruta de contracción no se medirá en absoluto.
    get scrollHeight() {
      return state.height;
    },
    get offsetHeight() {
      return state.height;
    },
  };
  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      state.observers.push(this);
    }
    observe() {}
    fire() {
      this.callback();
    }
  }
  const sandbox = {
    JSON,
    Date,
    Math,
    console,
    MuseHeightSink: sink,
    document: { body },
    window: {
      innerWidth: 360,
      innerHeight: 640,
      addEventListener: (name, handler) => {
        if (name === 'load') state.looper = handler;
      },
    },
    ResizeObserver: FakeResizeObserver,
    setTimeout: (fn) => {
      state.timers.push(fn);
      return state.timers.length;
    },
  };
  sandbox.window.window = sandbox.window;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(extractObserverJs(), sandbox);
  return { sandbox, state };
}

const failures = [];
function check(name, condition, detail) {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
    failures.push(name);
  }
}

/** Ejecutar temporizadores en cola en secuencia (simulando avance de tiempo). */
function runTimers(state, count) {
  const running = state.timers.splice(0, count ?? state.timers.length);
  running.forEach((fn) => fn());
}

// ---------- 1. primera medición ----------
{
  console.log('[first measure]');
  const { state } = makeSandbox(200);
  check('Se programa una medición al inicio', state.timers.length >= 1, `timers=${state.timers.length}`);
  runTimers(state, 1); // start adentro schedule → evaluate de 80ms
  runTimers(state); // ejecución real evaluate
  check('Informe la primera medición inmediatamente', state.reported.length === 1 && state.reported[0] === 200, JSON.stringify(state.reported));
}

// ---------- 2. Hazte más alto al instante, acorta dos veces ----------
{
  console.log('[growth and shrink]');
  const { state } = makeSandbox(200);
  runTimers(state, 1);
  runTimers(state);
  const observer = state.observers[0];
  check('Registrado ResizeObserver', !!observer);

  state.height = 260;
  observer.fire();
  runTimers(state);
  check('Informe inmediatamente si aumenta', state.reported[state.reported.length - 1] === 260, JSON.stringify(state.reported));

  state.height = 180;
  observer.fire();
  runTimers(state);
  check('La primera vez que me volví más bajo, no lo denuncié.', state.reported[state.reported.length - 1] === 260, JSON.stringify(state.reported));

  observer.fire();
  runTimers(state);
  check('Informe después de acortarse dos veces seguidas.', state.reported[state.reported.length - 1] === 180, JSON.stringify(state.reported));

  state.height = 182;
  observer.fire();
  runTimers(state);
  check('8px No se informarán las fluctuaciones dentro del rango', state.reported[state.reported.length - 1] === 180, JSON.stringify(state.reported));
}

// ---------- 3. El cronómetro de bolsillo ya no se medirá después del primer informe (nuevo en esta ronda) ----------
{
  console.log('[fallback timers]');
  const { state } = makeSandbox(300);
  runTimers(state, 1);
  runTimers(state);
  check('Primer informe completado', state.reported.length === 1, JSON.stringify(state.reported));

  // Borre la cola y los valores restantes deben ser 150/400/900 + cargar devolución de llamada
  const before = state.reported.length;
  state.timers.splice(0).forEach((fn) => fn());
  if (state.looper) state.looper();
  // Si Doubi todavía está programando, se agregará un nuevo temporizador de evaluación a la cola.
  const scheduled = state.timers.length;
  runTimers(state);

  check(
    'Después del primer informe, no se programarán más mediciones.',
    scheduled === 0,
    `todo esta planeado ${scheduled} veces`,
  );
  check('No se generaron informes adicionales', state.reported.length === before, JSON.stringify(state.reported));
}
console.log('');
if (failures.length > 0) {
  console.error(`FAILED: ${failures.length} artículo`);
  process.exit(1);
}
console.log('card observer contract: PASS');