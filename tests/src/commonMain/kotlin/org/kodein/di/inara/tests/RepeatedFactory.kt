package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory
import org.kodein.di.inara.DIModule

/**
 * Exercises `@DIFactory` being `@Repeatable` through real Gradle/KSP-generated code: [ScreensFactory]
 * is annotated twice and gets both `ScreensFactory.LoginScreen(module)` and
 * `ScreensFactory.HomeScreen(module)` in one generated file. The `argHeading` extension function is
 * shared - both screens have a `heading` argument, so both generated functions call it. `HomeScreen`
 * also resolves `count` automatically off [ScreenModule.itemCount].
 */
@DIModule
interface ScreenModule {
    val appName: String
    val itemCount: Int
}

class LoginScreen(val heading: String)
class HomeScreen(val heading: String, val count: Int)

@DIFactory(builds = LoginScreen::class, from = ScreenModule::class)
@DIFactory(builds = HomeScreen::class, from = ScreenModule::class)
public object ScreensFactory {
    internal fun ScreenModule.argHeading(): String = "$appName screen"
}
