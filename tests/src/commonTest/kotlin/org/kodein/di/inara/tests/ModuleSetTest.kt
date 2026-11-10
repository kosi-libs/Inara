package org.kodein.di.inara.tests

import kotlinx.coroutines.test.runTest
import org.kodein.di.inara.Binding
import org.kodein.di.inara.SuspendBinding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSetTest {
    private val server = AppService("server")

    private fun servicesModule(database: AppService? = null) = ServicesModule(
        databaseModule = if (database == null) DatabaseModule() else DatabaseModule(database = Binding.value(database)),
        serverModule = ServerModule(server = Binding.value(server), port = Binding.value(80)),
        cache = Binding.factory { size -> AppService("cache$size") },
    )

    @Test
    fun set_collectsAnnotatedMembersInOrder_leavingNullOut() {
        assertEquals(
            listOf(server, AppService("cache16")),
            servicesModule().setOfAppServices().toList(),
        )
    }

    @Test
    fun set_includesBoundNullableMember() {
        val database = AppService("database")
        assertEquals(
            listOf(database, server, AppService("cache16")),
            servicesModule(database).setOfAppServices().toList(),
        )
    }

    @Test
    fun set_holdsEqualValuesOnce() {
        val module = ServicesModule(
            databaseModule = DatabaseModule(database = Binding.value(server)),
            serverModule = ServerModule(server = Binding.value(server), port = Binding.value(80)),
            cache = Binding.factory { _ -> server },
        )

        assertEquals(setOf(server), module.setOfAppServices())
    }

    @Test
    fun set_isInheritedByDescendant() {
        val metrics = AppService("metrics")
        val module = ExtendedServicesModule(
            server = Binding.value(server),
            port = Binding.value(80),
            cache = Binding.factory { size -> AppService("cache$size") },
            metrics = Binding.value(metrics),
        )

        assertEquals(
            listOf(server, AppService("cache16"), metrics),
            module.setOfAppServices().toList(),
        )
    }

    @Test
    fun set_reflectsCopyAndShadowOverrides() {
        val other = AppService("other")
        val module = servicesModule()

        assertEquals(listOf(other, AppService("cache16")), module.copy(server = Binding.value<ServicesModule, AppService>(other)).setOfAppServices().toList())
        assertEquals(listOf(other, AppService("cache16")), module.shadow(server = Binding.value<ServicesModule, AppService>(other)).setOfAppServices().toList())
        assertEquals(listOf(server, AppService("cache16")), module.setOfAppServices().toList())
    }

    @Test
    fun suspendSet_readsSuspendMembers() = runTest {
        val module = AsyncServicesModule(
            local = Binding.value(AppService("local")),
            remote = SuspendBinding.provider { AppService("remote") },
        )

        assertEquals(listOf(AppService("local"), AppService("remote")), module.setOfAsyncServices().toList())
    }
}
