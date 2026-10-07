/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  WebFlow: "moz-src:///toolkit/components/webflow/WebFlow.sys.mjs",
});

/**
 * Connects a page of the hidden browser to the attempt WebFlow.sys.mjs runs
 * in it: answers the child's request for the page's actions and passes its
 * results on.
 */
export class WebFlowParent extends JSWindowActorParent {
  receiveMessage({ name, data }) {
    const browser = this.browsingContext.top.embedderElement;
    switch (name) {
      case "WebFlow:Ready":
        // The URL comes from the parent's view of the page: the child runs in
        // a web content process and is not trusted with it.
        return lazy.WebFlow.planFor(browser, this.manager.documentURI.spec);
      case "WebFlow:Result":
        lazy.WebFlow.reportFor(browser, data);
        break;
      case "WebFlow:Failed":
        lazy.WebFlow.releaseFor(browser, data.onceKey);
        break;
    }
    return undefined;
  }
}
