package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule
import org.kodein.di.inara.tests.library.LibraryAuditModule
import org.kodein.di.inara.tests.library.LibraryServerModule
import org.kodein.di.inara.tests.library.LibraryService
import org.kodein.di.inara.tests.library.LibraryServices

/**
 * Modules extending the modules of another Gradle module (`tests-library`), which Inara only sees
 * compiled - so these are the only fixtures whose generated code checks, when first built, that
 * the library modules are those it was compiled against.
 */
@DIModule
interface AppOnLibraryModule : LibraryServerModule {
    val appName: String
    @LibraryServices val appService: LibraryService
}

/**
 * Reads the code generated for its library parents. It also guards that Inara reads the library's
 * `@InaraGenerated` annotations from its klib: if it couldn't, this module would fail to compile.
 */
@DIModule(composed = true)
interface ComposedOnLibraryModule : LibraryServerModule, LibraryAuditModule {
    val appName: String
}
