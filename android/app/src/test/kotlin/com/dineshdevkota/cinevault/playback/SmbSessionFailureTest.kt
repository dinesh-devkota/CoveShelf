package com.dineshdevkota.cinevault.playback

import com.dineshdevkota.cinevault.smb.SmbException
import com.dineshdevkota.cinevault.smb.SmbService
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

class SmbSessionFailureTest {
    @Test
    fun wrappedPermissionFailuresDoNotReconnectOrRetryTheOperation() {
        val denied = SMBApiException(0xC0000022L, SMB2MessageCommandCode.SMB2_CREATE, null)
        assertFalse(SmbService.isStaleSession(IOException("operation failed", denied)))
    }

    @Test
    fun expiredSessionsAndBrokenConnectionsCanReconnect() {
        val expired = SMBApiException(0xC000035CL, SMB2MessageCommandCode.SMB2_READ, null)
        assertTrue(SmbService.isStaleSession(IOException("operation failed", expired)))
        assertTrue(SmbService.isStaleSession(SocketException("connection reset")))
    }

    @Test
    fun cancellationAndLocalValidationErrorsAreNotNetworkRetries() {
        assertFalse(SmbService.isStaleSession(CancellationException("scan stopped")))
        assertFalse(SmbService.isStaleSession(SmbException("The NAS file is empty.")))
        assertFalse(SmbService.isStaleSession(IllegalArgumentException("invalid operation")))
    }

    @Test
    fun onlyAuthenticationAndAccessFailuresPromptForCredentials() {
        for (status in listOf(0xC000006DL, 0xC000006AL, 0xC0000022L)) {
            val failure = SMBApiException(status, SMB2MessageCommandCode.SMB2_CREATE, null)
            assertTrue(SmbService.isAuthenticationFailure(IOException("wrapped failure", failure)))
        }
        for (status in listOf(0xC00000CCL, 0xC0000034L, 0xC000003AL)) {
            assertFalse(SmbService.isAuthenticationFailure(SMBApiException(status, SMB2MessageCommandCode.SMB2_CREATE, null)))
        }
        assertFalse(SmbService.isAuthenticationFailure(java.net.SocketTimeoutException()))
        assertFalse(SmbService.isAuthenticationFailure(java.net.UnknownHostException()))
    }
}
