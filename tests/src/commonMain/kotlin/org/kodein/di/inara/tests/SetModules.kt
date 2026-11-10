package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory.Suspend
import org.kodein.di.inara.DIModule
import org.kodein.di.inara.DISet

data class AppService(val name: String)

/**
 * A set annotation: [ServicesModule] lists it in `sets`, so it gets a generated
 * `setOfAppServices()` collecting every member annotated with it - [DatabaseModule.database] and
 * [ServerModule.server] (inherited through its two `@DIModule` parents) and its own
 * [ServicesModule.cache].
 */
@DISet(of = AppService::class)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class AppServices

@DIModule
interface DatabaseModule {
    /** Nullable, and `null` by default: left out of the set unless bound. */
    @AppServices fun database(): AppService? = null
}

@DIModule
interface ServerModule {
    @AppServices val server: AppService
    val port: Int
}

@DIModule(composed = true, sets = [AppServices::class])
interface ServicesModule : DatabaseModule, ServerModule {
    /** Called without argument by the generated function, so with its default `size`. */
    @AppServices fun cache(size: Int = 16): AppService
}

/** Doesn't list [AppServices] itself, but still gets `setOfAppServices()` from [ServicesModule]. */
@DIModule
interface ExtendedServicesModule : ServicesModule {
    @AppServices val metrics: AppService
}

/** A set whose generated function is `suspend`, since one of its members is. */
@DISet(of = AppService::class, suspend = Suspend.Allow)
annotation class AsyncServices

@DIModule(sets = [AsyncServices::class])
interface AsyncServicesModule {
    @AsyncServices val local: AppService
    @AsyncServices suspend fun remote(): AppService
}
