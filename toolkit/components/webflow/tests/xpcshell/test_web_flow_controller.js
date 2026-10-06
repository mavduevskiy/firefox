/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

// Tests the shared journey controller, and the Android background glue
// around it with a fake `browser` object standing in for the WebExtension
// APIs.

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

const scope = {};
Services.scriptloader.loadSubScript(
  "resource://webflow/extension/web-flow-controller.js",
  scope
);
const { WebFlowController } = scope;

const url = path => `https://example.com${path}`;

add_task(function test_plan_resolves_selectors_and_prefix() {
  const plan = new WebFlowController(CONFIG).plan(url("/u/1/"));

  Assert.equal(plan.type, "plan");
  Assert.equal(plan.pageId, "start");
  Assert.equal(plan.vars.prefix, "/u/1");
  Assert.equal(plan.actions[0].selector, "#start");
  Assert.equal(plan.actions[0].timeoutMs, 1000, "flow timeout is the default");
  Assert.equal(plan.actions[1].url, "{prefix}/wait");
});

add_task(function test_action_overrides_and_nulls() {
  const [action] = new WebFlowController(CONFIG).plan(url("/wait")).actions;

  Assert.equal(action.timeoutMs, 5000, "action timeout overrides the default");
  Assert.equal(action.expectations[0].timeoutMs, 5000, "expectations inherit it");
  Assert.equal(action.expectations[0].selector, ".pending");
  Assert.ok(!("url" in action), "null values from Nimbus are dropped");
});

add_task(function test_other_hosts_and_paths_are_idle() {
  const controller = new WebFlowController(CONFIG);

  Assert.equal(controller.plan("https://other.example/").reason, "unhandled-host");
  Assert.equal(controller.plan(url("/elsewhere")).reason, "unhandled-path");
});

add_task(function test_once_pages_and_auth() {
  const controller = new WebFlowController(CONFIG);

  Assert.equal(controller.plan(url("/retry")).type, "plan");
  Assert.equal(controller.plan(url("/retry")).reason, "already-run");
  Assert.equal(controller.plan(url("/no-retry")).type, "plan");

  Assert.ok(controller.isAuthHost("auth.example.com"));
  Assert.equal(controller.plan("https://auth.example.com/signin").reason, "auth-required");

  Assert.equal(
    controller.plan(url("/retry")).type,
    "plan",
    "a page interrupted by auth runs again"
  );
  Assert.equal(
    controller.plan(url("/no-retry")).reason,
    "already-run",
    "unless it opted out of retrying"
  );
});

add_task(function test_release_lets_a_failed_once_page_run_again() {
  const controller = new WebFlowController(CONFIG);

  controller.release(controller.plan(url("/retry")).onceKey);

  Assert.equal(controller.plan(url("/retry")).type, "plan");
});

add_task(function test_invalid_config_is_reported_by_name() {
  const broken = structuredClone(CONFIG);
  broken.journey[0].actions[0]["selector-ref"] = "missing";

  const controller = new WebFlowController(broken);

  Assert.stringContains(controller.error, 'unknown selector "missing"');
  Assert.equal(controller.plan(url("/")).type, "error");
});

/** Loads the Android background glue with a fake `browser` object. */
function loadBackground() {
  let onMessage;
  const sandbox = Cu.Sandbox(Services.scriptSecurityManager.getSystemPrincipal(), {
    wantGlobalProperties: ["URL"],
    freshCompartment: true,
  });
  sandbox.browser = {
    runtime: { onMessage: { addListener: l => (onMessage = l) } },
  };
  for (const file of ["web-flow-controller.js", "web-flow-background.js"]) {
    Services.scriptloader.loadSubScript(`resource://webflow/extension/${file}`, sandbox);
  }
  return (message, tabId = 7) => onMessage(message, { tab: { id: tabId } });
}

const ready = (path, attemptId = "a1") => ({
  type: "ready",
  attemptId,
  config: CONFIG,
  url: url(path),
});

add_task(async function test_background_creates_the_controller_from_ready() {
  const send = loadBackground();

  Assert.equal((await send(ready("/"))).pageId, "start");
});

add_task(async function test_background_keeps_the_controller_of_an_attempt() {
  const send = loadBackground();
  await send(ready("/retry"));

  Assert.equal(
    (await send(ready("/retry"))).reason,
    "already-run",
    "the second page of the attempt sees the first page's state"
  );
});

add_task(async function test_background_drives_only_the_first_tab() {
  const send = loadBackground();
  await send(ready("/"));

  Assert.equal((await send(ready("/"), 8)).reason, "foreign-tab");
});

add_task(async function test_background_starts_over_on_a_new_attempt() {
  const send = loadBackground();
  await send(ready("/retry"));

  Assert.equal((await send(ready("/retry", "a2"), 9)).type, "plan");
});

add_task(async function test_background_failed_releases_the_once_page() {
  const send = loadBackground();
  const plan = await send(ready("/retry"));
  send({ type: "failed", attemptId: "a1", onceKey: plan.onceKey });

  Assert.equal((await send(ready("/retry"))).type, "plan");
});
