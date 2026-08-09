package dev.punit.tidylink.shared.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [Link::class, TrashedLink::class, Peer::class, SyncState::class],
    version = 1,
    exportSchema = false,
)
abstract class TidyLinkDb : RoomDatabase() {

    abstract fun linkDao(): LinkDao

    abstract fun syncDao(): SyncDao

    companion object {
        /** On-disk database at [path] (absolute file path). */
        fun open(path: String): TidyLinkDb =
            Room.databaseBuilder<TidyLinkDb>(name = path)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()

        /** Fresh in-memory database, for tests. */
        fun inMemory(): TidyLinkDb =
            Room.inMemoryDatabaseBuilder<TidyLinkDb>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
