/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  AddonManager: "resource://gre/modules/AddonManager.sys.mjs",
});

const ADDON_ID = "web-flow-runner@mozilla.org";
const ADDON_BASE_URL = "resource://webflow/extension/";

/**
 * Desktop host of the web flow runner extension. It runs a journey, the same
 * config the Android app sends from Nimbus, in a tab, and passes every
 * message from the runner to the caller. Android hosts the extension from
 * the app instead.
 *
 * One attempt runs at a time; starting a new one replaces the previous one.
 */
export const WebFlow = {
  /** @type {?{attemptId: string, tab: object, config: object, onMessage: Function}} */
  currentAttempt: null,

  _configureListeners: new Set(),

  /**
   * Installs the extension and starts an attempt in `tab`. The tab should
   * be loaded with the journey's launch URL only after this resolves, so the
   * runner there finds the attempt configured.
   *
   * @param {object} options
   * @param {object} options.tab The native tab the journey runs in.
   * @param {object} options.config The journey config, in the Nimbus kebab-case form.
   * @param {Function} options.onMessage Called with every message from the runner.
   * @returns {Promise<string>} The id of the attempt.
   */
  async start({ tab, config, onMessage }) {
    await this._installExtension();

    const attemptId = Services.uuid.generateUUID().toString().slice(1, -1);
    this.currentAttempt = { attemptId, tab, config, onMessage };
    for (const listener of this._configureListeners) {
      listener(this.currentAttempt);
    }
    return attemptId;
  },

  /**
   * Ends the attempt and uninstalls the extension.
   *
   * @param {string} attemptId The attempt to stop; other attempts are left alone.
   */
  async stop(attemptId) {
    if (this.currentAttempt?.attemptId !== attemptId) {
      return;
    }
    this.currentAttempt = null;
    await this._uninstallExtension();
  },

  async _installExtension() {
    const manifest = await (await fetch(`${ADDON_BASE_URL}manifest.json`)).json();
    await lazy.AddonManager.maybeInstallBuiltinAddon(ADDON_ID, manifest.version, ADDON_BASE_URL);
  },

  async _uninstallExtension() {
    const addon = await lazy.AddonManager.getAddonByID(ADDON_ID);
    await addon?.uninstall();
  },

  /** Used by the webFlow experiment API. */
  addConfigureListener(listener) {
    this._configureListeners.add(listener);
  },

  /** Used by the webFlow experiment API. */
  removeConfigureListener(listener) {
    this._configureListeners.delete(listener);
  },

  /**
   * Used by the webFlow experiment API to pass a runner message on. Messages
   * of earlier attempts are dropped.
   */
  report(message) {
    const attempt = this.currentAttempt;
    if (attempt && message?.attemptId === attempt.attemptId) {
      attempt.onMessage(message);
    }
  },
};
