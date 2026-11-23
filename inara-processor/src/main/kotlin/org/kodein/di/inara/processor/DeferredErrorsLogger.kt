package org.kodein.di.inara.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode

/**
 * A [KSPLogger] that holds the errors back until [flush], and passes everything else on at once.
 *
 * Since KSP 2.3.13, when a processor reports an error during a round, KSP type-checks the sources of
 * that round and reports every compiler diagnostic as an error too. That analysis doesn't see the files
 * generated during the round, so code using a module generated alongside the invalid one - or the
 * builder that wasn't generated because of the error - gets reported as unresolved, burying the actual
 * error under false ones. KSP decides on that type-check once the rounds are over, before calling
 * [com.google.devtools.ksp.processing.SymbolProcessor.finish]: errors reported from `finish` still fail
 * the build, without it.
 *
 * Each error's location is read when it is reported, since a [KSNode] isn't valid past its round.
 */
internal class DeferredErrorsLogger(private val logger: KSPLogger) : KSPLogger by logger {
    private val errors = LinkedHashSet<String>()

    override fun error(message: String, symbol: KSNode?) {
        val location = symbol?.location as? FileLocation
        errors += if (location != null) "${location.filePath}:${location.lineNumber}: $message" else message
    }

    /** Reports the errors held back so far, each once. */
    fun flush() {
        errors.forEach { logger.error(it) }
        errors.clear()
    }
}
