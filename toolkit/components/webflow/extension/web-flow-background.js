/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/* global WebFlowController */

/**
 * Android glue around the shared journey controller (web-flow-controller.js,
 * loaded before this script). The app sends the journey config over native
 * messaging in a `configure` message, together with the id of the attempt it
 * belongs to; the runner content script asks for the actions of each page and
 * reports its results here, which are forwarded to the app.
 *
 * The controller's memory lives in this page, which is why the manifest
 * declares the background as persistent: an event page could be unloaded
 * mid-attempt and lose it.
 */

const PORT_NAME = "mozacWebFlowRunner";
const CONFIGURE_TIMEOUT_MS = 10000;

/**
 * Only one import runs at a time; a `configure` message starts a new attempt
 * and discards the previous one.
 */
let currentAttempt = null;
let attemptWaiters = [];

const nativePort = browser.runtime.connectNative(PORT_NAME);

function onConfigure({ attemptId, config }) {
  currentAttempt = {
    id: attemptId,
    controller: new WebFlowController(config),
    // The first tab that receives "start" from the app claims the attempt.
    tabId: null,
  };
  // Waiters remove themselves once their attempt is configured.
  [...attemptWaiters].forEach(waiter => waiter());
}

/**
 * Resolves with the attempt once its `configure` message has arrived, or
 * with null after a timeout; the runner can announce a page before the app
 * has configured the attempt.
 */
function waitForAttempt(attemptId) {
  if (currentAttempt?.id === attemptId) {
    return Promise.resolve(currentAttempt);
  }
  return new Promise(resolve => {
    const finish = attempt => {
      clearTimeout(timer);
      attemptWaiters = attemptWaiters.filter(w => w !== waiter);
      resolve(attempt);
    };
    const waiter = () => {
      if (currentAttempt?.id === attemptId) {
        finish(currentAttempt);
      }
    };
    const timer = setTimeout(() => finish(null), CONFIGURE_TIMEOUT_MS);
    attemptWaiters.push(waiter);
  });
}

async function onReady({ attemptId, url }, sender) {
  const attempt = await waitForAttempt(attemptId);
  if (!attempt) {
    return { type: "error", reason: "config-missing" };
  }
  attempt.tabId ??= sender.tab?.id;
  if (attempt.tabId !== sender.tab?.id) {
    return { type: "idle", reason: "foreign-tab" };
  }
  return attempt.controller.plan(url);
}

function onFailed({ attemptId, onceKey }) {
  if (currentAttempt?.id === attemptId) {
    currentAttempt.controller.release(onceKey);
  }
}

function onResult(message) {
  nativePort.postMessage(message);
}

const HANDLERS = {
  ready: onReady,
  result: onResult,
  failed: onFailed,
};

browser.runtime.onMessage.addListener((message, sender) =>
  HANDLERS[message?.type]?.(message, sender)
);

nativePort.onMessage.addListener(message => {
  if (message?.type === "configure") {
    onConfigure(message);
  }
});
