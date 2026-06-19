/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import java.util.IllformedLocaleException
import java.util.Locale

/**
 * Converts an ISO 3166-1 alpha-2 country [code] (for example "US") into a country name localized
 * to [locale] (for example "United States"). Falls back to the raw [code] when it cannot be
 * resolved, e.g. for an unknown or malformed region.
 *
 * @param code ISO 3166-1 alpha-2 country code.
 * @param locale The locale the name should be displayed in. Defaults to the device locale.
 */
fun localizedCountryName(code: String, locale: Locale = Locale.getDefault()): String =
    try {
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifEmpty { code }
    } catch (_: IllformedLocaleException) {
        code
    }
