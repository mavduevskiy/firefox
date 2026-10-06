/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * The journey controller, shared by both platforms: the Android extension
 * loads it as a background script, and desktop's WebFlow.sys.mjs loads it as
 * a subscript. It has no platform code.
 *
 * A journey is data (the same config Android gets from Nimbus, with keys in
 * the Nimbus kebab-case form). Given the URL of the page the runner is on,
 * the controller replies with the resolved actions for that page. The URL is
 * the state; the only memory is which `once` pages were already handed out,
 * so pages that reload themselves (the Takeout archive page reloads after the
 * download click) do not loop.
 */
this.WebFlowController = (() => {
  const ACTION_TYPES = new Set(["click", "scroll", "expect", "extract", "navigate"]);
  const EXPECTATION_TYPES = new Set(["element", "gone", "url"]);

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
   * (unset optional fields). Keys of `selectors` are names, not fields, and
   * are kept as they are.
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
   * Validates the config and resolves everything that can be resolved ahead
   * of time: regular expressions are compiled and selector references are
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
          // Defaults to true: a page interrupted by an auth interstitial
          // usually did not get its effect and has to run again.
          retryAfterAuth: page.retryAfterAuth ?? true,
          path: compileRegExp(page.pathPattern, `${page.id}/pathPattern`),
          actions: page.actions.map(a => compileAction(a, selectors, defaults, page.id)),
        };
      }),
    };
  }

  return class WebFlowController {
    #config = null;
    #handedOutOnce = new Map();

    /**
     * @param {object} rawConfig The journey config, in the Nimbus kebab-case form.
     */
    constructor(rawConfig) {
      try {
        this.#config = compileConfig(rawConfig);
      } catch (e) {
        /** Why the config was rejected, or null when it is valid. */
        this.error = String(e?.message || e);
        return;
      }
      this.error = null;
    }

    /** @returns {boolean} Whether `hostname` is one of the journey's auth hosts. */
    isAuthHost(hostname) {
      return !!this.#config?.authHosts.has(hostname);
    }

    /** @returns {boolean} Whether `hostname` is one of the hosts the journey runs on. */
    isJourneyHost(hostname) {
      return !!this.#config?.hosts.has(hostname);
    }

    /**
     * Returns what the runner should do on the page at `url`: a `plan` with
     * the resolved actions, `idle` with a reason, or an `error`.
     */
    plan(url) {
      if (this.error) {
        return { type: "error", reason: this.error };
      }
      const { hostname, pathname } = new URL(url);
      if (this.#config.authHosts.has(hostname)) {
        // An auth interstitial interrupted the journey: unless the page says
        // otherwise, the page that led here did not get its effect and has
        // to be able to run again once the user is back.
        for (const [key, page] of this.#handedOutOnce) {
          if (page.retryAfterAuth) {
            this.#handedOutOnce.delete(key);
          }
        }
        return { type: "idle", reason: "auth-required" };
      }
      if (!this.#config.hosts.has(hostname)) {
        return { type: "idle", reason: "unhandled-host", detail: hostname };
      }

      const prefix = pathname.match(this.#config.pathPrefix)?.[0] ?? "";
      const path = pathname.slice(prefix.length) || "/";
      const page = this.#config.journey.find(p => p.path.test(path));
      if (!page) {
        return { type: "idle", reason: "unhandled-path", detail: path };
      }

      let onceKey;
      if (page.once) {
        onceKey = `${page.id}:${path}`;
        if (this.#handedOutOnce.has(onceKey)) {
          return { type: "idle", reason: "already-run", detail: path };
        }
        // Recorded before the actions run so a self-reloading page cannot
        // request them again; released by release() so a failed run can retry.
        this.#handedOutOnce.set(onceKey, page);
      }

      return {
        type: "plan",
        pageId: page.id,
        onceKey,
        vars: { prefix },
        actions: page.actions,
      };
    }

    /** Lets a `once` page whose actions failed run again. */
    release(onceKey) {
      if (onceKey) {
        this.#handedOutOnce.delete(onceKey);
      }
    }
  };
})();
