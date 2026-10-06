/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/* global WebFlowRunner */

/**
 * Android glue around the shared action runner (web-flow-runner.js, loaded
 * before this script). The app sends "start" over a native port it only
 * listens to for its hidden tab, so pages the user opens in other tabs are
 * left alone. Once started, the runner asks the background controller for the
 * actions of the current page and reports each result back through it.
 */
(() => {
  const PORT_NAME = "mozacWebFlowRunner";

  function reporter(attemptId) {
    return (actionId, status, detail) =>
      browser.runtime.sendMessage({
        type: "result",
        attemptId,
        actionId,
        status,
        ...(detail === undefined ? {} : { detail }),
      });
  }

  let started = false;

  async function start(attemptId) {
    if (started) {
      return;
    }
    started = true;
    const report = reporter(attemptId);
    let plan;
    try {
      plan = await browser.runtime.sendMessage({
        type: "ready",
        attemptId,
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

  browser.runtime.connectNative(PORT_NAME).onMessage.addListener(message => {
    if (message?.type === "start") {
      start(message.attemptId);
    }
  });
})();
