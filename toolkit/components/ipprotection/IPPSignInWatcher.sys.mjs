/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * Proxy holder for sign-in state. Platform-specific implementations
 * (IPPDesktopSignInWatcher, GeckoViewIPPSignInWatcher) register themselves
 * via setImplementation(); all property access is transparently forwarded.
 *
 * IPProtectionService and other consumers import this module and use
 * IPPSignInWatcher.isSignedIn without knowing which platform impl backs it.
 */

let _impl = null;

class IPPSignInWatcherDefault extends EventTarget {
  #signedIn = false;

  get isSignedIn() {
    return this.#signedIn;
  }

  set isSignedIn(signedIn) {
    this.#signedIn = signedIn;
  }

  get guardianClient() {
    return null;
  }

  init() {}
  initOnStartupCompleted() {}
  uninit() {}
}

const _default = new IPPSignInWatcherDefault();

const IPPSignInWatcher = new Proxy(_default, {
  get(target, prop, _receiver) {
    if (prop === "setImplementation") {
      return impl => {
        _impl = impl;
      };
    }
    const obj = _impl ?? target;
    const val = Reflect.get(obj, prop, obj);
    return typeof val === "function" ? val.bind(obj) : val;
  },
  set(target, prop, value) {
    const obj = _impl ?? target;
    return Reflect.set(obj, prop, value, obj);
  },
  has(target, prop) {
    const obj = _impl ?? target;
    return Reflect.has(obj, prop);
  },
  getOwnPropertyDescriptor(target, prop) {
    const obj = _impl ?? target;
    return Reflect.getOwnPropertyDescriptor(obj, prop);
  },
  defineProperty(target, prop, descriptor) {
    const obj = _impl ?? target;
    return Reflect.defineProperty(obj, prop, descriptor);
  },
  ownKeys(target) {
    const obj = _impl ?? target;
    return Reflect.ownKeys(obj);
  },
});

export { IPPSignInWatcher };
