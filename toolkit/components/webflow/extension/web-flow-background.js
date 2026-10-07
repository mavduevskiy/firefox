/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/* global WebFlowController */

/**
 * Android glue around the shared journey controller (web-flow-controller.js,
 * loaded before this script). The app only talks to the runner in its hidden
 * tab; the runner passes on the attempt id and journey config it got from the
 * app with every `ready` request, and this script keeps one controller for
 * the attempt, created the first time its id is seen.
 *
 * The controller's memory lives in this page, which is why the manifest
 * declares the background as persistent: an event page could be unloaded
 * mid-attempt and lose it.
 */

/** One import runs at a time; a new attempt id replaces the previous attempt. */
let currentAttempt = null;

function attemptFor(attemptId, config, tabId) {
  if (currentAttempt?.id !== attemptId) {
    currentAttempt = {
      id: attemptId,
      controller: new WebFlowController(config),
      // The tab of the first page of the attempt; the app only starts the
      // runner in its hidden tab, so other tabs never claim it.
      tabId,
    };
  }
  return currentAttempt;
}

function onReady({ attemptId, config, url }, sender) {
  const attempt = attemptFor(attemptId, config, sender.tab?.id);
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

const HANDLERS = {
  ready: onReady,
  failed: onFailed,
};

browser.runtime.onMessage.addListener((message, sender) => {
  const handler = HANDLERS[message?.type];
  return handler ? Promise.resolve(handler(message, sender)) : undefined;
});
