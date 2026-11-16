package no.novari.communication.email

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.communication.message.domain.EmailPayload
import no.novari.communication.message.domain.MessageId

class LoggingEmailAdapter : EmailAdapter {
    private val logger = KotlinLogging.logger {}

    override fun send(
        id: MessageId,
        email: EmailPayload,
    ): EmailSendOutcome {
        logger.info { "E-post ikke sendt (provider=logging) id=${id.value} templateId=${email.templateId}" }
        return EmailSendOutcome.Sent
    }
}
