package no.novari.communication.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendMessageRequestTest {
    @Test
    fun `channel and replyTo are optional`() {
        val request = SendMessageRequest(tenant = "rogfk.no")
        val email = EmailMessage(to = "ola@rogfk.no", subject = "Emne", body = "Innhold")

        assertNull(request.email)
        assertNull(email.replyTo)
    }

    @Test
    fun `requests with same content are equal`() {
        val email = EmailMessage(to = "ola@rogfk.no", subject = "Emne", body = "Innhold", replyTo = "kari@rogfk.no")

        assertEquals(
            SendMessageRequest(tenant = "rogfk.no", email = email),
            SendMessageRequest(tenant = "rogfk.no", email = email.copy()),
        )
    }
}
