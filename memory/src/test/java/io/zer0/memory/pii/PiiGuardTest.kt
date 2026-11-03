package io.zer0.memory.pii

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiGuardTest {

    @Test
    fun scrubMasksChineseNameAndAddress() {
        val result = PiiGuard.scrub("Mi nombre es Zhang Ming, vivo en el distrito de Wuhou, ciudad de Chengdu.")
        assertFalse(result.cleaned.contains("Zhang Ming"))
        assertFalse(result.cleaned.contains("Distrito de Wuhou, ciudad de Chengdu"))
        assertTrue(result.cleaned.contains("[REDACTED]"))
        assertTrue("name" in result.detected)
        assertTrue("detected=${result.detected} cleaned=${result.cleaned}", "address" in result.detected)
    }

    @Test
    fun scrubKeepsWhitelistedCommonWords() {
        val result = PiiGuard.scrub("Necesito entender este problema")
        assertTrue(result.cleaned.contains("entender"))
        assertTrue(result.cleaned.contains("pregunta"))
    }

    @Test
    fun scrubMasksPhoneEmailAndIdCardTableDriven() {
        val cases = listOf(
            PiiCase("Contacto 13800138000", listOf("13800138000"), listOf("phone")),
            PiiCase("Contacto 138 0013 8000", listOf("138 0013 8000"), listOf("phone")),
            PiiCase("Correo electrónico zhang@example.com", listOf("zhang@example.com"), listOf("email")),
            PiiCase("tarjeta de identificación 11010119900307751X", listOf("11010119900307751X"), listOf("id_card")),
            PiiCase("tarjeta de identificación 110101900307751", listOf("110101900307751"), listOf("id_card")),
        )
        for (case in cases) {
            val result = PiiGuard.scrub(case.input)
            assertTrue("input=${case.input} detected=${result.detected}", case.expectedTypes.all { it in result.detected })
            for (secret in case.secrets) {
                assertFalse("input=${case.input} cleaned=${result.cleaned}", result.cleaned.contains(secret))
            }
            assertTrue("input=${case.input} cleaned=${result.cleaned}", result.cleaned.contains("[REDACTED]"))
        }
    }

    private data class PiiCase(
        val input: String,
        val secrets: List<String>,
        val expectedTypes: List<String>,
    )

    // ---- A-18: La regla del nombre se cambia a "apellido"+Después de "título" y "contexto del título",Las palabras comunes no deben borrarse por error ----

    @Test
    fun scrubKeepsCommonWordsStartingWithSurnameChar() {
        // texto/rey/Las palabras comunes que comienzan con apellidos como Li no se pueden borrar como nombres.
        val inputs = listOf(
            "cultura tradicional china", "Este artículo está bien escrito.", "era del reino", "He leído algunas obras literarias.",
            "Necesidad de dominar el método correcto.", "Sobre la ubicación de este lugar", "El tratamiento lleva tiempo", "El programa se ejecuta normalmente.",
            "El gerente es responsable de gestionar el equipo.", "La lógica es muy clara.", "Lleno de energía", "El campo es muy amplio.",
        )
        for (input in inputs) {
            val result = PiiGuard.scrub(input)
            assertTrue(
                "A-18 Lesión accidental: input=$input cleaned=${result.cleaned}",
                !result.cleaned.contains("[REDACTED]"),
            )
        }
    }

    @Test
    fun scrubKeepsWhitelistedA18Words() {
        // Incluir seguro dual en la lista blanca:Incluso si se coloca en el contexto de un título,Las palabras que aparecen en la lista blanca también deben permanecer en su texto original.
        val result = PiiGuard.scrub("Quiero gestionar el trabajo cultural aquí.")
        assertTrue(result.cleaned.contains("gestión"))
        assertTrue(result.cleaned.contains("cultura"))
        assertTrue(result.cleaned.contains("trabajo"))
    }

    @Test
    fun scrubMasksSurnamePlusTitleAsName() {
        // Los apellidos + títulos deben reconocerse como nombres de pila y borrarse.
        val inputs = listOf(
            "Maestro Wang",
            "doctor li",
            "compañero de clase zhang",
            "Gerente Liu",
            "Profesor Chen",
            "jefe zhao",
            "Abogado Sol",
        )
        for (input in inputs) {
            val result = PiiGuard.scrub("lo se$input")
            assertFalse(
                "A-18 Título no borrado: input=$input cleaned=${result.cleaned}",
                result.cleaned.contains(input),
            )
            assertTrue(
                "A-18 detected desaparecido name: input=$input detected=${result.detected}",
                "name" in result.detected,
            )
        }
    }

    @Test
    fun scrubMasksPlainNameWithContextMarker() {
        // Depende del contexto: llamar/Apellido/Nombre seguido del apellido+nombre,Debe identificarse por su nombre y borrarse.
        val inputs = listOf(
            "Mi nombre es Zhang Ming, vivo en el distrito de Wuhou, ciudad de Chengdu.",
            "Su apellido es Li Wei.",
            "El nombre del maestro es Wang Fang.",
        )
        for (input in inputs) {
            val result = PiiGuard.scrub(input)
            assertTrue(
                "A-18 El nombre del contexto no se borra: input=$input cleaned=${result.cleaned}",
                result.cleaned.contains("[REDACTED]"),
            )
            assertTrue(
                "A-18 detected desaparecido name: input=$input detected=${result.detected}",
                "name" in result.detected,
            )
        }
    }

    // ---- A-20: CREDIT_CARD Alinear la versión de la aplicación,Cubre números de tarjetas de 16 a 19 dígitos ----

    @Test
    fun scrubMasksBankCard16To19Digits() {
        val cases = listOf(
            "Número de tarjeta 6222021234567890", // 16 poco
            "Número de tarjeta 62220212345678901", // 17 poco
            "Número de tarjeta 622202123456789012", // 18 poco
            "Número de tarjeta 6222021234567890123", // 19 poco
            "Número de tarjeta 6222 0212 3456 7890", // 4-4-4-4 espacio separado(16 poco)
            "Número de tarjeta 6222-0212-3456-7890123", // 4-4-4-Separador + Remolque 7 bits(19 poco)
        )
        for (input in cases) {
            val result = PiiGuard.scrub(input)
            val card = input.removePrefix("Número de tarjeta ")
            assertFalse(
                "A-20 Número de tarjeta no borrado: input=$input cleaned=${result.cleaned}",
                result.cleaned.contains(card),
            )
            assertTrue(
                "A-20 detected desaparecido credit_card: input=$input detected=${result.detected}",
                "credit_card" in result.detected,
            )
        }
    }

    @Test
    fun scrubKeepsNonCardDigitRuns() {
        // 15 Los dígitos o más de 20 dígitos consecutivos no deben borrarse accidentalmente con CREDIT_CARD(protección fronteriza).
        val input = "No. 123456789012345(15poco),otro 12345678901234567890(20poco)"
        val result = PiiGuard.scrub(input)
        // 15/20 el bit no puede golpear CREDIT_CARD,Por lo tanto no debería ocurrir [REDACTED].
        assertFalse(
            "A-20 Fuego amigo de límites: input=$input cleaned=${result.cleaned}",
            result.cleaned.contains("[REDACTED]"),
        )
    }

    // ── Revisión de corrección (2.0 B-08): mask/unmask residuo de marcador de posición ──────────────────────

    @Test
    fun unmaskRestoresKnownTokens() {
        val masked = PiiGuard.mask("Mi número de teléfono es 13800138000,El correo electrónico es a@b.com")
        // Las reglas se ejecutan en el orden de RuleKind.entries,EMAIL antes PHONE,Por lo tanto, el número de marcador de posición del teléfono es 2;
        // La afirmación solo verifica el formulario del marcador de posición y restaura el resultado.,No se basa en números específicos
        assertTrue("masked=${masked.masked}", masked.masked.contains("[PHONE_"))
        assertTrue("masked=${masked.masked}", masked.masked.contains("[EMAIL_1]"))

        val restored = PiiGuard.unmask(masked.masked, masked.map)
        assertTrue("restored=$restored", restored.contains("13800138000"))
        assertTrue("restored=$restored", restored.contains("a@b.com"))
        assertFalse("restored=$restored", restored.contains("[PHONE_"))
    }

    @Test
    fun unmaskStripsResidualTokensWhenLlmDropsOriginal() {
        // Dos escenarios residuales:
        // 1) LLM Alucinar cosas que no existen en el mapa. token(Como por ejemplo [PHONE_2])→ despegar;
        // 2) El token en la asignación se restaura al texto original normalmente.(No afectado por el despojo).
        val masked = PiiGuard.mask("telefono 13800138000")
        val llmOutput = "El usuario dijo que cambió su número.,[PHONE_1]Desactivado,Nos vemos en la nueva cuenta.[PHONE_2]"
        val restored = PiiGuard.unmask(llmOutput, masked.map)
        assertTrue("B-08: El token en el mapeo se restaura normalmente., restored=$restored", restored.contains("13800138000"))
        assertFalse("B-08: Se debe eliminar el marcador de posición residual de alucinación, restored=$restored", restored.contains("[PHONE_2]"))
        assertFalse("B-08: No deberían quedar marcadores de posición después de la restauración., restored=$restored", restored.contains("[PHONE_1]"))
    }

    @Test
    fun unmaskStripsUnexplainedTokensAcrossLabels() {
        // Sin mapeo(o el mapeo no contiene un label)tiempo,Se deben eliminar todos los marcadores de posición enmascarados.
        val llmOutput = "Información de contacto [EMAIL_2] y [ID_CARD_1] Todos han caducado"
        val restored = PiiGuard.unmask(llmOutput, emptyMap())
        assertFalse("B-08: Los marcadores de posición residuales también se eliminan cuando no hay mapeo., restored=$restored", restored.contains("[EMAIL_2]"))
        assertFalse("B-08: Los marcadores de posición residuales también se eliminan cuando no hay mapeo., restored=$restored", restored.contains("[ID_CARD_1]"))
    }
}
