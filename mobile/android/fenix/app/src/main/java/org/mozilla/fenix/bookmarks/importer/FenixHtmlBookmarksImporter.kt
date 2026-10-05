/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks.importer

import android.content.Context
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.bookmark.parser.BookmarksFileParser
import mozilla.components.concept.bookmarks.file.BookmarksFileImporter
import mozilla.components.concept.storage.bookmarks.BookmarkInserter
import mozilla.components.lib.bookmark.parser.jsoup.jsoupParser
import mozilla.components.lib.bookmarks.file.htmlImporter
import org.mozilla.fenix.R

/**
 * The importer used by every bookmarks import in Fenix: Netscape HTML files,
 * imported into a new folder under the mobile bookmarks root.
 */
internal fun fenixHtmlBookmarksImporter(
    context: Context,
    inserter: BookmarkInserter,
): BookmarksFileImporter =
    BookmarksFileImporter.htmlImporter(
        context = context,
        parentGuid = BookmarkRoot.Mobile.id,
        parser = BookmarksFileParser.jsoupParser(
            rootFolderName = context.getString(R.string.bookmark_import_destination_default_name),
        ),
        inserter = inserter,
    )
