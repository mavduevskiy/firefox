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
  RemoteSettings: "resource://services-settings/remote-settings.sys.mjs",
  WebFlow: "moz-src:///toolkit/components/webflow/WebFlow.sys.mjs",
  clearTimeout: "resource://gre/modules/Timer.sys.mjs",
  setTimeout: "resource://gre/modules/Timer.sys.mjs",
});

const JOURNEYS_COLLECTION = "web-flow-journeys";
const JOURNEY_ID = "google-takeout-bookmarks";
const BOOKMARKS_ENTRY = "Bookmarks.html";
const ATTEMPT_TIMEOUT_MS = 15 * 60 * 1000;

let runningImport = null;

/**
 * Prototype of the Google Takeout bookmarks import on desktop, driven by
 * the same journey controller, runner and config as Firefox for Android.
 * There is no UI yet: with `browser.webflow.takeoutImport.enabled` set, run
 * it from the Browser Console:
 *
 *   ChromeUtils.importESModule(
 *     "moz-src:///browser/components/webflow/TakeoutBookmarksImport.sys.mjs"
 *   ).TakeoutBookmarksImport.run();
 */
export const TakeoutBookmarksImport = {
  /**
   * Runs the export in a hidden browser, opens Google's sign-in in a tab
   * when Google asks for it, and imports the downloaded bookmarks. Calling it
   * again while an import runs returns the running import.
   *
   * @returns {Promise<number>} The number of imported bookmarks.
   */
  run() {
    runningImport ??= runImport().finally(() => {
      runningImport = null;
    });
    return runningImport;
  },
};

async function runImport() {
  const feature = lazy.NimbusFeatures.webFlowTakeoutImport;
  if (!feature.getVariable("enabled")) {
    throw new Error("browser.webflow.takeoutImport.enabled is false");
  }
  feature.recordExposureEvent();
  // Before the first sync, this returns the dump shipped with the build.
  const [config] = await lazy.RemoteSettings(JOURNEYS_COLLECTION).get({
    filters: { id: JOURNEY_ID },
  });
  if (!config) {
    throw new Error(`No ${JOURNEY_ID} journey in ${JOURNEYS_COLLECTION}`);
  }

  const startedAt = new Date();
  const { promise: archive, resolve, reject } = Promise.withResolvers();
  const timeout = lazy.setTimeout(
    () => reject(new Error("Timed out waiting for the export")),
    ATTEMPT_TIMEOUT_MS
  );

  const downloadHosts = new Set(config["download-hosts"]);
  const downloadView = {
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
  const downloads = await lazy.Downloads.getList(lazy.Downloads.ALL);
  await downloads.addView(downloadView);
  const signIn = new SignInTab(config, reject);

  try {
    await lazy.WebFlow.run({
      config,
      done: archive,
      onMessage: message => {
        console.log("Takeout import:", message);
        if (message.status === "error") {
          reject(new Error(`Journey step ${message.actionId} failed: ${message.detail}`));
        }
      },
      onAuthRequired: (url, resume) => signIn.open(url, resume),
    });
    const count = await importBookmarksFromArchive(await archive);
    console.log(`Takeout import: imported ${count} bookmarks`);
    return count;
  } finally {
    lazy.clearTimeout(timeout);
    signIn.close();
    await downloads.removeView(downloadView);
  }
}

/**
 * The visible tab in which the user signs in when Google asks for it. The
 * hidden browser shares the user's cookies, so once the tab leaves the auth
 * pages the journey resumes there. The sign-in itself may also start the
 * export download in this tab, which the download watcher picks up too.
 */
class SignInTab {
  #config;
  #onAbandoned;
  #tab = null;
  #listener = null;

  constructor(config, onAbandoned) {
    this.#config = config;
    this.#onAbandoned = onAbandoned;
  }

  open(url, resume) {
    if (this.#tab) {
      return;
    }
    const { gBrowser } = lazy.BrowserWindowTracker.getTopWindow();
    const tab = gBrowser.addTrustedTab(url, { inBackground: false });
    const authHosts = new Set(this.#config["auth-hosts"]);
    this.#tab = tab;
    this.#listener = {
      onLocationChange: (browser, webProgress, request, location) => {
        if (browser !== tab.linkedBrowser || !webProgress.isTopLevel) {
          return;
        }
        let host = "";
        try {
          host = location.host;
        } catch {
          // URIs without a host, such as about:blank.
        }
        if (host && !authHosts.has(host)) {
          this.close();
          resume();
        }
      },
    };
    gBrowser.addTabsProgressListener(this.#listener);
    tab.addEventListener(
      "TabClose",
      () => {
        if (this.#tab === tab) {
          this.#detach();
          this.#onAbandoned(new Error("The sign-in tab was closed"));
        }
      },
      { once: true }
    );
  }

  close() {
    const tab = this.#tab;
    if (!tab) {
      return;
    }
    this.#detach();
    if (tab.isConnected && !tab.closing) {
      tab.ownerGlobal.gBrowser.removeTab(tab);
    }
  }

  #detach() {
    this.#tab.ownerGlobal.gBrowser.removeTabsProgressListener(this.#listener);
    this.#tab = null;
    this.#listener = null;
  }
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
