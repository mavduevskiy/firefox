/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

// Runs journeys on fixture pages through WebFlow.sys.mjs, in its hidden
// browser, with the same controller and runner Android uses.

const { WebFlow } = ChromeUtils.importESModule(
  "moz-src:///toolkit/components/webflow/WebFlow.sys.mjs"
);

const TEST_DIR = "/browser/toolkit/components/webflow/tests/browser/";

const BASE_CONFIG = {
  hosts: ["example.com"],
  "auth-hosts": ["example.org"],
  "path-prefix-pattern": "^$",
  "timeout-ms": 5000,
  "settle-ms": 0,
  selectors: {
    "start-button": "#start",
    pending: ".pending",
    "done-button": "#done",
  },
};

/**
 * Runs `config` until a result satisfies `isLast`, and returns the results.
 */
async function runJourney(config, isLast, onAuthRequired = () => {}) {
  const messages = [];
  const { promise: done, resolve, reject } = Promise.withResolvers();
  await WebFlow.run({
    config,
    done,
    onMessage: message => {
      messages.push(message);
      if (message.status === "error") {
        reject(new Error(`${message.actionId}: ${message.detail}`));
      } else if (isLast(message)) {
        resolve();
      }
    },
    onAuthRequired,
  });
  return messages;
}

add_task(async function test_journey_runs_across_pages() {
  const config = {
    ...BASE_CONFIG,
    "launch-url": `https://example.com${TEST_DIR}start.html`,
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

  const messages = await runJourney(config, m => m.actionId === "done-click");

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
  Assert.equal(
    messages.find(m => m.actionId === "go-done").detail,
    `${TEST_DIR}done.html?id=item-42`,
    "the extracted value reached the next page"
  );
});

add_task(async function test_auth_page_is_reported_and_journey_resumes() {
  const startUrl = `https://example.com${TEST_DIR}start.html`;
  const config = {
    ...BASE_CONFIG,
    "launch-url": startUrl,
    journey: [
      {
        id: "start",
        "path-pattern": "start[.]html$",
        once: true,
        "retry-after-auth": false,
        actions: [
          { id: "to-auth", type: "navigate", url: `https://example.org${TEST_DIR}done.html` },
        ],
      },
    ],
  };
  const authUrls = [];

  const messages = await runJourney(
    config,
    m => m.actionId === "already-run",
    (url, resume) => {
      authUrls.push(url);
      resume();
    }
  );

  Assert.deepEqual(authUrls, [`https://example.org${TEST_DIR}done.html`]);
  Assert.deepEqual(
    messages.map(m => m.actionId),
    ["page:start", "to-auth", "auth-required", "already-run"],
    "resuming reloaded the last journey page in the hidden browser"
  );
});

add_task(async function test_invalid_config_is_rejected() {
  const config = {
    ...BASE_CONFIG,
    "launch-url": "https://example.com/",
    journey: [{ id: "p", "path-pattern": "^/$", actions: [{ id: "x", type: "nope" }] }],
  };

  await Assert.rejects(
    WebFlow.run({ config, done: Promise.resolve(), onMessage() {}, onAuthRequired() {} }),
    /unknown action type "nope"/
  );
});
