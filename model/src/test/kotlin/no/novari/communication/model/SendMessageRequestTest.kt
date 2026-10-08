package no.novari.communication.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendMessageRequestTest {
    @Test
    fun `variables and lists are optional`() {
        val email = EmailMessage(to = "ola@rogfk.no", templateId = "team/mal")

        assertTrue(email.variables.isEmpty())
        assertTrue(email.lists.isEmpty())
    }

    @Test
    fun `requests with same content are equal`() {
        val email = validEmail()

        assertEquals(
            SendMessageRequest(tenant = Tenant.ROGALAND, message = email),
            SendMessageRequest(tenant = Tenant.ROGALAND, message = email.copy()),
        )
    }

    @Test
    fun `toString does not expose recipient or values`() {
        val text = validEmail().toString()

        assertFalse(text.contains("ola@rogfk.no"))
        assertFalse(text.contains("12345"))
        assertFalse(text.contains("ACOS"))
        assertTrue(text.contains("team/mal"))
        assertTrue(text.contains("saksnummer"))
        assertTrue(text.contains("integrasjoner"))
    }

    private fun validEmail() =
        EmailMessage(
            to = "ola@rogfk.no",
            templateId = "team/mal",
            variables = mapOf("saksnummer" to "12345"),
            lists = mapOf("integrasjoner" to listOf(mapOf("navn" to "ACOS"))),
        )
}
