/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  GuardianClient:
    "moz-src:///toolkit/components/ipprotection/GuardianClient.sys.mjs",
  IPPSignInWatcher:
    "moz-src:///toolkit/components/ipprotection/IPPSignInWatcher.sys.mjs",
  IPProtectionService:
    "moz-src:///toolkit/components/ipprotection/IPProtectionService.sys.mjs",
  UIState: "resource://services-sync/UIState.sys.mjs",
});

/**
 * Desktop implementation that monitors FxA sign-in state.
 * Registers itself as the backing implementation of the IPPSignInWatcher proxy.
 */
class IPPDesktopSignInWatcherImpl extends EventTarget {
  #signedIn = false;
  #fxaObserver = null;
  #guardianClient = null;

  get isSignedIn() {
    return this.#signedIn;
  }

  set isSignedIn(signedIn) {
    this.#signedIn = signedIn;
  }

  get guardianClient() {
    return this.#guardianClient;
  }

  init() {
    lazy.IPPSignInWatcher.setImplementation(this);
    this.#guardianClient = new lazy.GuardianClient();
    this.#signedIn = Services.prefs.prefHasUserValue("services.sync.username");
  }

  async initOnStartupCompleted() {
    const self = this;
    this.#fxaObserver = {
      QueryInterface: ChromeUtils.generateQI([
        Ci.nsIObserver,
        Ci.nsISupportsWeakReference,
      ]),

      observe() {
        let { status } = lazy.UIState.get();
        let signedIn = status == lazy.UIState.STATUS_SIGNED_IN;
        if (signedIn !== self.#signedIn) {
          self.#signedIn = signedIn;
          lazy.IPProtectionService.updateState();

          self.dispatchEvent(
            new CustomEvent("IPPSignInWatcher:StateChanged", {
              bubbles: true,
              composed: true,
            })
          );
        }
      },
    };

    Services.obs.addObserver(this.#fxaObserver, lazy.UIState.ON_UPDATE);
  }

  uninit() {
    if (this.#fxaObserver) {
      Services.obs.removeObserver(this.#fxaObserver, lazy.UIState.ON_UPDATE);
      this.#fxaObserver = null;
    }
  }
}

const IPPDesktopSignInWatcher = new IPPDesktopSignInWatcherImpl();

export { IPPDesktopSignInWatcher };
