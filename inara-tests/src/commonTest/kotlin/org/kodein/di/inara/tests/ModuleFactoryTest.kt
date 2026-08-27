package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleFactoryTest {
    @Test
    fun generatedFactory_buildsTheTargetClassFromTheModule() {
        val network = NetworkModule(
            baseUrl = Binding.value("https://api.example.com"),
            httpClient = Binding.single { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        val controller = UsersController(network)

        assertEquals("https://api.example.com", controller.httpClient.baseUrl)
        assertEquals("https://api.example.com/users", controller.endpoint)
    }
}
