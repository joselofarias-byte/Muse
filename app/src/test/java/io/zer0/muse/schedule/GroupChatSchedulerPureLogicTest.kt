package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R-TEST-15: El análisis del libro mayor de rotación del chat grupal y la asignación de roles de debate son puramente lógicos.
 */
class GroupChatSchedulerPureLogicTest {

    @Test
    fun `ledger member ids preserve rotation order`() {
        assertEquals(
            listOf("a", "b", "c"),
            GroupChatScheduler.parseLedgerMemberIds("""["a","b","c"]"""),
        )
    }

    @Test
    fun `blank or empty ledger returns null`() {
        assertNull(GroupChatScheduler.parseLedgerMemberIds(null))
        assertNull(GroupChatScheduler.parseLedgerMemberIds(""))
        assertNull(GroupChatScheduler.parseLedgerMemberIds("[]"))
    }

    @Test
    fun `invalid ledger json returns null`() {
        assertNull(GroupChatScheduler.parseLedgerMemberIds("not-json"))
    }

    @Test
    fun `debate roles cover two three and more members`() {
        assertEquals(listOf("proponer un plan", "desafío de preguntas"), GroupChatScheduler.generateDebateRoles(2))
        assertEquals(
            listOf("proponer un plan", "desafío de preguntas", "Mejorar la optimización"),
            GroupChatScheduler.generateDebateRoles(3),
        )
        assertEquals(
            listOf("proponer un plan", "desafío de preguntas", "Mejorar la optimización", "Prórroga suplementaria", "proponer un plan"),
            GroupChatScheduler.generateDebateRoles(5),
        )
    }
}
