/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mozilla.appservices.remotesettings.RemoteSettingsClient
import mozilla.appservices.remotesettings.RemoteSettingsException
import mozilla.appservices.remotesettings.RemoteSettingsRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebFlowJourneyRepositoryTest {
    private val dump = """
        {
          "data": [
            { "id": "journey", "launch-url": "https://dump.example/", "last_modified": 100 }
          ],
          "timestamp": 100
        }
    """.trimIndent()

    private val client: RemoteSettingsClient = mockk()
    private val repository = WebFlowJourneyRepository(client) { JSONObject(dump) }

    private fun synced(id: String, launchUrl: String) =
        RemoteSettingsRecord(id, 200u, false, null, JSONObject().put("launch-url", launchUrl))

    @Test
    fun `GIVEN nothing synced WHEN a journey is requested THEN the dump is used`() {
        every { client.getLastModifiedTimestamp() } returns null

        assertEquals("https://dump.example/", repository.journey("journey")?.getString("launch-url"))
        verify(exactly = 0) { client.getRecords() }
    }

    @Test
    fun `GIVEN synced records older than the dump WHEN a journey is requested THEN the dump is used`() {
        every { client.getLastModifiedTimestamp() } returns 50u

        assertEquals("https://dump.example/", repository.journey("journey")?.getString("launch-url"))
        verify(exactly = 0) { client.getRecords() }
    }

    @Test
    fun `GIVEN synced records newer than the dump WHEN a journey is requested THEN the synced record is used`() {
        every { client.getLastModifiedTimestamp() } returns 200u
        every { client.getRecords() } returns listOf(synced("journey", "https://synced.example/"))

        assertEquals("https://synced.example/", repository.journey("journey")?.getString("launch-url"))
    }

    @Test
    fun `GIVEN newer synced records without the journey WHEN it is requested THEN the dump is used`() {
        every { client.getLastModifiedTimestamp() } returns 200u
        every { client.getRecords() } returns listOf(synced("other", "https://synced.example/"))

        assertEquals("https://dump.example/", repository.journey("journey")?.getString("launch-url"))
    }

    @Test
    fun `GIVEN Remote Settings fails WHEN a journey is requested THEN the dump is used`() {
        every { client.getLastModifiedTimestamp() } throws RemoteSettingsException.Other("failed")

        assertEquals("https://dump.example/", repository.journey("journey")?.getString("launch-url"))
    }

    @Test
    fun `GIVEN an unknown journey WHEN it is requested THEN null is returned`() {
        every { client.getLastModifiedTimestamp() } returns null

        assertNull(repository.journey("unknown"))
    }
}
