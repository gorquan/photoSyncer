package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.exception.XiaomiAuthenticationException
import com.coooolfan.xiaomialbumsyncer.model.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.SQLiteDialect
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.DefaultDatabaseNamingStrategy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.time.Instant

class AccountAuthStatusTest {
    @Test fun legacyAccountsRemainUnknownAndCredentialsArePreserved(@TempDir directory: Path) {
        source(directory).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("0.26.0").load().migrate()
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO provider_account(provider,nickname,user_id,credentials) VALUES ('XIAOMI','Test','test-user','{\"passToken\":\"test-secret\"}')")
            } }
            migrate(source)
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT auth_state,auth_checked_at,credentials FROM provider_account WHERE user_id = 'test-user'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("UNKNOWN", rows.getString(1))
                    assertNull(rows.getObject(2))
                    assertEquals("{\"passToken\":\"test-secret\"}", rows.getString(3))
                }
            } }
        }
    }

    @Test fun observationsRejectOldRequestsAndReplacedCredentials(@TempDir directory: Path) {
        source(directory).use { source ->
            migrate(source)
            val sql = sql(source)
            val service = AccountAuthStatusService(sql)
            val account = sql.saveCommand(ProviderAccount {
                provider = CloudProvider.XIAOMI; nickname = "Test"; userId = "test-user"; credentials = "test-secret"
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            assertEquals(AccountAuthState.UNKNOWN, sql.findById(ProviderAccount::class, account.id)!!.authState)
            val t = Instant.ofEpochMilli(100_000)
            service.record(account, AccountAuthState.SESSION_EXPIRED, t.plusSeconds(2))
            service.record(account, AccountAuthState.READY, t.plusSeconds(1))
            assertEquals(AccountAuthState.SESSION_EXPIRED, sql.findById(ProviderAccount::class, account.id)!!.authState)
            sql.saveCommand(ProviderAccount { id = account.id; credentials = "new-secret"; authState = AccountAuthState.UNKNOWN; authCheckedAt = null }, SaveMode.UPDATE_ONLY).execute()
            service.record(account, AccountAuthState.READY, t.plusSeconds(3), credentials = "stale-refreshed-secret")
            val current = sql.findById(ProviderAccount::class, account.id)!!
            assertEquals("new-secret", current.credentials)
            assertEquals(AccountAuthState.UNKNOWN, current.authState)
            assertNull(current.authCheckedAt)
            service.record(current, AccountAuthState.READY, t.plusSeconds(4))
            service.record(current, AccountAuthState.READY, t.plusSeconds(5))
            assertEquals(t.plusSeconds(5), sql.findById(ProviderAccount::class, account.id)!!.authCheckedAt)
            service.record(current, AccountAuthState.READY, t.plusSeconds(6))
            assertEquals(t.plusSeconds(6), sql.findById(ProviderAccount::class, account.id)!!.authCheckedAt)
            val restored = AccountAuthStatusService(sql)
            restored.record(current, AccountAuthState.MFA_REQUIRED, t.plusSeconds(7))
            assertEquals(AccountAuthState.MFA_REQUIRED, sql.findById(ProviderAccount::class, account.id)!!.authState)
        }
    }

    @Test fun xiaomiAuthErrorsAndNetworkErrorsRemainDistinct(@TempDir directory: Path) {
        source(directory).use { source ->
            migrate(source)
            val sql = sql(source)
            val account = sql.saveCommand(ProviderAccount {
                provider = CloudProvider.XIAOMI; nickname = "Test"; userId = "test-user"; credentials = "test-secret"
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val service = AccountAuthStatusService(sql)
            assertEquals("ok", service.observeXiaomi(account.id) { "ok" })
            assertEquals(AccountAuthState.READY, sql.findById(ProviderAccount::class, account.id)!!.authState)
            for ((failure, expected) in listOf(
                XiaomiAuthenticationException(true) to AccountAuthState.MFA_REQUIRED,
                XiaomiAuthenticationException() to AccountAuthState.SESSION_EXPIRED,
                SocketTimeoutException("sensitive transport details") to AccountAuthState.CHECK_FAILED,
            )) {
                assertThrows(failure.javaClass) { service.observeXiaomi(account.id) { throw failure } }
                val current = sql.findById(ProviderAccount::class, account.id)!!
                assertEquals(expected, current.authState)
                assertNotNull(current.authCheckedAt)
                assertEquals("test-secret", current.credentials)
            }
        }
    }

    private fun source(directory: Path) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:sqlite:${directory.resolve("accounts.db")}"; maximumPoolSize = 1
    })
    private fun migrate(source: HikariDataSource) { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate() }
    private fun sql(source: HikariDataSource) = newKSqlClient {
        setDialect(SQLiteDialect()); setConnectionManager(ConnectionManager.simpleConnectionManager(source)); setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)
    }
}
