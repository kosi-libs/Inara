package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory
import org.kodein.di.inara.DIModule

/**
 * Exercises `@DIFactory` falling back to a constructor parameter's own default through real
 * Gradle/KSP-generated code. [Report.verbose] declares a default *and* [ReportModule.verbose] would
 * match it exactly - the default outranks the module member, so the generated factory never reads
 * `module.verbose` and the built [Report] carries `false`. `title` still resolves automatically, an
 * exact-type match on [ReportModule.title].
 */
@DIModule
public interface ReportModule {
    public val title: String
    public val verbose: Boolean
}

public class Report(
    public val title: String,
    public val verbose: Boolean = false,
)

@DIFactory(builds = Report::class, from = ReportModule::class)
internal object ReportDIFactory
