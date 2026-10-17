package com.coooolfan.xiaomialbumsyncer.model

/** Latest recorded provider authentication observation, not a live guarantee. */
enum class AccountAuthState { UNKNOWN, READY, MFA_REQUIRED, SESSION_EXPIRED, CHECK_FAILED }
