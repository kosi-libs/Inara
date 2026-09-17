package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory

/**
 * Exercises `@DIFactory`'s default `Suspend.Allow` through real Gradle/KSP-generated code:
 * [service] resolves to [AsyncModule.load] - a `suspend` member - by exact type match, with no
 * `arg` function needed, so the generated builder function itself becomes `suspend`.
 */
class AsyncGreeter(val service: Service)

@DIFactory(builds = AsyncGreeter::class, from = AsyncModule::class)
internal object AsyncGreeterDIFactory
