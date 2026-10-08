package no.novari.communication.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "channel")
@JsonSubTypes(JsonSubTypes.Type(value = EmailMessage::class, name = "EMAIL"))
sealed interface Message
