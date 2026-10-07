/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  HiddenBrowserManager: "resource://gre/modules/HiddenFrame.sys.mjs",
});

const CONTROLLER_URL = "resource://webflow/extension/web-flow-controller.js";
const MESSAGE_MANAGER_GROUP = "webflow";

/**
 * Desktop host of the web flow runner. It runs a journey, a record of the
 * `web-flow-journeys` Remote Settings collection shared with Android, in a
 * hidden browser, with the journey controller and action runner Android uses
 * too. The WebFlow actors only exist in that browser, so pages the user opens
 * in tabs are never driven.
 */
export const WebFlow = {
  /** Attempts in progress, by hidden browser. */
  _attempts: new Map(),

  /**
   * Runs a journey in a hidden browser until `done` settles.
   *
   * The journey only drives pages; what ends it (for example a completed
   * download) is up to the caller, which signals it through `done`.
   *
   * @param {object} options
   * @param {object} options.config The journey record, with kebab-case keys.
   * @param {Promise} options.done Settles when the caller's goal is reached or abandoned.
   * @param {Function} options.onMessage Called with every result the runner reports.
   * @param {Function} options.onAuthRequired Called with the URL of an auth page the
   *   journey reached, and a `resume` function. The user has to sign in
   *   somewhere visible; calling `resume` afterwards reloads the last journey
   *   page in the hidden browser, which shares the user's cookies.
   * @returns {Promise} Resolves or rejects like `done`.
   */
  async run({ config, done, onMessage, onAuthRequired }) {
    const controller = this._createController(config);
    if (controller.error) {
      throw new Error(controller.error);
    }
    this._registerActor();

    return lazy.HiddenBrowserManager.withHiddenBrowser(
      async browser => {
        const load = url => {
          const uri = Services.io.newURI(url);
          browser.loadURI(uri, {
            triggeringPrincipal: Services.scriptSecurityManager.createContentPrincipal(uri, {}),
          });
        };
        const attempt = {
          controller,
          onMessage,
          lastJourneyUrl: config["launch-url"],
          onAuthRequired: url => onAuthRequired(url, () => load(attempt.lastJourneyUrl)),
        };
        this._attempts.set(browser, attempt);
        try {
          load(config["launch-url"]);
          return await done;
        } finally {
          this._attempts.delete(browser);
          browser.stop();
        }
      },
      { messageManagerGroup: MESSAGE_MANAGER_GROUP }
    );
  },

  /** Used by WebFlowParent: the actions for the page at `url`. */
  planFor(browser, url) {
    const attempt = this._attempts.get(browser);
    if (!attempt) {
      return { type: "idle", reason: "no-attempt" };
    }
    const plan = attempt.controller.plan(url);
    const { hostname } = new URL(url);
    if (attempt.controller.isAuthHost(hostname)) {
      attempt.onAuthRequired(url);
    } else if (attempt.controller.isJourneyHost(hostname)) {
      attempt.lastJourneyUrl = url;
    }
    return plan;
  },

  /** Used by WebFlowParent: passes a runner result to the caller. */
  reportFor(browser, message) {
    this._attempts.get(browser)?.onMessage(message);
  },

  /** Used by WebFlowParent: lets a failed `once` page run again. */
  releaseFor(browser, onceKey) {
    this._attempts.get(browser)?.controller.release(onceKey);
  },

  _createController(config) {
    if (!this._WebFlowController) {
      const scope = {};
      Services.scriptloader.loadSubScript(CONTROLLER_URL, scope);
      this._WebFlowController = scope.WebFlowController;
    }
    return new this._WebFlowController(config);
  },

  _registerActor() {
    if (this._actorRegistered) {
      return;
    }
    ChromeUtils.registerWindowActor("WebFlow", {
      parent: { esModuleURI: "moz-src:///toolkit/components/webflow/WebFlowParent.sys.mjs" },
      child: {
        esModuleURI: "moz-src:///toolkit/components/webflow/WebFlowChild.sys.mjs",
        events: { pageshow: {} },
      },
      messageManagerGroups: [MESSAGE_MANAGER_GROUP],
      // The journey runs on regular web pages. WebFlowParent takes nothing
      // but results and the failed page's key from the child.
      safeForUntrustedWebProcess: true,
    });
    this._actorRegistered = true;
  },
};
