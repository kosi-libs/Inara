package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModuleFactoryDefaultTest {
    @Test
    fun generatedFactory_usesTheConstructorDefault_notAMatchingModuleMember() {
        val module = ReportModule(
            title = Binding.value("Q3 report"),
            verbose = Binding.value(true),
        )

        val report = ReportDIFactory.Report(module)

        assertEquals("Q3 report", report.title)
        // `verbose` has a constructor default, so the factory never consults `module.verbose` (true).
        assertFalse(report.verbose)
    }
}
