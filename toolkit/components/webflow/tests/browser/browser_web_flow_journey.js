/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

// Runs a journey on fixture pages through WebFlow.sys.mjs. The shipped
// extension only matches Google pages, so the test loads the same script
// files in a test extension that matches example.com.

const { WebFlow } = ChromeUtils.importESModule(
  "moz-src:///toolkit/components/webflow/WebFlow.sys.mjs"
);
const { sinon } = ChromeUtils.importESModule(
  "resource://testing-common/Sinon.sys.mjs"
);

const TEST_DIR = "/browser/toolkit/components/webflow/tests/browser/";
const EXTENSION_FILES = [
  "web-flow-background.js",
  "web-flow-content.js",
  "experiment-apis/webFlow.js",
  "experiment-apis/webFlow.json",
];

const CONFIG = {
  hosts: ["example.com"],
  "auth-hosts": ["auth.example.com"],
  "path-prefix-pattern": "^$",
  "timeout-ms": 5000,
  "settle-ms": 0,
  selectors: {
    "start-button": "#start",
    pending: ".pending",
    "done-button": "#done",
  },
  journey: [
    {
      id: "start",
      "path-pattern": "start[.]html$",
      actions: [
        { id: "start-click", type: "click", "selector-ref": "start-button" },
        {
          id: "id-found",
          type: "extract",
          "selector-ref": "pending",
          attribute: "data-id",
          variable: "itemId",
        },
        {
          id: "pending-gone",
          type: "expect",
          expectations: [{ type: "gone", "selector-ref": "pending" }],
        },
        { id: "go-done", type: "navigate", url: `${TEST_DIR}done.html?id={itemId}` },
      ],
    },
    {
      id: "done",
      "path-pattern": "done[.]html$",
      actions: [{ id: "done-click", type: "click", "selector-ref": "done-button" }],
    },
  ],
};

async function loadTestExtension() {
  const files = {};
  for (const name of EXTENSION_FILES) {
    files[name] = await (await fetch(`resource://webflow/extension/${name}`)).text();
  }
  const manifest = await (await fetch("resource://webflow/extension/manifest.json")).json();
  manifest.content_scripts[0].matches = ["https://example.com/*"];
  manifest.permissions = ["tabs", "https://example.com/*"];
  delete manifest.browser_specific_settings;
  const extension = ExtensionTestUtils.loadExtension({
    isPrivileged: true,
    manifest,
    files,
  });
  await extension.startup();
  return extension;
}

add_task(async function test_journey_runs_across_pages() {
  const sandbox = sinon.createSandbox();
  sandbox.stub(WebFlow, "_installExtension").resolves();
  sandbox.stub(WebFlow, "_uninstallExtension").resolves();
  const extension = await loadTestExtension();

  const messages = [];
  const tab = await BrowserTestUtils.openNewForegroundTab(gBrowser, "about:blank");
  const finished = new Promise(resolve => {
    WebFlow.start({
      tab,
      config: CONFIG,
      onMessage: message => {
        messages.push(message);
        if (message.actionId === "done-click" || message.status === "error") {
          resolve();
        }
      },
    }).then(attemptId => {
      BrowserTestUtils.startLoadingURIString(
        tab.linkedBrowser,
        `https://example.com${TEST_DIR}start.html`
      );
      registerCleanupFunction(() => WebFlow.stop(attemptId));
    });
  });
  await finished;

  Assert.deepEqual(
    messages.map(m => `${m.actionId}:${m.status}`),
    [
      "page:start:success",
      "start-click:success",
      "id-found:success",
      "pending-gone:success",
      "go-done:success",
      "page:done:success",
      "done-click:success",
    ],
    "every action of both pages ran in order"
  );
  Assert.equal(messages.find(m => m.actionId === "id-found").detail, "item-42");

  const clicked = await SpecialPowers.spawn(
    tab.linkedBrowser,
    [],
    () => content.document.body.dataset.clicked
  );
  Assert.equal(clicked, "item-42", "the extracted value reached the next page");

  BrowserTestUtils.removeTab(tab);
  await extension.unload();
  sandbox.restore();
});
