/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

(() => {
  const MESSAGE_NAME = "takeoutStep";
  const MANAGE_ARCHIVE_BASE = "/manage/archive";
  const MANAGE_ARCHIVE = "/manage";

  function report(actionID, status, detail) {
    browser.runtime.sendMessage({
      name: MESSAGE_NAME,
      data: {
        result: {
          [status]: { actionID, ...(detail ? { detail } : {}) },
        },
      },
    });
  }

  const success = (actionID, detail) => report(actionID, "success", detail);
  const failure = (_actionID, _detail) => report(_actionID, "error", _detail);

  // Wait up to `timeoutMs` for an element matched by `selector` to appear.
  function waitForElement(
    selector,
    { timeoutMs = 25000, root = document } = {}
  ) {
    return new Promise((resolve, reject) => {
      const existing = root.querySelector(selector);
      if (existing) {
        resolve(existing);
        return;
      }
      const observer = new MutationObserver(() => {
        const el = root.querySelector(selector);
        if (el) {
          observer.disconnect();
          resolve(el);
        }
      });
      observer.observe(root.body || root, { childList: true, subtree: true });
      setTimeout(() => {
        observer.disconnect();
        reject(new Error(`timeout waiting for ${selector}`));
      }, timeoutMs);
    });
  }


  function waitForElementGone(
    selector,
    { timeoutMs = 25000, root = document } = {}
  ) {
    return new Promise((resolve, reject) => {
      if (!root.querySelector(selector)) {
        resolve();
        return;
      }
      const observer = new MutationObserver(() => {
        if (!root.querySelector(selector)) {
          observer.disconnect();
          resolve();
        }
      });
      observer.observe(root.body || root, {
        childList: true,
        subtree: true,
        attributes: true,
      });
      setTimeout(() => {
        observer.disconnect();
        reject(new Error(`timeout waiting for ${selector} to disappear`));
      }, timeoutMs);
    });
  }


  const POST_CLICK_SETTLE_MS = 500;
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  async function clickWhenReady(actionID, selector, options) {
    console.log("BOEK BOEK BOEK: clickWhenReady", actionID, selector)
    try {
      const el = await waitForElement(selector, options);
      console.log("BOEK BOEK BOEK: clickWhenReady foundElement", el)
      el.click();
      success(actionID);
      await sleep(POST_CLICK_SETTLE_MS);
    } catch (e) {
      failure(actionID, String(e?.message || e));
      throw e;
    }
  }

  async function scrollIntoView(actionID, selector, options) {
    try {
      const el = await waitForElement(selector, options);
      el.scrollIntoView({ behavior: "smooth", block: "center" });
      success(actionID);
    } catch (e) {
      failure(actionID, String(e?.message || e));
      throw e;
    }
  }

  async function waitFor(actionID, selector, options) {
    try {
      await waitForElement(selector, options);
      success(actionID);
    } catch (e) {
      failure(actionID, String(e?.message || e));
      throw e;
    }
  }

  // Selectors from the duckduckgo/privacy-configuration android override
  // (features.autofill.features.canImportBookmarksFromGoogleTakeout).
  const SELECTORS = {
    topDeselectAll:
      'div[role="tabpanel"] div:nth-child(2) div:nth-child(2) button, div[role="tabpanel"] button[aria-label="Deselect all"]',
    chromeUnchecked: 'input[name="Chrome"]:not(:checked)',
    chromeSection: 'c-wiz [data-id="chrome"]',
    chromeCheckbox: 'input[name="Chrome"]',
    chromeDataButton:
      'div[data-id="chrome"] div[data-is-transfer="false"] button:not([disabled])',
    chromeDataModal: "fieldset.rcetic",
    modalDeselectAll:
      'fieldset.rcetic div:nth-child(2) button:nth-of-type(2), fieldset.rcetic button[aria-label="Deselect all"]',
    bookmarksUnchecked:
      'fieldset.rcetic input[value="bookmarks"]:not(:checked)',
    okButtonEnabled:
      'div[isfullscreen] div:nth-child(3) > div[role="button"]:not([aria-disabled]):nth-of-type(2)',
    bookmarksChecked: 'fieldset.rcetic input[value="bookmarks"]:checked',
    nextStepButton:
      'div[role="tabpanel"] > div:nth-child(1) > div:nth-child(2) button, div[role="tabpanel"] button[aria-label="Next step"]',
    createExportButton: 'div[data-configure-step="1"] button',
    archiveIdHolder: "div[data-archive-id]",
    downloadLink: 'div[data-is-downloaded="false"] a[href*="&i=0&user="]',
    pendingRequestBanner: 'div[data-in-progress="true"][data-archive-id]',
  };

  // Wait up to 5 minutes for Google to finish creating the archive on the
  // /manage/archive page; everything else is a short DOM wait.
  const LONG = { timeoutMs: 5 * 60 * 1000 };

  async function findExportId() {
    const panels = document.querySelectorAll('div[role="tabpanel"]');
    for (const panel of Array.from(panels).reverse()) {
      const holder = panel.querySelector(SELECTORS.archiveIdHolder);
      const id = holder?.getAttribute("data-archive-id");
      if (id) {
        return id;
      }
    }
    return null;
  }

  async function runExportConfiguration() {
    success("takeout-loaded");

    // 1. Top-level "Deselect all".
    await clickWhenReady("deselect-all-click", SELECTORS.topDeselectAll);
    await waitFor("deselect-all-confirmed", SELECTORS.chromeUnchecked);

    // 2-3. Scroll to + tick the Chrome row.
    await scrollIntoView("chrome-section-scroll", SELECTORS.chromeSection);
    await clickWhenReady("chrome-section-click", SELECTORS.chromeCheckbox);

    // 4. Open the Chrome data-types modal.
    await clickWhenReady(
      "chrome-data-button-click",
      SELECTORS.chromeDataButton
    );
    await waitFor("chrome-data-modal-shown", SELECTORS.chromeDataModal);

    // 5. Inside the modal, deselect every Chrome data type.
    await clickWhenReady(
      "modal-deselect-all-click",
      SELECTORS.modalDeselectAll
    );
    await waitFor("modal-deselect-all-confirmed", SELECTORS.bookmarksUnchecked);

    // 6. Re-check just "Bookmarks".
    await clickWhenReady(
      "bookmarks-checkbox-click",
      SELECTORS.bookmarksUnchecked
    );
    await waitFor("bookmarks-checked", SELECTORS.bookmarksChecked);
    await waitFor("ok-button-enabled", SELECTORS.okButtonEnabled);

    // 7. Confirm the modal.
    await clickWhenReady("ok-button-click", SELECTORS.okButtonEnabled);

    // 8-9. Advance to step 2 of the Takeout wizard.
    await scrollIntoView("next-step-scroll", SELECTORS.nextStepButton);
    await clickWhenReady("next-step-click", SELECTORS.nextStepButton);

    // 10-11. Request export.
    await scrollIntoView("create-export-scroll", SELECTORS.createExportButton);
    await clickWhenReady("create-export-click", SELECTORS.createExportButton);
    window.location.href = `${MANAGE_ARCHIVE}`;
  }

  async function runManageExports() {
      // 13. Wait for the download link to appear and click it. Google can take
      // a long time to actually produce the archive; the user has already left
      // the configuration screen at this point so a 5-minute wait is fine.
      const card = await waitForElement('div[data-in-progress="true"][data-archive-id]');
      const id = card.getAttribute("data-archive-id");
      console.log("WOW: wound the id!", id)

      await waitForElementGone('div[data-in-progress="true"][data-archive-id]', LONG);
      window.location.href = `${MANAGE_ARCHIVE_BASE}/${id}`;
  }

  async function runArchiveDownload() {
    success("manage-archive-loaded");
    // the download page behaves funny - pressing the download button reloads the page and starts
    // the download on the reload with some delay, so we want to avoid falling into a infinite load
    // loop.
    // Sometimes, a re-auth is required as well - that's an edge case we should add support for.
    const archiveId = location.pathname.split("/").pop();
    const guardKey = `mozacTakeoutDownloaded:${archiveId}`;
    if (sessionStorage.getItem(guardKey)) {
      success("download-already-triggered", archiveId);
      return;
    }
    try {
      const el = await waitForElement(SELECTORS.downloadLink, LONG);
      sessionStorage.setItem(guardKey, "1");
      success("download-link-click");
      el.click();
      success("download-triggered");
    } catch (e) {
      failure("download-link-click", String(e?.message || e));
    }
  }

  async function runBookmarkExportJourney() {
    if (location.host !== "takeout.google.com") {
      return;
    }
    try {
      if (location.pathname === "/") {
        await runExportConfiguration();
      }  else if (location.pathname.startsWith(MANAGE_ARCHIVE_BASE)) {
        await runArchiveDownload();
      } else if (location.pathname.startsWith(MANAGE_ARCHIVE)) {
        await runManageExports();
      }
    } catch (_e) {
      // Per-step failure has already been reported.
    }
  }

  runBookmarkExportJourney();
})();
