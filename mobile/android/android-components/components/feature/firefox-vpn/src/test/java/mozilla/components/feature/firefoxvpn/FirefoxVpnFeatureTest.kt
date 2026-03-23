/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.firefoxvpn

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class FirefoxVpnFeatureTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var feature: FirefoxVpnFeature
    private val statusChanges = mutableListOf<Boolean>()

    @Before
    fun setup() {
        statusChanges.clear()
        feature = FirefoxVpnFeature(
            onVpnStatusChange = { connected -> statusChanges.add(connected) },
            mainDispatcher = testDispatcher,
        )
    }

    @Test
    fun `start triggers onVpnStatusChange with connected state`() = runTest(testDispatcher) {
        feature.start()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, statusChanges.size)
        assertEquals(true, statusChanges[0])
    }

    @Test
    fun `stop cancels job and notifies disconnected`() = runTest(testDispatcher) {
        feature.start()
        testDispatcher.scheduler.advanceUntilIdle()
        feature.stop()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(statusChanges.last())
    }
}
