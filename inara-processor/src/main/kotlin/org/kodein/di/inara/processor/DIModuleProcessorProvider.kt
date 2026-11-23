package org.kodein.di.inara.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

public class DIModuleProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        // DIModuleProcessor uses API that only exists since KSP 2.3.14 (SymbolProcessor.apiFeatures,
        // validate(predicate, apiFeatures)): on an older KSP it would die on a NoSuchMethodError that
        // says nothing about why. `SymbolProcessor` is KSP's own copy - the API jar this processor
        // depends on never overrides it - so it tells which KSP actually runs. `kspVersion` can't:
        // KSP always sets it to 2.0.
        if (SymbolProcessor::class.java.methods.none { it.name == "getApiFeatures" }) {
            return UnsupportedKspProcessor(environment.logger)
        }
        return DIModuleProcessor(environment.codeGenerator, environment.logger)
    }
}

private const val MINIMUM_KSP_VERSION = "2.3.14"

/**
 * Stands in for [DIModuleProcessor] on a KSP older than [MINIMUM_KSP_VERSION]: generates nothing,
 * and only says why. The error is logged once all rounds are over - see DeferredErrorsLogger for why.
 */
private class UnsupportedKspProcessor(private val logger: KSPLogger) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()

    override fun finish() {
        report()
    }

    // Called instead of finish() when another processor reported an error.
    override fun onError() {
        report()
    }

    private fun report() {
        logger.error("Inara needs KSP $MINIMUM_KSP_VERSION or later: update the 'com.google.devtools.ksp' plugin.")
    }
}
