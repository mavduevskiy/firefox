/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  BookmarkHTMLUtils: "resource://gre/modules/BookmarkHTMLUtils.sys.mjs",
  BrowserWindowTracker: "resource:///modules/BrowserWindowTracker.sys.mjs",
  Downloads: "resource://gre/modules/Downloads.sys.mjs",
  FileUtils: "resource://gre/modules/FileUtils.sys.mjs",
  NimbusFeatures: "resource://nimbus/ExperimentAPI.sys.mjs",
  clearTimeout: "resource://gre/modules/Timer.sys.mjs",
  setTimeout: "resource://gre/modules/Timer.sys.mjs",
  WebFlow: "moz-src:///toolkit/components/webflow/WebFlow.sys.mjs",
});

const DEFAULT_CONFIG_URL = "resource://webflow/journeys/google-takeout-bookmarks.json";
const BOOKMARKS_ENTRY = "Bookmarks.html";
const ATTEMPT_TIMEOUT_MS = 15 * 60 * 1000;

/**
 * Prototype of the Google Takeout bookmarks import on desktop, driven by
 * the same web flow runner and journey as Firefox for Android. There is no
 * UI yet: with `browser.webflow.takeoutImport.enabled` set, run it from the
 * Browser Console:
 *
 *   ChromeUtils.importESModule(
 *     "moz-src:///browser/components/webflow/TakeoutBookmarksImport.sys.mjs"
 *   ).TakeoutBookmarksImport.run();
 */
export const TakeoutBookmarksImport = {
  /**
   * Runs the export in a background tab, brings the tab to the front while
   * Google asks the user to sign in, then imports the downloaded bookmarks.
   *
   * @returns {Promise<number>} The number of imported bookmarks.
   */
  async run() {
    const feature = lazy.NimbusFeatures.webFlowTakeoutImport;
    if (!feature.getVariable("enabled")) {
      throw new Error("browser.webflow.takeoutImport.enabled is false");
    }
    feature.recordExposureEvent();
    const config =
      feature.getVariable("config") ?? (await (await fetch(DEFAULT_CONFIG_URL)).json());

    const win = lazy.BrowserWindowTracker.getTopWindow();
    const { gBrowser } = win;
    const startedAt = new Date();
    const tab = gBrowser.addTrustedTab("about:blank", { inBackground: true });
    const stopAuthSurfacing = surfaceTabForAuth(gBrowser, tab, config);
    const downloads = await lazy.Downloads.getList(lazy.Downloads.ALL);
    let downloadView;
    let attemptId;
    let timeout;

    try {
      const archive = new Promise((resolve, reject) => {
        const downloadHosts = new Set(config["download-hosts"]);
        downloadView = {
          onDownloadChanged(download) {
            if (
              download.succeeded &&
              download.startTime >= startedAt &&
              downloadHosts.has(URL.parse(download.source.url)?.hostname)
            ) {
              resolve(download.target.path);
            }
          },
        };
        timeout = lazy.setTimeout(
          () => reject(new Error("Timed out waiting for the export")),
          ATTEMPT_TIMEOUT_MS
        );
      });
      await downloads.addView(downloadView);

      attemptId = await lazy.WebFlow.start({
        tab,
        config,
        onMessage: message => {
          console.log("Takeout import:", message);
        },
      });
      // Load the journey only once the attempt exists, so the runner in the
      // tab finds it configured.
      tab.linkedBrowser.fixupAndLoadURIString(config["launch-url"], {
        triggeringPrincipal: Services.scriptSecurityManager.getSystemPrincipal(),
      });

      const archivePath = await archive;
      const count = await importBookmarksFromArchive(archivePath);
      console.log(`Takeout import: imported ${count} bookmarks`);
      return count;
    } finally {
      lazy.clearTimeout(timeout);
      stopAuthSurfacing();
      if (downloadView) {
        await downloads.removeView(downloadView);
      }
      if (attemptId) {
        await lazy.WebFlow.stop(attemptId);
      }
      if (!tab.closing) {
        gBrowser.removeTab(tab);
      }
    }
  },
};

/**
 * Selects `tab` while it shows one of the config's auth hosts, so the user
 * can sign in, and goes back to the previously selected tab afterwards.
 *
 * @returns {Function} Stops watching the tab.
 */
function surfaceTabForAuth(gBrowser, tab, config) {
  const authHosts = new Set(config["auth-hosts"]);
  let previousTab = null;
  const listener = {
    onLocationChange(browser, webProgress, request, location) {
      if (browser !== tab.linkedBrowser || !webProgress.isTopLevel) {
        return;
      }
      let host = "";
      try {
        host = location.host;
      } catch {
        // URIs without a host, such as about:blank.
      }
      if (authHosts.has(host)) {
        if (gBrowser.selectedTab !== tab) {
          previousTab = gBrowser.selectedTab;
          gBrowser.selectedTab = tab;
        }
      } else if (previousTab) {
        if (!previousTab.closing) {
          gBrowser.selectedTab = previousTab;
        }
        previousTab = null;
      }
    },
  };
  gBrowser.addTabsProgressListener(listener);
  return () => gBrowser.removeTabsProgressListener(listener);
}

/**
 * Extracts the bookmarks file from the Takeout archive into a temporary
 * file, imports it and deletes the temporary file.
 *
 * @param {string} archivePath Path of the downloaded zip.
 * @returns {Promise<number>} The number of imported bookmarks.
 */
async function importBookmarksFromArchive(archivePath) {
  const extracted = await IOUtils.createUniqueFile(PathUtils.tempDir, BOOKMARKS_ENTRY);
  try {
    const reader = Cc["@mozilla.org/libjar/zip-reader;1"].createInstance(Ci.nsIZipReader);
    reader.open(new lazy.FileUtils.File(archivePath));
    try {
      const entries = reader.findEntries(`*/${BOOKMARKS_ENTRY}`);
      if (!entries.hasMore()) {
        throw new Error(`No ${BOOKMARKS_ENTRY} in ${archivePath}`);
      }
      reader.extract(entries.getNext(), new lazy.FileUtils.File(extracted));
    } finally {
      reader.close();
    }
    return await lazy.BookmarkHTMLUtils.importFromFile(extracted);
  } finally {
    // The extracted file holds the user's bookmarks; don't keep it.
    await IOUtils.remove(extracted, { ignoreAbsent: true });
  }
}
