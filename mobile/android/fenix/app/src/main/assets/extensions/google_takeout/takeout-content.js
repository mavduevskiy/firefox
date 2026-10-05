/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * Generic action runner. Has no knowledge of the site it runs on: once the
 * app sends "start" over the native port, it asks the background controller
 * for the actions of the current page, executes them in order, and reports
 * each result back to the app.
 */
(() => {
  const PORT_NAME = "mozacTakeoutImporter";

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  function waitForCondition(check, timeoutMs, description) {
    return new Promise((resolve, reject) => {
      const initial = check();
      if (initial) {
        resolve(initial);
        return;
      }
      const observer = new MutationObserver(() => {
        const result = check();
        if (result) {
          observer.disconnect();
          clearTimeout(timer);
          resolve(result);
        }
      });
      const timer = setTimeout(() => {
        observer.disconnect();
        reject(new Error(`timeout waiting for ${description}`));
      }, timeoutMs);
      observer.observe(document.body || document, {
        childList: true,
        subtree: true,
        attributes: true,
      });
    });
  }

  const waitForElement = (selector, timeoutMs) =>
    waitForCondition(() => document.querySelector(selector), timeoutMs, selector);

  const waitForElementGone = (selector, timeoutMs) =>
    waitForCondition(
      () => !document.querySelector(selector),
      timeoutMs,
      `${selector} to disappear`
    );

  const waitForUrl = (pattern, timeoutMs) =>
    waitForCondition(
      () => new RegExp(pattern).test(location.href),
      timeoutMs,
      `url ${pattern}`
    );

  function checkExpectation(expectation) {
    switch (expectation.type) {
      case "element":
        return waitForElement(expectation.selector, expectation.timeoutMs);
      case "gone":
        return waitForElementGone(expectation.selector, expectation.timeoutMs);
      case "url":
        return waitForUrl(expectation.pattern, expectation.timeoutMs);
      default:
        throw new Error(`unknown expectation type: ${expectation.type}`);
    }
  }

  function interpolate(template, vars) {
    return template.replace(/\{(\w+)\}/g, (_, key) => vars[key] ?? "");
  }

  // Returns the action's detail, or NAVIGATED when the page is being left.
  const NAVIGATED = Symbol("navigated");

  async function runAction(action, vars) {
    switch (action.type) {
      case "click": {
        const element = await waitForElement(action.selector, action.timeoutMs);
        element.click();
        await sleep(action.settleMs);
        return undefined;
      }
      case "scroll": {
        const element = await waitForElement(action.selector, action.timeoutMs);
        element.scrollIntoView({ behavior: "smooth", block: "center" });
        return undefined;
      }
      case "expect":
        await Promise.all(action.expectations.map(checkExpectation));
        return undefined;
      case "extract": {
        const element = await waitForElement(action.selector, action.timeoutMs);
        const value = element.getAttribute(action.attribute);
        if (!value) {
          throw new Error(`attribute ${action.attribute} is empty`);
        }
        vars[action.variable] = value;
        return value;
      }
      case "navigate":
        return NAVIGATED;
      default:
        throw new Error(`unknown action type: ${action.type}`);
    }
  }

  const port = browser.runtime.connectNative(PORT_NAME);

  function report(attemptId, actionId, status, detail) {
    port.postMessage({
      type: "result",
      attemptId,
      actionId,
      status,
      ...(detail === undefined ? {} : { detail }),
    });
  }

  async function runPlan(attemptId, plan) {
    const vars = { ...plan.vars };
    report(attemptId, `page:${plan.pageId}`, "success");
    for (const action of plan.actions) {
      try {
        const detail = await runAction(action, vars);
        if (detail === NAVIGATED) {
          const url = interpolate(action.url, vars);
          report(attemptId, action.id, "success", url);
          location.href = url;
          return;
        }
        report(attemptId, action.id, "success", detail);
      } catch (e) {
        report(attemptId, action.id, "error", String(e?.message || e));
        browser.runtime.sendMessage({
          type: "failed",
          attemptId,
          onceKey: plan.onceKey,
        });
        return;
      }
    }
  }

  let started = false;

  port.onMessage.addListener(async message => {
    if (message?.type !== "start" || started) {
      return;
    }
    started = true;
    const { attemptId } = message;
    let plan;
    try {
      plan = await browser.runtime.sendMessage({
        type: "ready",
        attemptId,
        url: location.href,
      });
    } catch (e) {
      report(attemptId, "plan-request", "error", String(e?.message || e));
      return;
    }
    switch (plan?.type) {
      case "plan":
        await runPlan(attemptId, plan);
        break;
      case "idle":
        report(attemptId, plan.reason, "success", plan.detail);
        break;
      default:
        report(attemptId, "plan-request", "error", plan?.reason);
    }
  });
})();
