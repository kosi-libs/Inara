package org.kodein.di.inara.tests.library

import org.kodein.di.inara.DIModule
import org.kodein.di.inara.DISet

class Server(val port: Int)
class AuditLog(val server: Server)
data class LibraryService(val name: String)

/**
 * A set annotation declared by a library: a module of another Gradle module extending
 * [LibraryServerModule] answers for it with its own members too.
 */
@DISet(of = LibraryService::class)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class LibraryServices

/**
 * Modules of a library, extended by the `tests` Gradle module (see its `LibraryExtensions.kt`): the
 * code Inara generates there reads the code generated here, which it only sees compiled.
 */
@DIModule(sets = [LibraryServices::class])
interface LibraryServerModule {
    val port: Int
    val server: Server

    /** Two parameters: its arguments class, `LibraryServerModuleBindingArguments.Endpoint`, is generated here. */
    fun endpoint(path: String, secure: Boolean): String

    @LibraryServices val serverService: LibraryService
}

/** A partial module: the library ships its wiring as a `LibraryAuditModulePart`. */
@DIModule
interface LibraryAuditModule : @DIModule.Required LibraryServerModule {
    val auditLog: AuditLog
}
