/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webflow

import androidx.annotation.WorkerThread
import mozilla.appservices.remotesettings.RemoteSettingsClient
import mozilla.appservices.remotesettings.RemoteSettingsException
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.org.json.asSequence
import org.json.JSONException
import org.json.JSONObject

/**
 * Provides the web flow journeys, shared with desktop through the
 * `web-flow-journeys` Remote Settings collection. Until the collection has
 * synced records newer than the dump desktop ships, the copy of that dump
 * bundled in the app's assets is used.
 *
 * @param client The Remote Settings client of the collection. Creating it
 * registers the collection with the periodic Remote Settings sync.
 * @param readDump Reads the bundled dump, in the Remote Settings dump format.
 */
class WebFlowJourneyRepository(
    private val client: RemoteSettingsClient,
    private val readDump: () -> JSONObject,
) {
    private val logger = Logger("WebFlowJourneyRepository")

    /**
     * Returns the journey config with the record id [id], in the Nimbus
     * kebab-case form the runner expects, or null if there is none.
     */
    @WorkerThread
    fun journey(id: String): JSONObject? {
        val dump = try {
            readDump()
        } catch (e: JSONException) {
            logger.error("Failed to read the bundled journeys", e)
            null
        }
        val dumpTimestamp = dump?.optLong("timestamp") ?: 0L

        val synced = try {
            val lastModified = client.getLastModifiedTimestamp()
            if (lastModified != null && lastModified.toLong() > dumpTimestamp) client.getRecords() else null
        } catch (e: RemoteSettingsException) {
            logger.error("Failed to read the synced journeys", e)
            null
        } catch (e: IllegalStateException) {
            logger.error("Failed to read the synced journeys", e)
            null
        }
        synced?.firstOrNull { it.id == id && !it.deleted }?.let { return it.fields }

        return dump?.optJSONArray("data")
            ?.asSequence()
            ?.filterIsInstance<JSONObject>()
            ?.firstOrNull { it.optString("id") == id }
    }

    companion object {
        /** The Remote Settings collection holding the journeys. */
        const val COLLECTION_NAME = "web-flow-journeys"

        /** The asset path of the bundled dump, copied from desktop's dumps by the build. */
        const val DUMP_ASSET = "webflow/web-flow-journeys.json"

        /** The record id of the Google Takeout bookmarks journey. */
        const val GOOGLE_TAKEOUT_BOOKMARKS = "google-takeout-bookmarks"

        /** The record id of the Google Password Manager export journey. */
        const val GOOGLE_PASSWORDS_IMPORT = "google-passwords-import"
    }
}
