package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.model.AccountAuthState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException

class ICloudAuthStatusTest {
    @Test fun authenticationFailuresAreDistinctFromTransportAndServiceFailures() {
        assertEquals(AccountAuthState.MFA_REQUIRED, authFailureState(ICloudApiException(409)))
        assertEquals(AccountAuthState.MFA_REQUIRED, authFailureState(ICloudApiException(412)))
        assertEquals(AccountAuthState.MFA_REQUIRED, authFailureState(ICloudApiException(401), verifying = true))
        assertEquals(AccountAuthState.SESSION_EXPIRED, authFailureState(ICloudApiException(401)))
        assertEquals(AccountAuthState.CHECK_FAILED, authFailureState(ICloudApiException(503), verifying = true))
        assertEquals(AccountAuthState.CHECK_FAILED, authFailureState(SocketTimeoutException()))
    }
}
