package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Proves that an `internal` `@DIModule` interface gets an `internal` generated builder, and that
 * it's usable from within the same module (here, from `commonTest`).
 */
@DIModule
internal interface InternalModule {
    val secret: String
}
