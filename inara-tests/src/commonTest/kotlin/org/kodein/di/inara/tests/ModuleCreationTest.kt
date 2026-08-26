package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleCreationTest {
    @Test
    fun generatedNetworkModule_delegatesToBindings() {
        val module = NetworkModule(
            baseUrl = Binding.value("https://example.test"),
            httpClient = Binding.provider { HttpClient(baseUrl) },
            endpoint = Binding.factory { path -> "$baseUrl/$path" },
        )

        assertEquals("https://example.test", module.baseUrl)
        assertEquals(HttpClient("https://example.test"), module.httpClient())
        assertEquals("https://example.test/users", module.endpoint("users"))
    }
}
