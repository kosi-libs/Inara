package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory

/**
 * Exercises `@DIFactory(constructorIndex = 1)` through real Gradle/KSP-generated code: [Greeter]'s
 * primary constructor is `private` - unreachable from the generated function - so `constructorIndex`
 * redirects argument resolution to its public secondary constructor instead. `baseUrl` resolves
 * automatically, an exact-type match on [NetworkModule.baseUrl].
 */
class Greeter private constructor(val greeting: String, private val marker: Int) {
    constructor(baseUrl: String) : this("Hello from $baseUrl", 0)
}

@DIFactory(builds = Greeter::class, from = NetworkModule::class, constructorIndex = 1)
internal object GreeterDIFactory
