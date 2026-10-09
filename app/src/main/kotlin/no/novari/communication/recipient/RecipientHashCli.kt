package no.novari.communication.recipient

import java.io.BufferedReader
import java.io.PrintStream
import kotlin.system.exitProcess

fun main() {
    exitProcess(RecipientHashCli.run(System.getenv(), System.`in`.bufferedReader(), System.out, System.err))
}

object RecipientHashCli {
    fun run(
        environment: Map<String, String>,
        input: BufferedReader,
        output: PrintStream,
        error: PrintStream,
    ): Int {
        val hasher =
            try {
                RecipientHasher.fromBase64Key(environment[RecipientHasher.KEY_ENVIRONMENT_VARIABLE])
            } catch (exception: IllegalStateException) {
                error.println(exception.message)
                return 1
            }
        input
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach { output.println(hasher.hash(it).value) }
        return 0
    }
}
