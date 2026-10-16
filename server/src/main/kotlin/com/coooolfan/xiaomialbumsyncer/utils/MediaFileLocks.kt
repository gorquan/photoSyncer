package com.coooolfan.xiaomialbumsyncer.utils

/** Bounded process-local locks shared by publication and deletion across tasks and source versions. */
object MediaFileLocks {
    private val groups = Array(128) { java.util.concurrent.locks.ReentrantLock() }
    private val ownership = java.util.concurrent.locks.ReentrantLock()
    fun <T> group(key: String, action: () -> T): T {
        val lock = groups[(key.hashCode() and Int.MAX_VALUE) % groups.size]
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }
    /** Never perform provider IO while holding the ownership lock. */
    fun <T> ownership(action: () -> T): T {
        ownership.lock()
        try { return action() } finally { ownership.unlock() }
    }
}
