package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleFactoryRepeatedTest {
    @Test
    fun repeatedAnnotation_bothGeneratedFunctionsAreCallableOffTheObject() {
        val module = ScreenModule(
            appName = Binding.value("Inara"),
            itemCount = Binding.value(7),
        )

        val login = ScreensFactory.LoginScreen(module)
        val home = ScreensFactory.HomeScreen(module)

        assertEquals("Inara screen", login.heading)
        assertEquals("Inara screen", home.heading)
        assertEquals(7, home.count)
    }
}
