/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/* global WebFlowRunner */

/**
 * Android glue around the shared action runner (web-flow-runner.js, loaded
 * before this script). Every page opens a native port to the app, but the app
 * only answers in its hidden tab, with "start" carrying the attempt id and the
 * journey config; pages the user opens in other tabs are left alone. Once
 * started, the runner asks the background controller for the actions of the
 * current page and reports each result to the app over the same port.
 */
(() => {
  const PORT_NAME = "mozacWebFlowRunner";
  const port = browser.runtime.connectNative(PORT_NAME);
  let started = false;

  async function start({ attemptId, config }) {
    if (started) {
      return;
    }
    started = true;
    const report = (actionId, status, detail) =>
      port.postMessage({
        type: "result",
        attemptId,
        actionId,
        status,
        ...(detail === undefined ? {} : { detail }),
      });

    let plan;
    try {
      plan = await browser.runtime.sendMessage({
        type: "ready",
        attemptId,
        config,
        url: location.href,
      });
    } catch (e) {
      report("plan-request", "error", String(e?.message || e));
      return;
    }
    switch (plan?.type) {
      case "plan":
        if (!(await WebFlowRunner.runPlan(window, plan, report))) {
          browser.runtime.sendMessage({ type: "failed", attemptId, onceKey: plan.onceKey });
        }
        break;
      case "idle":
        report(plan.reason, "success", plan.detail);
        break;
      default:
        report("plan-request", "error", plan?.reason);
    }
  }

  port.onMessage.addListener(message => {
    if (message?.type === "start") {
      start(message);
    }
  });
})();
