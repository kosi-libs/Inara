package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory

/**
 * Exercises `@DIFactory` through real Gradle/KSP-generated code: [UsersController] never
 * references Inara at all - only [UserControllerDIFactory] does. The generated
 * `UserControllerDIFactory.UsersController(module)` is an extension on the object, and `public`
 * because the object is (its `arg` functions must then be `internal`). `httpClient` resolves
 * automatically (an exact-type match on [NetworkModule.httpClient]); `endpoint` needs the
 * `argEndpoint` extension function since no module member alone determines the path.
 */
class UsersController(
    val httpClient: HttpClient,
    val endpoint: String,
)

@DIFactory(builds = UsersController::class, from = NetworkModule::class)
public object UserControllerDIFactory {
    internal fun NetworkModule.argEndpoint(): String = endpoint("users")
}
