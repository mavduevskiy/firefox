/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.takeout.importer

/**
 * A step reported by the Takeout WebExtension as it drives the export journey,
 * or by the native side as it processes the resulting download.
 */
sealed class TakeoutStep {
    /** A step from the content script completed successfully. */
    data class Success(val actionId: String, val detail: String? = null) : TakeoutStep()

    /** A step from the content script failed. [actionId] may be null if no step had run yet. */
    data class Failure(val actionId: String?, val detail: String? = null) : TakeoutStep()

    /** A download originating from `takeout.google.com` was detected. */
    data object DownloadDetected : TakeoutStep()

    /** ZIP extraction finished. [files] holds the absolute paths of the entries written to cache. */
    data class Extracted(val files: List<String>) : TakeoutStep()
}
