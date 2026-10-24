package com.coooolfan.xiaomialbumsyncer.service

import cn.dev33.satoken.stp.StpUtil
import com.coooolfan.xiaomialbumsyncer.config.TaskScheduler
import com.coooolfan.xiaomialbumsyncer.controller.LoginRequest
import com.coooolfan.xiaomialbumsyncer.exception.AuthenticationException
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.model.dto.SystemConfigInit
import com.coooolfan.xiaomialbumsyncer.model.dto.SystemConfigNotifyConfigUpdate
import com.coooolfan.xiaomialbumsyncer.model.dto.SystemConfigPasswordUpdate
import com.coooolfan.xiaomialbumsyncer.utils.DataImporter
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Managed
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Managed
class SystemConfigService(
    private val sql: KSqlClient,
    private val dataImporter: DataImporter,
) {
    private val secureRandom = SecureRandom()
    // 避免一下循环依赖
    @Inject
    private lateinit var taskScheduler: TaskScheduler

    fun isInit(): Boolean {
        return sql.executeQuery(SystemConfig::class) {
            selectCount()
        }[0] > 0
    }

    fun initConfig(create: SystemConfigInit) {
        if (isInit()) throw IllegalStateException("System is already initialized")
        validatePassword(create.password)

        sql.saveCommand(SystemConfig {
            id = CONFIG_ID
            password = hashPwd(create.password)
            exifToolPath = "exiftool"
            assetsDateMapTimeZone = "Asia/Shanghai"
            notifyConfig = NotifyConfig(
                url = "",
                headers = emptyMap(),
                body = "",
                dailySummaryBody = null,
                dailySummaryCron = null,
                dailySummaryTimeZone = null,
                passTokenExpiredBody = null
            )
        }, SaveMode.INSERT_ONLY).execute()
    }

    fun login(login: LoginRequest) {
        if (!isInit()) throw IllegalStateException("System is not initialized")

        val stored = sql.findById(SystemConfig::class, CONFIG_ID)?.password
        if (stored == null || !verifyPwd(login.password, stored)) throw AuthenticationException("Auth failed")

        // Upgrade databases created by older releases after the first successful login.
        if (!stored.startsWith(PBKDF2_PREFIX)) {
            sql.executeUpdate(SystemConfig::class) {
                set(table.password, hashPwd(login.password))
                where(table.id eq CONFIG_ID)
                where(table.password eq stored)
            }
        }

        StpUtil.login(0)

    }

    fun updateConfig(update: SystemConfig) {
        sql.saveCommand(SystemConfig(update) {
            id = CONFIG_ID
        }, SaveMode.UPDATE_ONLY).execute()
    }

    fun updatePassword(update: SystemConfigPasswordUpdate) {
        if (!isInit()) throw IllegalStateException("System is not initialized")
        validatePassword(update.password)

        val stored = sql.findById(SystemConfig::class, CONFIG_ID)?.password
        if (stored == null || !verifyPwd(update.oldPassword, stored)) throw AuthenticationException("Auth failed")
        sql.executeUpdate(SystemConfig::class) {
            set(table.password, hashPwd(update.password))
            where(table.id eq CONFIG_ID)
        }
    }


    private fun hashPwd(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val derived = derive(password, salt, PBKDF2_ITERATIONS)
        return "${PBKDF2_PREFIX}${PBKDF2_ITERATIONS}\$${HexFormat.of().formatHex(salt)}\$${HexFormat.of().formatHex(derived)}"
    }

    private fun validatePassword(password: String) {
        if (password.isBlank()) throw com.coooolfan.xiaomialbumsyncer.exception.BadRequestException("密码不能为空")
    }

    private fun verifyPwd(password: String, stored: String): Boolean {
        if (!stored.startsWith(PBKDF2_PREFIX)) {
            return MessageDigest.isEqual(legacyHash(password).toByteArray(Charsets.US_ASCII), stored.toByteArray(Charsets.US_ASCII))
        }
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) return false
        val salt = runCatching { HexFormat.of().parseHex(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { HexFormat.of().parseHex(parts[3]) }.getOrNull() ?: return false
        if (salt.size != SALT_BYTES || expected.size != DERIVED_KEY_BITS / 8) return false
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, DERIVED_KEY_BITS))
            .encoded

    private fun legacyHash(password: String): String {
        val unHashed = "djshfpiuwEGfiugeiugfpiugpiuiuf$password"
        val digest = MessageDigest.getInstance("SHA3-384")
        val hashBytes = digest.digest(unHashed.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }

    fun importFromV2Db() {

        // 避免新旧数据冲突与合并，只允许空库导入

        val existsRows = sql.executeQuery(Album::class) { selectCount() }[0] + //
                sql.executeQuery(Crontab::class) { selectCount() }[0] + //
                sql.executeQuery(Asset::class) { selectCount() }[0]

        if (existsRows > 0) {
            throw IllegalStateException("当前数据库非空，导入已中止")
        }

        dataImporter.exec()
    }

    fun getConfig(fetcher: Fetcher<SystemConfig>): SystemConfig {
        return sql.findById(fetcher, CONFIG_ID) ?: throw IllegalStateException("System is not initialized")
    }

    fun getNotifyConfig(): NotifyConfig {
        return sql.findOneById(SystemConfig::class, CONFIG_ID).notifyConfig
    }

    fun updateNotifyConfig(update: SystemConfigNotifyConfigUpdate) {
        sql.saveCommand(SystemConfig {
            id = CONFIG_ID
            notifyConfig = update.notifyConfig
        }, SaveMode.UPDATE_ONLY).execute()
        taskScheduler.initJobs()
    }

    companion object {
        const val CONFIG_ID = 0L
        private const val PBKDF2_PREFIX = "pbkdf2-sha256$"
        private const val PBKDF2_ITERATIONS = 210_000
        private const val MIN_PBKDF2_ITERATIONS = 100_000
        private const val MAX_PBKDF2_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 16
        private const val DERIVED_KEY_BITS = 256
    }
}
