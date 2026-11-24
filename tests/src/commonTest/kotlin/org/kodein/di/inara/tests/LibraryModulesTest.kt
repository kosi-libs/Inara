package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import org.kodein.di.inara.tests.library.AuditLog
import org.kodein.di.inara.tests.library.LibraryAuditModulePart
import org.kodein.di.inara.tests.library.LibraryServerModule
import org.kodein.di.inara.tests.library.LibraryService
import org.kodein.di.inara.tests.library.Server
import org.kodein.di.inara.tests.library.setOfLibraryServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LibraryModulesTest {
    private fun serverModule(portNumber: Int) = LibraryServerModule(
        port = Binding.value(portNumber),
        server = Binding.single { Server(port) },
        endpoint = Binding.factory { "${if (it.secure) "https" else "http"}://localhost:$port/${it.path}" },
        serverService = Binding.value(LibraryService("server")),
    )

    private val audit = LibraryAuditModulePart(auditLog = Binding.single { AuditLog(server) })

    @Test
    fun extendingALibraryModule_passesTheStartupCheck() {
        val app = AppOnLibraryModule(
            port = Binding.value(8080),
            server = Binding.single { Server(port) },
            endpoint = Binding.factory { "${it.path}:${it.secure}" },
            serverService = Binding.value(LibraryService("server")),
            appName = Binding.value("app"),
            appService = Binding.value(LibraryService("app")),
        )

        assertEquals(8080, app.server.port)
        assertEquals("users:true", app.endpoint("users", true))
    }

    @Test
    fun composedOnALibrary_sharesTheParentAndCopiesThePart() {
        val server = serverModule(8080)
        val app = ComposedOnLibraryModule(libraryServerModule = server, libraryAuditModule = audit, appName = Binding.value("app"))

        assertSame(server.server, app.server)
        assertSame(app.server, app.auditLog.server)
        assertEquals("http://localhost:8080/health", app.endpoint("health", false))
    }

    @Test
    fun swappingALibraryParent_rebuildsThePartOnIt() {
        val app = ComposedOnLibraryModule(libraryServerModule = serverModule(8080), libraryAuditModule = audit, appName = Binding.value("app"))
        val swapped = app.copyComposedOnLibraryModule(libraryServerModule = serverModule(9090))

        assertEquals(9090, swapped.server.port)
        assertEquals(9090, swapped.auditLog.server.port)
        assertEquals("https://localhost:9090/admin", swapped.endpoint("admin", true))
        assertEquals(8080, app.auditLog.server.port)
    }

    @Test
    fun aLibrarySet_answersWithTheMembersOfTheExtendingModule() {
        val app = AppOnLibraryModule(
            port = Binding.value(8080),
            server = Binding.single { Server(port) },
            endpoint = Binding.factory { it.path },
            serverService = Binding.value(LibraryService("server")),
            appName = Binding.value("app"),
            appService = Binding.value(LibraryService("app")),
        )

        assertEquals(setOf(LibraryService("server"), LibraryService("app")), app.setOfLibraryServices())
        assertEquals(setOf(LibraryService("server")), serverModule(8080).setOfLibraryServices())
    }
}
