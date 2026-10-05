/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * Journey controller for the Google Takeout import. This is the
 * site-specific half of the extension: the runner content script is generic
 * and only executes the actions this controller hands out.
 *
 * The runner announces every page it is injected into; the controller maps
 * the page URL to a journey page and replies with the resolved actions. The
 * URL is the state. The only per-attempt memory is which `once` pages were
 * already handed out, so pages that reload themselves (the archive page
 * reloads after the download click) do not loop.
 *
 * That memory lives in this page, which is why the manifest declares the
 * background as persistent: an event page could be unloaded mid-attempt and
 * lose it.
 *
 * The journey config comes from the app (a Nimbus feature) in a `configure`
 * message over the native port, together with the id of the attempt it
 * belongs to. Keys arrive in the Nimbus kebab-case form.
 */

const PORT_NAME = "mozacTakeoutImporter";
const CONFIGURE_TIMEOUT_MS = 10000;
const ACTION_TYPES = new Set(["click", "scroll", "expect", "extract", "navigate"]);
const EXPECTATION_TYPES = new Set(["element", "gone", "url"]);

/**
 * Only one import runs at a time; a `configure` message starts a new attempt
 * and discards the previous one.
 */
let currentAttempt = null;
let attemptWaiters = [];

function invalid(message) {
  return new Error(`invalid config: ${message}`);
}

function compileRegExp(pattern, where) {
  try {
    return new RegExp(pattern);
  } catch (e) {
    throw invalid(`${where}: ${e.message}`);
  }
}

function resolveSelector(selectors, ref, where) {
  const selector = selectors[ref];
  if (typeof selector !== "string" || !selector) {
    throw invalid(`${where}: unknown selector "${ref}"`);
  }
  return selector;
}

function compileExpectation(raw, selectors, defaults, where) {
  if (!EXPECTATION_TYPES.has(raw.type)) {
    throw invalid(`${where}: unknown expectation type "${raw.type}"`);
  }
  const expectation = { timeoutMs: defaults.timeoutMs, ...raw };
  if (raw.type === "url") {
    compileRegExp(raw.pattern, where);
  } else {
    expectation.selector = resolveSelector(selectors, raw.selectorRef, where);
  }
  return expectation;
}

function compileAction(raw, selectors, defaults, where) {
  if (!raw.id) {
    throw invalid(`${where}: action without id`);
  }
  const at = `${where}/${raw.id}`;
  if (!ACTION_TYPES.has(raw.type)) {
    throw invalid(`${at}: unknown action type "${raw.type}"`);
  }
  const action = {
    timeoutMs: defaults.timeoutMs,
    settleMs: defaults.settleMs,
    ...raw,
  };
  if (raw.selectorRef) {
    action.selector = resolveSelector(selectors, raw.selectorRef, at);
  }
  if (raw.type === "expect") {
    if (!Array.isArray(raw.expectations) || !raw.expectations.length) {
      throw invalid(`${at}: expect action without expectations`);
    }
    action.expectations = raw.expectations.map(e =>
      compileExpectation(e, selectors, action, at)
    );
  }
  if (raw.type === "navigate" && typeof raw.url !== "string") {
    throw invalid(`${at}: navigate action without url`);
  }
  return action;
}

/**
 * Converts the Nimbus kebab-case keys to camelCase and drops null values
 * (unset optional fields). Keys of `selectors` are names, not fields, and are
 * kept as they are.
 */
function normalize(value) {
  if (Array.isArray(value)) {
    return value.map(normalize);
  }
  if (value === null || typeof value !== "object") {
    return value;
  }
  const result = {};
  for (const [key, entry] of Object.entries(value)) {
    if (entry === null) {
      continue;
    }
    const camelKey = key.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    result[camelKey] = camelKey === "selectors" ? entry : normalize(entry);
  }
  return result;
}

/**
 * Validates the config and resolves everything that can be resolved ahead of
 * time: regular expressions are compiled and selector references are
 * replaced by their CSS selectors. Throws on the first problem found.
 */
function compileConfig(rawConfig) {
  const raw = normalize(rawConfig);
  for (const key of ["hosts", "authHosts", "journey"]) {
    if (!Array.isArray(raw?.[key])) {
      throw invalid(`"${key}" must be an array`);
    }
  }
  const selectors = raw.selectors ?? {};
  const defaults = { timeoutMs: raw.timeoutMs, settleMs: raw.settleMs };
  return {
    hosts: new Set(raw.hosts),
    authHosts: new Set(raw.authHosts),
    pathPrefix: compileRegExp(raw.pathPrefixPattern ?? "^$", "pathPrefixPattern"),
    journey: raw.journey.map(page => {
      if (!page.id || !Array.isArray(page.actions)) {
        throw invalid(`journey page "${page.id}" needs an id and actions`);
      }
      return {
        id: page.id,
        once: !!page.once,
        path: compileRegExp(page.pathPattern, `${page.id}/pathPattern`),
        actions: page.actions.map(a =>
          compileAction(a, selectors, defaults, page.id)
        ),
      };
    }),
  };
}

function onConfigure({ attemptId, config }) {
  let compiled = null;
  let error = null;
  try {
    compiled = compileConfig(config);
  } catch (e) {
    error = String(e?.message || e);
  }
  currentAttempt = {
    id: attemptId,
    config: compiled,
    error,
    tabId: null,
    handedOutOnce: new Set(),
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

function splitPath(config, pathname) {
  const prefix = pathname.match(config.pathPrefix)?.[0] ?? "";
  return { prefix, path: pathname.slice(prefix.length) || "/" };
}

async function onReady({ attemptId, url }, sender) {
  const attempt = await waitForAttempt(attemptId);
  if (!attempt) {
    return { type: "error", reason: "config-missing" };
  }
  if (attempt.error) {
    return { type: "error", reason: attempt.error };
  }

  attempt.tabId ??= sender.tab?.id;
  if (attempt.tabId !== sender.tab?.id) {
    return { type: "idle", reason: "foreign-tab" };
  }

  const { config } = attempt;
  const { hostname, pathname } = new URL(url);
  if (config.authHosts.has(hostname)) {
    // An auth interstitial interrupted the journey: the page that led here
    // did not get its effect, so it has to be able to run again once the
    // user is back.
    attempt.handedOutOnce.clear();
    return { type: "idle", reason: "auth-required" };
  }
  if (!config.hosts.has(hostname)) {
    return { type: "idle", reason: "unhandled-host", detail: hostname };
  }

  const { prefix, path } = splitPath(config, pathname);
  const page = config.journey.find(p => p.path.test(path));
  if (!page) {
    return { type: "idle", reason: "unhandled-path", detail: path };
  }

  let onceKey;
  if (page.once) {
    onceKey = `${page.id}:${path}`;
    if (attempt.handedOutOnce.has(onceKey)) {
      return { type: "idle", reason: "already-run", detail: path };
    }
    // Recorded before the actions run so a self-reloading page cannot
    // request them again; released by onFailed so a failed run can retry.
    attempt.handedOutOnce.add(onceKey);
  }

  return {
    type: "plan",
    pageId: page.id,
    onceKey,
    vars: { prefix },
    actions: page.actions,
  };
}

function onFailed({ attemptId, onceKey }) {
  if (currentAttempt?.id === attemptId && onceKey) {
    currentAttempt.handedOutOnce.delete(onceKey);
  }
}

const HANDLERS = {
  ready: onReady,
  failed: onFailed,
};

browser.runtime.onMessage.addListener((message, sender) =>
  HANDLERS[message?.type]?.(message, sender)
);

const nativePort = browser.runtime.connectNative(PORT_NAME);
nativePort.onMessage.addListener(message => {
  if (message?.type === "configure") {
    onConfigure(message);
  }
});
