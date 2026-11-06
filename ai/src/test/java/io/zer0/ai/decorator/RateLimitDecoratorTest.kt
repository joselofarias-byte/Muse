package io.zer0.ai.decorator

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.Provider
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1-3: [RateLimitDecorator] Pruebas unitarias.
 *
 * verificar:
 *  - control de concurrencia(Semaphore)Limitar el número de solicitudes simultáneas en tránsito
 *  - Saltar decoración cuando algún parámetro sea 0(comisión directa)
 *  - streamChat El permiso concurrente se mantiene durante la recopilación de transmisiones.,fin/liberado al cancelar
 *
 * Nota:RPM La lógica limitante actual consiste en [io.zer0.ai.util.SlidingWindowRateLimiterTest] Portada
 * (decorator El limitador interno utiliza reloj en tiempo real,No se puede inyectar el reloj virtual,Por lo tanto, no repetiremos la prueba de RPM aquí y esperaremos.).
 *
 * C-31 Evaluación:[FakeProvider] Solo se usa en este archivo del módulo ai,No hay nombres en todos los almacenes./Copia cruzada de la misma responsabilidad
 * (VideoGenerationServiceTest El FakeVideoProvider se usa de manera diferente). Baja repetibilidad,No vale la pena construir
 * testFixtures Infraestructura,Mantenga los archivos privados. Consulte el informe de auditoría en profundidad C-31 para obtener instrucciones de revisión.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RateLimitDecoratorTest {

    /** Proveedor falso para pruebas: registre la cantidad de llamadas y la concurrencia máxima,Se puede inyectar retraso. */
    private class FakeProvider(private val delayMs: Long = 0) : Provider {
        override val id: String = "fake"
        override val displayName: String = "Fake"

        val completeCallCount = AtomicInteger(0)
        val streamCallCount = AtomicInteger(0)
        private val activeCount = AtomicInteger(0)
        val maxObservedActive = AtomicInteger(0)

        private fun enter() {
            val active = activeCount.incrementAndGet()
            // Récord de simultaneidad máxima(Gire las actualizaciones hasta que tenga éxito)
            var prev = maxObservedActive.get()
            while (active > prev && !maxObservedActive.compareAndSet(prev, active)) {
                prev = maxObservedActive.get()
            }
        }

        private fun exit() {
            activeCount.decrementAndGet()
        }

        override suspend fun completeText(request: ChatRequest): ChatCompletion {
            enter()
            try {
                if (delayMs > 0) delay(delayMs)
                completeCallCount.incrementAndGet()
                return ChatCompletion(text = "ok")
            } finally {
                exit()
            }
        }

        override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            enter()
            try {
                if (delayMs > 0) delay(delayMs)
                streamCallCount.incrementAndGet()
                emit(ChatStreamEvent.ContentDelta("hi"))
                emit(ChatStreamEvent.Done())
            } finally {
                exit()
            }
        }
    }

    private fun dummyRequest(): ChatRequest = ChatRequest(
        messages = listOf(UIMessage(role = MessageRole.USER, content = "hi")),
        model = Model(id = "m", name = "M", providerId = "fake"),
    )

    @Test
    fun `completeText should limit concurrency to maxConcurrentRequests`() = runTest {
        val fake = FakeProvider(delayMs = 100)
        val decorator = RateLimitDecorator(
            delegate = fake,
            requestLimitPerMinute = 0, // Sin límite RPM,Sólo prueba de simultaneidad
            maxConcurrentRequests = 2,
        )
        // 6 solicitudes concurrentes,Cada vez que consume mucho tiempo 100ms,Pero el límite de concurrencia 2 → Concurrencia máxima ≤ 2
        val jobs = (1..6).map { async { decorator.completeText(dummyRequest()) } }
        jobs.awaitAll()
        assertEquals(6, fake.completeCallCount.get())
        assertTrue(
            "max observed active ${fake.maxObservedActive.get()} should be <= 2",
            fake.maxObservedActive.get() <= 2,
        )
    }

    @Test
    fun `completeText should delegate directly when both limits are zero`() = runTest {
        val fake = FakeProvider(delayMs = 0)
        val decorator = RateLimitDecorator(
            delegate = fake,
            requestLimitPerMinute = 0,
            maxConcurrentRequests = 0,
        )
        decorator.completeText(dummyRequest())
        assertEquals(1, fake.completeCallCount.get())
        // Sin demora,El tiempo virtual no ha avanzado
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `streamChat should hold concurrency permit during collection`() = runTest {
        val fake = FakeProvider(delayMs = 100)
        val decorator = RateLimitDecorator(
            delegate = fake,
            requestLimitPerMinute = 0,
            maxConcurrentRequests = 2,
        )
        // 4 transmisiones concurrentes,Cada colección lleva tiempo. 100ms,Límite de concurrencia 2 → pico ≤ 2
        val jobs = (1..4).map {
            async { decorator.streamChat(dummyRequest()).toList() }
        }
        jobs.awaitAll()
        // Cada corriente emite ContentDelta + Done
        assertEquals(4, fake.streamCallCount.get())
        assertTrue(
            "max observed active ${fake.maxObservedActive.get()} should be <= 2",
            fake.maxObservedActive.get() <= 2,
        )
    }

    @Test
    fun `decorator should preserve delegate id and displayName`() {
        val fake = FakeProvider()
        val decorator = RateLimitDecorator(fake, requestLimitPerMinute = 10, maxConcurrentRequests = 2)
        // Provider by delegate atributo directo
        assertEquals("fake", decorator.id)
        assertEquals("Fake", decorator.displayName)
    }
}
