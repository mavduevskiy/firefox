/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import org.mozilla.gecko.EventDispatcher;
import org.mozilla.gecko.util.BundleEventListener;
import org.mozilla.gecko.util.EventCallback;
import org.mozilla.gecko.util.GeckoBundle;
import org.mozilla.gecko.util.ThreadUtils;

/** Default implementation of {@link IPProtectionController}. */
public class DefaultIPProtectionController implements IPProtectionController {

  private static int parseServiceState(final @NonNull String state) {
    switch (state) {
      case "unavailable":
        return StateInfo.SERVICE_STATE_UNAVAILABLE;
      case "unauthenticated":
        return StateInfo.SERVICE_STATE_UNAUTHENTICATED;
      case "ready":
        return StateInfo.SERVICE_STATE_READY;
      default:
        return StateInfo.SERVICE_STATE_UNINITIALIZED;
    }
  }

  private static int parseProxyState(final @NonNull String state) {
    switch (state) {
      case "ready":
        return StateInfo.PROXY_STATE_READY;
      case "activating":
        return StateInfo.PROXY_STATE_ACTIVATING;
      case "active":
        return StateInfo.PROXY_STATE_ACTIVE;
      case "error":
        return StateInfo.PROXY_STATE_ERROR;
      case "paused":
        return StateInfo.PROXY_STATE_PAUSED;
      default:
        return StateInfo.PROXY_STATE_NOT_READY;
    }
  }

  private Delegate mDelegate;
  private TokenProvider mTokenProvider;
  private final BundleEventListener mEventListener;

  /* package */ DefaultIPProtectionController() {
    mEventListener = new EventListener();
    EventDispatcher.getInstance()
        .registerUiThreadListener(
            mEventListener,
            "GeckoView:IPProtection:StateChanged",
            "GeckoView:IPProtection:GetToken");
  }

  @Override
  @UiThread
  @Nullable
  public Delegate getDelegate() {
    return mDelegate;
  }

  @Override
  @UiThread
  public void setDelegate(@Nullable final Delegate delegate) {
    ThreadUtils.assertOnUiThread();
    mDelegate = delegate;
  }

  @Override
  @UiThread
  @NonNull
  public GeckoResult<StateInfo> setTokenProvider(@Nullable final TokenProvider provider) {
    ThreadUtils.assertOnUiThread();
    mTokenProvider = provider;
    final GeckoBundle bundle = new GeckoBundle(1);
    bundle.putBoolean("hasProvider", provider != null);
    return EventDispatcher.getInstance()
        .queryBundle("GeckoView:IPProtection:SetTokenProvider", bundle)
        .map(DefaultIPProtectionController::bundleToStateInfo);
  }

  @Override
  @UiThread
  public void activate() {
    ThreadUtils.assertOnUiThread();
    EventDispatcher.getInstance().queryBundle("GeckoView:IPProtection:Activate", null);
  }

  @Override
  @UiThread
  public void deactivate() {
    ThreadUtils.assertOnUiThread();
    EventDispatcher.getInstance().queryBundle("GeckoView:IPProtection:Deactivate", null);
  }

  private static StateInfo bundleToStateInfo(final @NonNull GeckoBundle bundle) {
    return new StateInfo(
        parseProxyState(bundle.getString("proxyState", "")),
        parseServiceState(bundle.getString("serviceState", "")),
        bundle.getLong("remaining", -1L),
        bundle.getLong("max", -1L),
        bundle.getString("resetTime"),
        bundle.getString("lastError"));
  }

  private class EventListener implements BundleEventListener {
    @Override
    public void handleMessage(
        final String event, final GeckoBundle message, final EventCallback callback) {
      if ("GeckoView:IPProtection:StateChanged".equals(event)) {
        if (mDelegate != null) {
          mDelegate.onStateChanged(bundleToStateInfo(message));
        }
      } else if ("GeckoView:IPProtection:GetToken".equals(event)) {
        if (mTokenProvider == null) {
          callback.sendError("No token provider");
          return;
        }
        callback.resolveTo(
            mTokenProvider
                .getToken()
                .map(
                    token -> {
                      final GeckoBundle result = new GeckoBundle(1);
                      result.putString("token", token);
                      return result;
                    }));
      }
    }
  }
}
