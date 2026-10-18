package com.coooolfan.xiaomialbumsyncer.config

import com.coooolfan.xiaomialbumsyncer.model.*
import com.zaxxer.hikari.HikariDataSource
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.SQLiteDialect
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.DefaultDatabaseNamingStrategy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SQLiteConcurrencyTest {
    @Test
    fun deferredReadSnapshotCannotUpgradeAfterAnotherWriterCommits(@TempDir directory: Path) {
        val url = buildSQLiteUrl(directory.resolve("deferred.db"))
        DriverManager.getConnection(url).use { reader ->
            DriverManager.getConnection(url).use { writer ->
                reader.update("CREATE TABLE counter(value INTEGER)")
                reader.update("INSERT INTO counter VALUES (0)")
                reader.autoCommit = false
                try {
                    assertEquals(0, reader.counter())
                    writer.update("UPDATE counter SET value=1")
                    val failure = assertThrows(SQLException::class.java) {
                        reader.update("UPDATE counter SET value=2")
                    }
                    assertTrue(failure.message.orEmpty().contains("SQLITE_BUSY_SNAPSHOT"))
                } finally {
                    reader.rollback()
                }
            }
        }
    }

    @Test
    fun pooledTransactionsWaitBeforeReadingAndKeepWalReadersAvailable(@TempDir directory: Path) {
        // Both the generated URL and a complete SQLITE_URL use the production pool policy.
        for (configuredUrl in listOf(false, true)) {
            pool(directory.resolve("pool-$configuredUrl.db"), configuredUrl).use { source ->
                source.connection.use { first ->
                    first.update("CREATE TABLE counter(value INTEGER)")
                    first.update("INSERT INTO counter VALUES (0)")
                    first.autoCommit = false
                    assertEquals(0, first.counter())
                    Executors.newSingleThreadExecutor().use { executor ->
                        try {
                            val attempting = CountDownLatch(1)
                            val waiting = executor.submit<Int> {
                                source.connection.use { second ->
                                    attempting.countDown()
                                    second.autoCommit = false
                                    val previous = second.counter()
                                    second.update("UPDATE counter SET value=${previous + 1}")
                                    second.commit()
                                    previous
                                }
                            }
                            assertTrue(attempting.await(2, TimeUnit.SECONDS))
                            assertThrows(TimeoutException::class.java) { waiting.get(150, TimeUnit.MILLISECONDS) }
                            source.connection.use { reader ->
                                assertTrue(reader.autoCommit)
                                assertEquals(0, reader.counter())
                            }
                            first.update("UPDATE counter SET value=1")
                            first.commit()
                            first.autoCommit = true
                            assertEquals(1, waiting.get(5, TimeUnit.SECONDS))
                            assertEquals(2, first.counter())
                        } finally {
                            if (!first.autoCommit) {
                                first.rollback()
                                first.autoCommit = true
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun concurrentJimmerReadModifyWriteTransactionsPreserveUpdatesAndRollback(@TempDir directory: Path) {
        pool(directory.resolve("jimmer.db")).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val sql = newKSqlClient {
                setDialect(SQLiteDialect())
                setConnectionManager(ConnectionManager.simpleConnectionManager(source))
                setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)
            }
            val account = sql.saveCommand(ProviderAccount {
                nickname = "0"
                credentials = "{}"
                userId = "synthetic-account"
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            Executors.newFixedThreadPool(4).use { executor ->
                val start = CountDownLatch(1)
                val writers = (1..4).map {
                    executor.submit {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        repeat(20) {
                            sql.transaction {
                                val previous = sql.findById(ProviderAccount::class, account.id)!!.nickname.toInt()
                                sql.executeUpdate(ProviderAccount::class) {
                                    where(table.id eq account.id)
                                    set(table.nickname, (previous + 1).toString())
                                }
                            }
                        }
                    }
                }
                start.countDown()
                writers.forEach { it.get(15, TimeUnit.SECONDS) }
            }
            assertEquals("80", sql.findById(ProviderAccount::class, account.id)!!.nickname)
            assertThrows(IllegalStateException::class.java) {
                sql.transaction {
                    sql.executeUpdate(ProviderAccount::class) {
                        where(table.id eq account.id)
                        set(table.nickname, "must-roll-back")
                    }
                    error("synthetic failure")
                }
            }
            assertEquals("80", sql.findById(ProviderAccount::class, account.id)!!.nickname)
            source.connection.use { connection ->
                assertTrue(connection.autoCommit)
                connection.update("UPDATE provider_account SET nickname='reused' WHERE id=${account.id}")
            }
            assertEquals("reused", sql.findById(ProviderAccount::class, account.id)!!.nickname)
        }
    }

    private fun pool(path: Path, configuredUrl: Boolean = false): HikariDataSource {
        val properties = SQLiteUrlProperties().apply {
            journalMode = "WAL"
            synchronous = "NORMAL"
            tempStore = "memory"
            busyTimeout = 2_000
            maximumPoolSize = 4
            if (configuredUrl) url = "jdbc:sqlite:$path?journal_mode=WAL&busy_timeout=2000&foreign_keys=on"
        }
        return DataSource().apply { sqlite = path.toString() }.defaultDataSource(properties) as HikariDataSource
    }

    private fun Connection.update(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.counter(): Int = createStatement().use { statement ->
        statement.executeQuery("SELECT value FROM counter").use { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
        }
    }
}
