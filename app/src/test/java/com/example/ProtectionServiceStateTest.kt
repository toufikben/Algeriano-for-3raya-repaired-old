package com.example

import com.example.service.CameraForegroundService
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The status card reads `CameraForegroundService.isRunning` instead of the stored
 * `isTrackingEnabled` preference. That only helps if the flag is false whenever
 * no promoted instance exists — a stuck `true` would restore the exact lie the
 * change was meant to remove.
 */
class ProtectionServiceStateTest {

    @Test
    fun `service reports not running when no instance was ever created`() {
        assertFalse(CameraForegroundService.isRunning)
        assertFalse(CameraForegroundService.isCameraChannelOpen)
    }

    @Test
    fun `running state starts false so the card never assumes a live service`() {
        assertFalse(CameraForegroundService.runningState.value)
    }
}
