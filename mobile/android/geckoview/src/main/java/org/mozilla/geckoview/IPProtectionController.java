/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

/**
 * Interface for managing IP protection state. Implemented by
 * {@link DefaultIPProtectionController}.
 *
 * <p>Feature modules should depend on this interface rather than the concrete
 * {@link DefaultIPProtectionController} class.
 */
public interface IPProtectionController {

  /** Gets the current {@link Delegate}. */
  @UiThread
  @Nullable
  Delegate getDelegate();

  /** Sets the {@link Delegate} for state change notifications. */
  @UiThread
  void setDelegate(@Nullable Delegate delegate);

  /**
   * Sets the {@link TokenProvider} used to supply authentication tokens to the IP protection
   * service. Pass {@code null} to sign out.
   *
   * @return A {@link GeckoResult} that resolves to the updated {@link StateInfo}.
   */
  @UiThread
  @NonNull
  GeckoResult<StateInfo> setTokenProvider(@Nullable TokenProvider provider);

  /** Activates the IP proxy. */
  @UiThread
  void activate();

  /** Deactivates the IP proxy. */
  @UiThread
  void deactivate();

  /** Delegate for receiving IP protection state notifications. */
  interface Delegate {
    @UiThread
    default void onStateChanged(final @NonNull StateInfo info) {}
  }

  /**
   * Provides a fresh authentication token on demand. Called each time the JS layer needs to make
   * a Guardian API request, so tokens are never cached in the browser process.
   */
  interface TokenProvider {
    @UiThread
    @NonNull
    GeckoResult<String> getToken();
  }

  /** Holds information about the current IP protection state and usage. */
  class StateInfo {
    /** The service has not been initialized yet. */
    public static final int SERVICE_STATE_UNINITIALIZED = 0;

    /** The user is not eligible or still not signed in. */
    public static final int SERVICE_STATE_UNAVAILABLE = 1;

    /** The user is signed out but eligible. */
    public static final int SERVICE_STATE_UNAUTHENTICATED = 2;

    /** The service is ready to be activated. */
    public static final int SERVICE_STATE_READY = 3;

    /** The proxy is not ready. */
    public static final int PROXY_STATE_NOT_READY = 0;

    /** The proxy is ready to be activated. */
    public static final int PROXY_STATE_READY = 1;

    /** The proxy is in the process of activating. */
    public static final int PROXY_STATE_ACTIVATING = 2;

    /** The proxy is active. */
    public static final int PROXY_STATE_ACTIVE = 3;

    /** The proxy encountered an error. */
    public static final int PROXY_STATE_ERROR = 4;

    /** The proxy is paused (e.g. bandwidth limit reached). */
    public static final int PROXY_STATE_PAUSED = 5;

    /** The current service state. */
    public final int serviceState;

    /** The current proxy state. */
    public final int proxyState;

    /** The last error string, if {@link #proxyState} is {@link #PROXY_STATE_ERROR}. */
    public final @Nullable String lastError;

    /** Remaining usage allowance in bytes, or -1 if unavailable. */
    public final long remaining;

    /** Maximum usage allowance in bytes, or -1 if unavailable. */
    public final long max;

    /** The time when usage resets, as an ISO 8601 string, or null if unavailable. */
    public final @Nullable String resetTime;

    /** Constructs an empty StateInfo with default uninitialized values. */
    protected StateInfo() {
      serviceState = SERVICE_STATE_UNINITIALIZED;
      proxyState = PROXY_STATE_NOT_READY;
      lastError = null;
      remaining = -1L;
      max = -1L;
      resetTime = null;
    }

    /* package */ StateInfo(
        final int proxyState,
        final int serviceState,
        final long remaining,
        final long max,
        final @Nullable String resetTime,
        final @Nullable String lastError) {
      this.proxyState = proxyState;
      this.serviceState = serviceState;
      this.remaining = remaining;
      this.max = max;
      this.resetTime = resetTime;
      this.lastError = lastError;
    }
  }
}
