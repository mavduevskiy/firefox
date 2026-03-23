# [android-components](../../../README.md) > Feature > Firefox VPN

A feature that allows the browser to route data through a VPN service provider.

## Usage

### Setting up the dependency

Use Gradle to download the library from maven.mozilla.org:

```Groovy
implementation "org.mozilla.components:feature-firefox-vpn:{latest-version}"
```

### FirefoxVpnFeature

`FirefoxVpnFeature` is a lifecycle-aware component that manages the VPN connection state and
notifies the caller via a callback whenever the connection status changes.

```kotlin
val vpnFeature = FirefoxVpnFeature { connected ->
    if (connected) {
        showVpnConnectedIndicator()
    } else {
        showVpnDisconnectedIndicator()
    }
}

// Bind to lifecycle
lifecycle.addObserver(vpnFeature)

// Or manually control
vpnFeature.start()
vpnFeature.stop()
```

## License

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/
