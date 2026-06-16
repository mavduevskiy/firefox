/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

import { GeckoViewUtils } from "resource://gre/modules/GeckoViewUtils.sys.mjs";

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  EventDispatcher: "resource://gre/modules/Messaging.sys.mjs",
  IPPAndroidAuthProvider:
    "moz-src:///toolkit/components/ipprotection/fxa/IPPAndroidAuthProvider.sys.mjs",
  IPPGpiAuthProvider:
    "moz-src:///toolkit/components/ipprotection/gpi/IPPGpiAuthProvider.sys.mjs",
  IPPProxyManager:
    "moz-src:///toolkit/components/ipprotection/IPPProxyManager.sys.mjs",
  IPProtectionActivator:
    "moz-src:///toolkit/components/ipprotection/IPProtectionActivator.sys.mjs",
  IPProtectionServerlist:
    "moz-src:///toolkit/components/ipprotection/IPProtectionServerlist.sys.mjs",
  IPProtectionService:
    "moz-src:///toolkit/components/ipprotection/IPProtectionService.sys.mjs",
  RECOMMENDED_COUNTRY_CODE:
    "moz-src:///toolkit/components/ipprotection/IPProtectionServerlist.sys.mjs",
});

const { debug, warn } = GeckoViewUtils.initLogging("GeckoViewIPProtection");

const AUTH_PROVIDER_PREF = "toolkit.ipProtection.android.authProvider";

// Persisted egress location (ISO 3166-1 alpha-2 country code). Empty means the
// recommended (anycast) location. Shared with the desktop UI.
const EGRESS_LOCATION_PREF = "browser.ipProtection.egressLocation";

// Event dispatched to IPProtectionController.java carrying the current location
// catalog and the selected country code.
const LOCATIONS_CHANGED_EVENT = "GeckoView:IPProtection:LocationsChanged";

// Event dispatched by IPProtectionServerlist when its catalog changes.
const SERVERLIST_CHANGED_EVENT = "IPProtectionServerlist:ListChanged";

let initialized = false;

export const GeckoViewIPProtection = {
  // Events dispatched by components in toolkit/components/ipprotection.
  handleEvent(event) {
    let detail;
    switch (event.type) {
      case SERVERLIST_CHANGED_EVENT: {
        this.emitLocations();
        return;
      }
      case "IPPProxyManager:StateChanged": {
        const state = lazy.IPPProxyManager.state;
        detail = {
          state,
          errorType:
            state === "error" ? (lazy.IPPProxyManager.errorType ?? null) : null,
        };
        break;
      }
      case "IPPProxyManager:UsageChanged": {
        const { usage } = event.detail;
        detail = {
          remaining: Number(usage.remaining),
          max: Number(usage.max),
          resetTime: usage.reset?.toString() ?? null,
        };
        break;
      }
      default:
        detail = event.detail;
    }
    lazy.EventDispatcher.instance.sendRequest(
      `GeckoView:IPProtection:${event.type}`,
      detail
    );
  },

  // nsIObserver for the egress location pref, so a selection change made here
  // (or anywhere else) is pushed back to IPProtectionController.java.
  observe(aSubject, aTopic, aData) {
    if (aTopic === "nsPref:changed" && aData === EGRESS_LOCATION_PREF) {
      this.emitLocations();
    }
  },

  // Currently selected country code, or null for the recommended location.
  get selectedLocation() {
    const value = Services.prefs.getStringPref(EGRESS_LOCATION_PREF, "");
    return value && value !== lazy.RECOMMENDED_COUNTRY_CODE ? value : null;
  },

  // Push the current location catalog and selection to the Java controller.
  emitLocations() {
    lazy.EventDispatcher.instance.sendRequest(LOCATIONS_CHANGED_EVENT, {
      locations: lazy.IPProtectionServerlist.countries,
      selected: this.selectedLocation,
    });
  },

  // Events dispatched from IPProtectionController.java via EventDispatcher.
  onEvent(aEvent, aData, aCallback) {
    debug`onEvent ${aEvent}`;

    switch (aEvent) {
      case "GeckoView:IPProtection:Init": {
        if (!initialized) {
          initialized = true;
          lazy.IPPProxyManager.addEventListener(
            "IPPProxyManager:StateChanged",
            GeckoViewIPProtection
          );
          lazy.IPPProxyManager.addEventListener(
            "IPPProxyManager:UsageChanged",
            GeckoViewIPProtection
          );
          lazy.IPProtectionService.addEventListener(
            "IPProtectionService:StateChanged",
            GeckoViewIPProtection
          );
          lazy.IPProtectionServerlist.addEventListener(
            SERVERLIST_CHANGED_EVENT,
            GeckoViewIPProtection
          );
          Services.prefs.addObserver(
            EGRESS_LOCATION_PREF,
            GeckoViewIPProtection
          );
          let providerName = Services.prefs.getCharPref(AUTH_PROVIDER_PREF, "");
          if (!providerName) {
            providerName = aData?.isSignedIn ? "fxa" : "gpi";
            Services.prefs.setCharPref(AUTH_PROVIDER_PREF, providerName);
          }
          if (providerName === "fxa") {
            lazy.IPProtectionActivator.setAuthProvider(
              lazy.IPPAndroidAuthProvider
            );
            lazy.IPProtectionActivator.addHelpers(
              lazy.IPPAndroidAuthProvider.helpers
            );
          } else {
            lazy.IPProtectionActivator.setAuthProvider(lazy.IPPGpiAuthProvider);
            lazy.IPProtectionActivator.addHelpers(
              lazy.IPPGpiAuthProvider.helpers
            );
          }
          lazy.IPProtectionActivator.init();
          // Nudge a catalog fetch (no-op if already populated) and push the
          // current snapshot so the controller has an initial value to render.
          lazy.IPProtectionServerlist.maybeFetchList();
          GeckoViewIPProtection.emitLocations();
        }
        aCallback.onSuccess();
        break;
      }
      case "GeckoView:IPProtection:Uninit": {
        if (initialized) {
          initialized = false;
          lazy.IPPProxyManager.removeEventListener(
            "IPPProxyManager:StateChanged",
            GeckoViewIPProtection
          );
          lazy.IPPProxyManager.removeEventListener(
            "IPPProxyManager:UsageChanged",
            GeckoViewIPProtection
          );
          lazy.IPProtectionService.removeEventListener(
            "IPProtectionService:StateChanged",
            GeckoViewIPProtection
          );
          lazy.IPProtectionServerlist.removeEventListener(
            SERVERLIST_CHANGED_EVENT,
            GeckoViewIPProtection
          );
          Services.prefs.removeObserver(
            EGRESS_LOCATION_PREF,
            GeckoViewIPProtection
          );
          lazy.IPProtectionActivator.uninit();
          lazy.IPProtectionActivator.removeHelpers();
        }
        aCallback.onSuccess();
        break;
      }
      case "GeckoView:IPProtection:IPProtectionService:GetState": {
        aCallback.onSuccess({ state: lazy.IPProtectionService.state });
        break;
      }
      case "GeckoView:IPProtection:IPPProxyManager:GetState": {
        const state = lazy.IPPProxyManager.state;
        aCallback.onSuccess({
          state,
          errorType:
            state === "error" ? (lazy.IPPProxyManager.errorType ?? null) : null,
        });
        break;
      }
      case "GeckoView:IPProtection:Activate": {
        const country =
          Services.prefs.getStringPref(EGRESS_LOCATION_PREF, "") || undefined;
        lazy.IPPProxyManager.start(true, false, country)
          .then(({ started, error } = {}) => {
            if (started) {
              aCallback.onSuccess();
            } else {
              aCallback.onError(error ?? "generic-error");
            }
          })
          .catch(err => {
            aCallback.onError(
              typeof err === "string" ? err : (err?.message ?? "generic-error")
            );
          });
        break;
      }
      case "GeckoView:IPProtection:Enroll": {
        lazy.IPProtectionService.authProvider
          .enroll()
          .then(({ isEnrolledAndEntitled, error } = {}) => {
            aCallback.onSuccess({
              isEnrolledAndEntitled: !!isEnrolledAndEntitled,
              error: error ?? null,
            });
          })
          .catch(err => {
            aCallback.onError(
              typeof err === "string" ? err : (err?.message ?? "generic-error")
            );
          });
        break;
      }
      case "GeckoView:IPProtection:Deactivate": {
        lazy.IPPProxyManager.stop()
          .then(() => {
            aCallback.onSuccess();
          })
          .catch(err => {
            aCallback.onError(
              typeof err === "string" ? err : (err?.message ?? "generic-error")
            );
          });
        break;
      }
      case "GeckoView:IPProtection:SetLocation": {
        // Persist the selection (empty string means recommended) and, if the
        // proxy is already active, switch the live connection to it. When the
        // proxy is inactive the selection is applied on the next activation.
        const code = aData?.location || "";
        Services.prefs.setStringPref(EGRESS_LOCATION_PREF, code);
        if (lazy.IPPProxyManager.state === "active") {
          lazy.IPPProxyManager.switch(
            code && code !== lazy.RECOMMENDED_COUNTRY_CODE ? code : undefined
          );
        }
        aCallback.onSuccess();
        break;
      }
    }
  },
};
