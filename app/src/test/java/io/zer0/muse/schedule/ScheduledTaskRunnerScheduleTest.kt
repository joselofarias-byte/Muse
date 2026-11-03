package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R-TEST-11: La siguiente hora de activación de la tarea programada + la prueba de lógica pura del mensaje activo no molesta la ventana.
 */
class ScheduledTaskRunnerScheduleTest {

    @Test
    fun `interval modes compute fixed offsets`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3_600_000L, ScheduledTaskRunner.computeNextRun("hourly", "", now))
        assertEquals(now + 86_400_000L, ScheduledTaskRunner.computeNextRun("daily", "", now))
        assertEquals(now + 604_800_000L, ScheduledTaskRunner.computeNextRun("weekly", "", now))
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("once", "", now))
    }

    // ── once Semántica ─────────────────────────────────────────────────────
    // La "próxima ejecución" y el "primer tiempo de ejecución" son dos cuestiones,Compartiendo la misma implementación de intervalo:
    //   computeNextRun   = ¿Hay una próxima vez después de esta ejecución? → once para 0(No la próxima vez)
    //   initialNextRunAt = Recién construido/¿Cuándo se realizará la primera ejecución después de cambiar el intervalo? → once ahora es + un ciclo de votación
    // 0 No "ejecutar de nuevo ahora mismo",ScheduledTaskDao.recordExecutionAndScheduleNext ver <=0 voluntad
    // setEnabled(false),Entonces, una vez que se ejecuta la tarea, sale del cronograma.,No será descontado para la segunda ejecución.

    @Test
    fun `once has no next run after it executes`() {
        val now = 1_700_000_000_000L
        // No tiene ningún efecto ya sea con o sin expresión cron.:once no mires cron
        assertEquals("once No hay próxima ejecución", 0L, ScheduledTaskRunner.computeNextRun("once", "", now))
        assertEquals("once No hay próxima ejecución", 0L, ScheduledTaskRunner.computeNextRun("once", "0 9 * * *", now))
        // El intervalo desconocido tiene el mismo calibre que una vez:No hay próxima ejecución(No más retorno "+1 día" o "+1 minutos)
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("every-other-day", "", now))
    }

    @Test
    fun `once first run waits one poll interval`() {
        val now = 1_700_000_000_000L
        // No te pierdas la primera carrera 0(0 ¿Está la marca deshabilitada después de la ejecución?),Tampoco puedo caer now —— Dejar caer ahora provocará el siguiente análisis.
        // (0–60s,Lo peor es el salto inmediatamente después.)fue recibido,Después de que el usuario haga clic en "Crear", verá que la tarea se ha completado.
        // Dejar un ciclo de sondeo para almacenamiento en búfer,Mismo origen que POLL_INTERVAL_MS del programador.
        assertEquals(now + 60_000L, ScheduledTaskRunner.initialNextRunAt("once", "", now))
        assertTrue("El tiempo de la primera ejecución debe ser positivo.,entrar getDueTasks(next_run_at <= now)", ScheduledTaskRunner.initialNextRunAt("once", "", now) > 0)
    }

    @Test
    fun `recurring first run keeps the fixed offset`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3_600_000L, ScheduledTaskRunner.initialNextRunAt("hourly", "", now))
        assertEquals(now + 86_400_000L, ScheduledTaskRunner.initialNextRunAt("daily", "", now))
        assertEquals(now + 604_800_000L, ScheduledTaskRunner.initialNextRunAt("weekly", "", now))
    }

    @Test
    fun `cron blank or invalid degrades to once`() {
        val now = 1_700_000_000_000L
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("cron", "", now))
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("cron", "not-a-cron", now))
    }

    @Test
    fun `cron computes next daily run`() {
        val base = LocalDateTime.of(2026, 8, 6, 9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("30 9 * * *").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 8, 6, 9, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `cron every 15 minutes crosses into next hour`() {
        val base = LocalDateTime.of(2026, 8, 6, 9, 50)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("*/15 * * * *").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 8, 6, 10, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `cron dom and dow both restricted use OR semantics`() {
        // B-13: Quartz normativo — "0 0 1 * 1" = 1 de cada mes o cada lunes(OR),
        // La antigua implementación de AND resultó en sólo"Es tanto el 1 como el lunes."desencadenar.
        // 2026-08-06 es jueves,El próximo lunes es 08-10;El siguiente numero 1 es 09-01 → Expectativas 08-10.
        val base = LocalDateTime.of(2026, 8, 6, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("0 0 1 * 1").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 8, 10, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals("dom+dow La doble calificación debe O igualar", expected, next)
    }

    @Test
    fun `cron dom restricted only uses AND with wildcard dow`() {
        // "0 0 1 * *" = 1 de cada mes(dow para * No participa en el cambio Y/O)
        val base = LocalDateTime.of(2026, 8, 6, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("0 0 1 * *").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 9, 1, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `cron impossible expression returns NO_MATCH not claim sentinel`() {
        // Revisión de corrección (2.0 C-06): No hay ninguna coincidencia en la ventana de escaneo. cron(Al 30 de febrero)debe regresar
        // CronExpression.NO_MATCH(= Long.MAX_VALUE - 1),no debe usarse con ScheduledTaskRunner
        // Obtener centinela(CLAIM_NEXT_RUN_SENTINEL = Long.MAX_VALUE)Mezclar —
        // El uso mixto será"cron No hay coincidencia"Mal juzgado como"La tarea ha sido recibida.",La tarea nunca se vuelve a ejecutar.
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("0 0 30 2 *").nextRunAfter(base)
        assertEquals("No se debe devolver ninguna coincidencia NO_MATCH", CronExpression.NO_MATCH, next)
        assertTrue(
            "NO_MATCH Debe distinguirse de recibir centinela.,De lo contrario, se considerará erróneamente que el programa de tareas se ha recibido.",
            next != Long.MAX_VALUE,
        )
    }

    // ── B-12: Cobro preventivo(CAS)juicio ───────────────────────────────────────
    // Si el reclamo tiene éxito o no está determinado por la cantidad de filas afectadas por una única ACTUALIZACIÓN atómica de ScheduledTaskDao.claimTask:
    //   1 = Este ejecutor recibió exitosamente(WHERE El next_run_at de la condición sigue siendo igual a la instantánea.);
    //   0 = Ya recogido por otros ejecutores primero.(El perdedor en encuestas/trabajador/manual/concurrencia encadenada).
    // Aquí solo probamos la función de juicio puro ClaimTaskSucceeded,Portada"El perdedor no repetirá la ejecución."La lógica central de las fronteras.

    @Test
    fun `claim succeeds when DAO returns affected row`() {
        assertTrue(ScheduledTaskRunner.claimTaskSucceeded(1))
    }

    @Test
    fun `claim fails when already claimed by another executer`() {
        // 0 influencia de fila = Otros ejecutores lo reciben primero.(sondeo/Worker concurrencia),El perdedor debe saltar,Evite llamadas duplicadas de IA/Notificación
        assertEquals(false, ScheduledTaskRunner.claimTaskSucceeded(0))
    }

    @Test
    fun `claim fails on abnormal row count conservative skip`() {
        // numero negativo/El valor inesperado es una excepción de base de datos o un valor predeterminado,Manejado como fallido(Saltar de forma conservadora,Evite la duplicación)
        assertEquals(false, ScheduledTaskRunner.claimTaskSucceeded(-1))
        assertEquals(false, ScheduledTaskRunner.claimTaskSucceeded(-3))
    }
}
