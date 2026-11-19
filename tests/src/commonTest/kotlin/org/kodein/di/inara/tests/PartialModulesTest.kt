package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PartialModulesTest {
    private fun dataSourceModule(url: String) = DataSourceModule(dataSource = Binding.single { DataSource(url) })

    private val migration = MigrationModulePart(
        migrator = Binding.single { Migrator(dataSource) },
        migrate = Binding.factory { "${it.schema}@${it.target} on ${dataSource.url}" },
    )

    private val storage = StorageModulePart(storage = Binding.single { Storage(dataSource) })

    @Test
    fun partBindings_readTheProvidedRequirementThroughTheReceiver() {
        val app = PartialAppModule(dataSourceModule("prod"), migration, storage, appName = Binding.value("app"))

        assertSame(app.dataSource, app.migrator.dataSource)
        assertSame(app.dataSource, app.storage.dataSource)
        assertSame(app.migrator, app.migrator)
        assertEquals("public@3 on prod", app.migrate("public", 3))
    }

    @Test
    fun aPartComposedTwice_buildsItsSinglesOncePerModule() {
        val prod = PartialAppModule(dataSourceModule("prod"), migration, storage, appName = Binding.value("prod"))
        val test = PartialAppModule(dataSourceModule("test"), migration, storage, appName = Binding.value("test"))

        assertNotSame(prod.migrator, test.migrator)
        assertEquals("prod", prod.migrator.dataSource.url)
        assertEquals("test", test.migrator.dataSource.url)
    }

    @Test
    fun swappingTheProvider_rebuildsThePartBindingsOnIt() {
        val app = PartialAppModule(dataSourceModule("prod"), migration, storage, appName = Binding.value("app"))
        val swapped = app.copyPartialAppModule(dataSourceModule = dataSourceModule("swapped"))

        assertEquals("swapped", swapped.migrator.dataSource.url)
        assertEquals("swapped", swapped.storage.dataSource.url)
        assertEquals("prod", app.migrator.dataSource.url)
    }

    @Test
    fun aPartialModule_canStillBeBuiltStandalone() {
        val module = MigrationModule(
            dataSource = Binding.value(DataSource("standalone")),
            migrator = Binding.single { Migrator(dataSource) },
            migrate = Binding.factory { it.schema },
        )

        assertEquals("standalone", module.migrator.dataSource.url)
    }

    @Test
    fun aPartBuiltFromParents_sharesThem() {
        val pool = PoolModule(pool = Binding.single { Pool() })
        val part = PooledStorageModulePart(poolModule = pool, pooledStorage = Binding.single { Storage(dataSource) })

        val first = PooledAppModule(dataSourceModule("first"), part)
        val second = PooledAppModule(dataSourceModule("second"), part)

        assertSame(pool.pool, first.pool)
        assertSame(pool.pool, second.pool)
        assertEquals("first", first.pooledStorage.dataSource.url)
        assertEquals("second", second.pooledStorage.dataSource.url)
    }

    @Test
    fun partToString_listsItsRequirementsAndBindings() {
        assertEquals(
            "part StorageModule (requires DataSourceModule) {\n" +
                "    val storage: Storage => single(Synchronized) { Storage }\n" +
                "}",
            storage.toString(),
        )
    }
}
