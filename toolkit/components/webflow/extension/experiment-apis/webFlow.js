/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/* global ExtensionAPI, ExtensionCommon */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  ExtensionParent: "resource://gre/modules/ExtensionParent.sys.mjs",
  WebFlow: "moz-src:///toolkit/components/webflow/WebFlow.sys.mjs",
});

ChromeUtils.defineLazyGetter(lazy, "tabTracker", () => {
  return lazy.ExtensionParent.apiManager.global.tabTracker;
});

this.webFlow = class WebFlowAPI extends ExtensionAPI {
  getAPI(context) {
    const { EventManager } = ExtensionCommon;

    // The host knows the tab as a native tab; the extension as a tab id.
    const serialize = attempt =>
      attempt && {
        attemptId: attempt.attemptId,
        tabId: lazy.tabTracker.getId(attempt.tab),
        config: attempt.config,
      };

    return {
      webFlow: {
        onConfigure: new EventManager({
          context,
          name: "webFlow.onConfigure",
          register: fire => {
            const listener = attempt => {
              fire.async(serialize(attempt)).catch(() => {}); // ignore Message Manager disconnects
            };
            lazy.WebFlow.addConfigureListener(listener);
            return () => lazy.WebFlow.removeConfigureListener(listener);
          },
        }).api(),

        async getAttempt() {
          return serialize(lazy.WebFlow.currentAttempt);
        },

        async report(message) {
          lazy.WebFlow.report(message);
        },
      },
    };
  }
};
