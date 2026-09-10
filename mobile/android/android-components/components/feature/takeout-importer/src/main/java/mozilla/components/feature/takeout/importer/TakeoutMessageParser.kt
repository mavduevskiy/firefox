/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.takeout.importer

import org.json.JSONObject

/**
 * Parses the JSON envelope posted by the Takeout WebExtension over the native
 * messaging port. The envelope follows the shape:
 *
 * ```
 * { "name": "...", "data": { "result": { "success": { "actionID": "..." } } } }
 * { "name": "...", "data": { "result": { "error":   { "actionID": "...", "detail": "..." } } } }
 * ```
 *
 * Returns a [TakeoutStep] on success, or `null` for messages that don't match
 * the expected schema (treated as a soft "ignore" by the feature).
 */
internal object TakeoutMessageParser {

    fun parse(message: JSONObject): TakeoutStep? {
        val data = message.optJSONObject("data") ?: return null
        val result = data.optJSONObject("result") ?: return null

        result.optJSONObject("success")?.let { success ->
            val actionId = success.optString("actionID").takeIf { it.isNotEmpty() } ?: return null
            val detail = success.optString("detail").takeIf { it.isNotEmpty() }
            return TakeoutStep.Success(actionId, detail)
        }

        result.optJSONObject("error")?.let { error ->
            val actionId = error.optString("actionID").takeIf { it.isNotEmpty() }
            val detail = error.optString("detail").takeIf { it.isNotEmpty() }
            return TakeoutStep.Failure(actionId, detail)
        }

        return null
    }
}
