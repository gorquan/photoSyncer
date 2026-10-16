package com.coooolfan.xiaomialbumsyncer.model

/** Verification evidence for the downloaded original, before optional metadata changes. */
enum class VerificationStatus {
    LEGACY,
    PENDING,
    SKIPPED,
    SOURCE_VERIFIED,
    TRANSPORT_VERIFIED,
    FAILED,
}
