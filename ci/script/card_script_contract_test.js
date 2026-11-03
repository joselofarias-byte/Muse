#!/usr/bin/env node
/**
 * card_script_contract_test.js — Prueba de contrato de script de tarjeta "suscripción de datos".
 *
 * ## por qué es necesario
 *
 * El script de la tarjeta es una cadena JS inyectada en WebView (`CardScript.FACADE_JS`). decide
 * Método de actualización de los datos de vinculación de la tarjeta: el evento se activará cuando el host pueda enviarlo (se activará solo cuando se produzca el cambio) y se devolverá cuando no se pueda enviar.
 * 800ms Encuesta. Esta lógica no puede ser cubierta por ninguna prueba única de Kotlin. —— El pase de compilación no significa que el comportamiento sea correcto.
 * Y una vez que degenere en "encuesta pase lo que pase", cada tarjeta tendrá un temporizador permanente.
 *
 * Aquí, extraiga el JS en Kotlin, ejecute dos modos en el entorno limitado de vm de Node y afirme:
 *   1. Modo push: no crea ningún intervalo, solo devuelve la llamada cuando cambian los datos, las notificaciones repetidas no repiten las devoluciones de llamada;
 *   2. Modo de sondeo: cree un intervalo de 800 ms y devuelva la llamada solo cuando el valor cambie;
 *   3. No habrá devolución de llamada después de cancelar la suscripción.
 *
 * ## Uso (no conectado a carriles CI, ejecución manual)
 *
 *     node ci/script/card_script_contract_test.js
 *
 * código de salida 0 = Todo pasó. Imprima afirmaciones específicas sobre el fracaso.
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

function extractFacadeJs() {
  const source = fs.readFileSync(SCRIPT_KT, 'utf8');
  const match = source.match(/private const val FACADE_JS: String = """([\s\S]*?)"""/);
  if (!match) throw new Error('CardScript.kt no puedo encontrarlo FACADE_JS');
  return match[1];
}

function makeSandbox(pushEnabled, initialData) {
  const intervals = [];
  const timeouts = [];
  const state = { data: initialData };
  const bridge = {
    getCardId: () => 'card-1',
    capabilities: () =>
      JSON.stringify({
        emit: 'available',
        state: 'available',
        data: 'available',
        height: 'available',
        cardId: 'card-1',
      }),
    getData: () => state.data,
    send: () => {},
    sendWithData: () => {},
  };
  const sandbox = {
    JSON,
    Date,
    String,
    Boolean,
    Number,
    Math,
    console,
    MuseCardBridge: bridge,
    document: { addEventListener: () => {}, body: {} },
    setTimeout: (fn) => {
      timeouts.push(fn);
      return timeouts.length;
    },
    setInterval: (fn, ms) => {
      intervals.push({ fn, ms });
      return intervals.length;
    },
    clearInterval: () => {},
  };
  sandbox.window = sandbox;
  sandbox.window.__museDataPush = pushEnabled;
  vm.createContext(sandbox);
  vm.runInContext(extractFacadeJs(), sandbox);
  return { sandbox, state, intervals, timeouts };
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

// ---------- 1. modo de empuje ----------
{
  console.log('[push mode]');
  const { sandbox, state, intervals, timeouts } = makeSandbox(true, 'A');
  const seen = [];
  const unsubscribe = sandbox.window.muse.data.onChange((value) => seen.push(value));

  check('No crea una encuesta de 800 ms', intervals.length === 0, `realmente creado ${intervals.length} un`);
  check('Leer una vez al suscribirse (asincrónico)', timeouts.length === 1);
  timeouts[0]();
  check('Lectura complementaria para obtener el valor actual.', seen.length === 1 && seen[0] === 'A', JSON.stringify(seen));

  state.data = 'B';
  sandbox.window.__museDataEvent();
  check('Devolución de llamada nuevo valor después de cambios de datos', seen.length === 2 && seen[1] === 'B', JSON.stringify(seen));

  sandbox.window.__museDataEvent();
  check('No repita la devolución de llamada cuando el valor no haya cambiado', seen.length === 2, JSON.stringify(seen));

  state.data = 'C';
  sandbox.window.__museDataEvent();
  check('Cambiar de nuevo y seguir llamando', seen.length === 3 && seen[2] === 'C', JSON.stringify(seen));

  unsubscribe();
  state.data = 'D';
  sandbox.window.__museDataEvent();
  check('No hay devolución de llamada después de cancelar la suscripción', seen.length === 3, JSON.stringify(seen));
}

// ---------- 2. Modo de sondeo (bajar de categoría cuando no hay capacidad de envío) ----------
{
  console.log('[poll mode]');
  const { sandbox, state, intervals } = makeSandbox(false, 'A');
  const seen = [];
  const unsubscribe = sandbox.window.muse.data.onChange((value) => seen.push(value));

  check('Crear encuesta de 800 ms', intervals.length === 1 && intervals[0].ms === 800, JSON.stringify(intervals.map((i) => i.ms)));

  intervals[0].fn();
  check('Obtener el valor actual en la primera encuesta.', seen.length === 1 && seen[0] === 'A', JSON.stringify(seen));

  intervals[0].fn();
  check('No repita la devolución de llamada cuando el valor no haya cambiado', seen.length === 1, JSON.stringify(seen));

  state.data = 'B';
  intervals[0].fn();
  check('Devolución de llamada después de cambios de valor', seen.length === 2 && seen[1] === 'B', JSON.stringify(seen));

  unsubscribe();
  check('Darse de baja sin lanzar una excepción', true);
}

// ---------- 3. No tirar cuando el puente no esté disponible. ----------
{
  console.log('[no bridge]');
  const sandbox = {
    JSON,
    Date,
    String,
    Boolean,
    Number,
    Math,
    console,
    document: { addEventListener: () => {}, body: {} },
    setTimeout: () => 0,
    setInterval: () => 0,
    clearInterval: () => {},
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  let threw = false;
  try {
    vm.runInContext(extractFacadeJs(), sandbox);
    const off = sandbox.window.muse.data.onChange(() => {});
    off();
  } catch (error) {
    threw = true;
    console.log(`    ${error.message}`);
  }
  check('Las páginas sin puente no generan excepciones', !threw);
  check('La capacidad sin puente está marcada como requires_host', sandbox.window.muse.capabilities().emit === 'requires_host');
}

console.log('');
if (failures.length > 0) {
  console.error(`FAILED: ${failures.length} artículo`);
  process.exit(1);
}
console.log('card script contract: PASS');
