package io.zer0.muse.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-09: PII Pruebas de regresión de desensibilización y reducción.
 *
 * Cubrir el número de teléfono celular(11 poco/con espacios), correo electrónico, DNI(15/18 poco)El viaje de ida y vuelta enmascarar/desenmascarar.
 */
class PiiGuardTest {

    @Test
    fun maskAndUnmaskRoundTripForPhoneEmailAndIdCard() {
        val cases = listOf(
            "13800138000",
            "138 0013 8000",
            "zhang@example.com",
            "11010119900307751X",
            "110101900307751",
        )
        for (input in cases) {
            val (masked, matches) = PiiGuard.mask(input)
            assertFalse("masked=$masked input=$input", masked.contains(input))
            assertTrue("input=$input matches=${matches.size}", matches.isNotEmpty())
            assertEquals("La restauración debe ser coherente con el texto original. input=$input", input, PiiGuard.unmask(masked, matches))
        }
    }

    @Test
    fun maskAndUnmaskKeepSurroundingText() {
        val input = "teléfono celular 13800138000,Correo electrónico a@b.com"
        val (masked, matches) = PiiGuard.mask(input)
        assertTrue(masked.contains("teléfono celular"))
        assertTrue(masked.contains("Correo electrónico"))
        assertFalse(masked.contains("13800138000"))
        assertEquals(input, PiiGuard.unmask(masked, matches))
    }

    // A-20: BANK_CARD Cubre números de tarjetas de 16 a 19 dígitos(Alineado con la versión de memoria CREDIT_CARD).

    @Test
    fun maskMasksBankCardOfSixteenToNineteenDigits() {
        val inputs = listOf(
            "Número de tarjeta 6222021234567890", // 16 poco
            "Número de tarjeta 62220212345678901", // 17 poco
            "Número de tarjeta 622202123456789012", // 18 poco
            "Número de tarjeta 6222021234567890123", // 19 poco
        )
        for (input in inputs) {
            val card = input.removePrefix("Número de tarjeta ").trim()
            val (masked, matches) = PiiGuard.mask(input)
            assertFalse(
                "A-20 desenmascarado BANK_CARD: input=$input masked=$masked",
                masked.contains(card),
            )
            assertTrue(
                "A-20 no detectado BANK_CARD: input=$input matches=${matches.size}",
                matches.any { it.type == PiiGuard.PiiType.BANK_CARD },
            )
            assertEquals("La restauración debe ser coherente con el texto original. input=$input", input, PiiGuard.unmask(masked, matches))
        }
    }

    @Test
    fun maskMasksSixteenDigitBankCardBoundaryOnly() {
        // 16 Los números de tarjeta inmediatamente entre caracteres no numéricos deben estar ocultos.(protección fronteriza,No puede haber más números antes y después.).
        val input = "Tarjeta 6222021234567890 cola"
        val (masked, matches) = PiiGuard.mask(input)
        assertFalse(masked.contains("6222021234567890"))
        assertTrue(matches.any { it.type == PiiGuard.PiiType.BANK_CARD })
        assertEquals(input, PiiGuard.unmask(masked, matches))
    }
}
