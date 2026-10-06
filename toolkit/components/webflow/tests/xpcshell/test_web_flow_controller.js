/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

// Tests the journey controller (the extension's background script) on its
// own, with a fake `browser` object standing in for the WebExtension APIs.

const { setTimeout, clearTimeout } = ChromeUtils.importESModule(
  "resource://gre/modules/Timer.sys.mjs"
);

const CONFIG = {
  hosts: ["example.com"],
  "auth-hosts": ["auth.example.com"],
  "path-prefix-pattern": "^/u/[0-9]+",
  "timeout-ms": 1000,
  "settle-ms": 0,
  selectors: { "start-button": "#start", "pending-item": ".pending" },
  journey: [
    {
      id: "start",
      "path-pattern": "^/$",
      actions: [
        { id: "start-click", type: "click", "selector-ref": "start-button" },
        { id: "go", type: "navigate", url: "{prefix}/wait" },
      ],
    },
    {
      id: "retry-page",
      "path-pattern": "^/retry",
      once: true,
      actions: [{ id: "a", type: "click", "selector-ref": "start-button" }],
    },
    {
      id: "no-retry-page",
      "path-pattern": "^/no-retry",
      once: true,
      "retry-after-auth": false,
      actions: [{ id: "b", type: "click", "selector-ref": "start-button" }],
    },
    {
      id: "wait",
      "path-pattern": "^/wait",
      actions: [
        {
          id: "wait-gone",
          type: "expect",
          "timeout-ms": 5000,
          expectations: [{ type: "gone", "selector-ref": "pending-item" }],
          url: null,
        },
      ],
    },
  ],
};

const TAB_ID = 7;

/** Loads the controller with a fake desktop host and returns its handles. */
async function loadController() {
  let onMessage;
  let onConfigure;
  const reports = [];
  const sandbox = Cu.Sandbox(Services.scriptSecurityManager.getSystemPrincipal(), {
    wantGlobalProperties: ["URL"],
    freshCompartment: true,
  });
  sandbox.setTimeout = setTimeout;
  sandbox.clearTimeout = clearTimeout;
  sandbox.browser = {
    runtime: {
      getPlatformInfo: async () => ({ os: "linux" }),
      onMessage: { addListener: l => (onMessage = l) },
    },
    webFlow: {
      onConfigure: { addListener: l => (onConfigure = l) },
      getAttempt: async () => null,
      report: async m => reports.push(m),
    },
  };
  Services.scriptloader.loadSubScript(
    "resource://webflow/extension/web-flow-background.js",
    sandbox
  );
  // The controller subscribes to its host after an async platform check.
  while (!onConfigure) {
    await Promise.resolve();
  }
  return {
    send: (message, tabId = TAB_ID) => onMessage(message, { tab: { id: tabId } }),
    configure: (config = CONFIG, attemptId = "a1") =>
      onConfigure({ attemptId, tabId: TAB_ID, config }),
    reports,
  };
}

const ready = (path, attemptId = "a1") => ({
  type: "ready",
  attemptId,
  url: `https://example.com${path}`,
});

add_task(async function test_plan_resolves_selectors_and_prefix() {
  const controller = await loadController();
  controller.configure();

  const plan = await controller.send(ready("/u/1/"));

  Assert.equal(plan.type, "plan");
  Assert.equal(plan.pageId, "start");
  Assert.equal(plan.vars.prefix, "/u/1");
  Assert.equal(plan.actions[0].selector, "#start");
  Assert.equal(plan.actions[0].timeoutMs, 1000, "flow timeout is the default");
  Assert.equal(plan.actions[1].url, "{prefix}/wait");
});

add_task(async function test_action_overrides_and_nulls() {
  const controller = await loadController();
  controller.configure();

  const plan = await controller.send(ready("/wait"));

  const [action] = plan.actions;
  Assert.equal(action.timeoutMs, 5000, "action timeout overrides the default");
  Assert.equal(action.expectations[0].timeoutMs, 5000, "expectations inherit it");
  Assert.equal(action.expectations[0].selector, ".pending");
  Assert.ok(!("url" in action), "null values from Nimbus are dropped");
});

add_task(async function test_other_tabs_are_not_driven() {
  const controller = await loadController();
  controller.configure();

  const reply = await controller.send(ready("/"), TAB_ID + 1);

  Assert.deepEqual({ ...reply }, { type: "idle", reason: "foreign-tab" });
});

add_task(async function test_hello_starts_only_the_attempt_tab() {
  const controller = await loadController();
  const hello = controller.send({ type: "hello" });
  controller.configure();

  Assert.deepEqual({ ...(await hello) }, { type: "start", attemptId: "a1" });
});

add_task(async function test_once_pages_and_auth() {
  const controller = await loadController();
  controller.configure();

  Assert.equal((await controller.send(ready("/retry"))).type, "plan");
  Assert.equal((await controller.send(ready("/retry"))).reason, "already-run");
  Assert.equal((await controller.send(ready("/no-retry"))).type, "plan");

  const auth = await controller.send({
    type: "ready",
    attemptId: "a1",
    url: "https://auth.example.com/signin",
  });
  Assert.equal(auth.reason, "auth-required");

  Assert.equal(
    (await controller.send(ready("/retry"))).type,
    "plan",
    "a page interrupted by auth runs again"
  );
  Assert.equal(
    (await controller.send(ready("/no-retry"))).reason,
    "already-run",
    "unless it opted out of retrying"
  );
});

add_task(async function test_failed_action_releases_once_page() {
  const controller = await loadController();
  controller.configure();

  const plan = await controller.send(ready("/retry"));
  controller.send({ type: "failed", attemptId: "a1", onceKey: plan.onceKey });

  Assert.equal((await controller.send(ready("/retry"))).type, "plan");
});

add_task(async function test_new_attempt_resets_state() {
  const controller = await loadController();
  controller.configure();
  await controller.send(ready("/retry"));

  controller.configure(CONFIG, "a2");

  Assert.equal((await controller.send(ready("/retry", "a2"))).type, "plan");
});

add_task(async function test_invalid_config_is_reported_by_name() {
  const controller = await loadController();
  const broken = structuredClone(CONFIG);
  broken.journey[0].actions[0]["selector-ref"] = "missing";
  controller.configure(broken);

  const reply = await controller.send(ready("/"));

  Assert.equal(reply.type, "error");
  Assert.stringContains(reply.reason, 'unknown selector "missing"');
});

add_task(async function test_results_are_forwarded_to_the_host() {
  const controller = await loadController();
  controller.configure();
  const result = { type: "result", attemptId: "a1", actionId: "x", status: "success" };

  await controller.send(result);

  Assert.deepEqual(controller.reports.map(r => ({ ...r })), [result]);
});
