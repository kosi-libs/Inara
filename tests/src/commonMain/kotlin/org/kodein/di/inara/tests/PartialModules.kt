package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

class DataSource(val url: String)
class Migrator(val dataSource: DataSource)
class Storage(val dataSource: DataSource)
class Pool

/**
 * Partial modules, through real Gradle/KSP-generated code: [MigrationModule] and
 * [StorageModule] both need a [DataSourceModule] without providing it, so each gets a generated
 * `<Module>Part`. [PartialAppModule] composes them with the one parent that provides the data
 * source - a diamond that is accepted because it is provided exactly once.
 */
@DIModule
interface DataSourceModule {
    val dataSource: DataSource
}

@DIModule
interface MigrationModule : @DIModule.Required DataSourceModule {
    val migrator: Migrator
    fun migrate(schema: String, target: Int): String
}

@DIModule
interface StorageModule : @DIModule.Required DataSourceModule {
    val storage: Storage
}

@DIModule(composed = true)
interface PartialAppModule : DataSourceModule, MigrationModule, StorageModule {
    val appName: String
}

/**
 * A `@DIModule(composed = true)` partial module providing one parent ([PoolModule]) while requiring
 * another ([DataSourceModule]): its `Part` can be built from an already-built [PoolModule], shared
 * with every module it is composed into.
 */
@DIModule
interface PoolModule {
    val pool: Pool
}

@DIModule(composed = true)
interface PooledStorageModule : @DIModule.Required DataSourceModule, PoolModule {
    val pooledStorage: Storage
}

@DIModule(composed = true)
interface PooledAppModule : DataSourceModule, PooledStorageModule
