package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleFactoryPassThroughTest {
    @Test
    fun generatedFactory_takesUnresolvableArgumentsAsItsOwnParameters() {
        val network = NetworkModule(
            baseUrl = Binding.value("https://api.example.com"),
            httpClient = Binding.single { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        val welcome = WelcomeDIFactory.Welcome(network, RequestContext("alice"), Nickname("Al"), Note("back soon"))

        assertEquals("https://api.example.com", welcome.httpClient.baseUrl)
        assertEquals("Hello, alice", welcome.greeting)
        assertEquals(Nickname("Al"), welcome.nickname)
        assertEquals(Note("back soon"), welcome.note)
    }

    @Test
    fun generatedFactory_passThroughArgument_canBeNull() {
        val network = NetworkModule(
            baseUrl = Binding.value("https://api.example.com"),
            httpClient = Binding.single { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        val welcome = WelcomeDIFactory.Welcome(network, RequestContext("bob"), Nickname("Bob"), null)

        assertNull(welcome.note)
    }
}
