package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleFactoryWithTest {
    @Test
    fun generatedFactory_passesTheCallTimeArgumentToArgFunctions() {
        val network = NetworkModule(
            baseUrl = Binding.value("https://api.example.com"),
            httpClient = Binding.single { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        val session = UserSession(network, RequestContext("alice"))

        assertEquals("https://api.example.com", session.httpClient.baseUrl)
        assertEquals("Hello, alice", session.greeting)
    }

    @Test
    fun generatedFactory_resolvesAOneParameterModuleMemberWithTheCallTimeArgument() {
        val routing = RoutingModule(route = Binding.factory { segment -> "/app/$segment" })

        val page = Page(routing, "dashboard")

        assertEquals("/app/dashboard", page.route)
    }

    @Test
    fun generatedFactory_withNullable_acceptsAValueAndNull() {
        val network = NetworkModule(
            baseUrl = Binding.value("https://api.example.com"),
            httpClient = Binding.single { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        assertEquals("Hello, alice", Banner(network, RequestContext("alice")).greeting)
        assertEquals("Hello, anonymous", Banner(network, null).greeting)
    }
}
