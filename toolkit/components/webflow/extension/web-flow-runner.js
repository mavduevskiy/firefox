/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * The action runner, shared by both platforms: the Android extension loads
 * it as a content script, and desktop's WebFlowChild actor loads it as a
 * subscript. It has no knowledge of the site it runs on and no platform
 * code; every page API is reached through the window it is given.
 */
this.WebFlowRunner = (() => {
  function waitForCondition(win, check, timeoutMs, description) {
    return new Promise((resolve, reject) => {
      const initial = check();
      if (initial) {
        resolve(initial);
        return;
      }
      const observer = new win.MutationObserver(() => {
        const result = check();
        if (result) {
          observer.disconnect();
          win.clearTimeout(timer);
          resolve(result);
        }
      });
      const timer = win.setTimeout(() => {
        observer.disconnect();
        reject(new Error(`timeout waiting for ${description}`));
      }, timeoutMs);
      observer.observe(win.document.body || win.document, {
        childList: true,
        subtree: true,
        attributes: true,
      });
    });
  }

  const waitForElement = (win, selector, timeoutMs) =>
    waitForCondition(win, () => win.document.querySelector(selector), timeoutMs, selector);

  const waitForElementGone = (win, selector, timeoutMs) =>
    waitForCondition(
      win,
      () => !win.document.querySelector(selector),
      timeoutMs,
      `${selector} to disappear`
    );

  const waitForUrl = (win, pattern, timeoutMs) =>
    waitForCondition(
      win,
      () => new RegExp(pattern).test(win.location.href),
      timeoutMs,
      `url ${pattern}`
    );

  function checkExpectation(win, expectation) {
    switch (expectation.type) {
      case "element":
        return waitForElement(win, expectation.selector, expectation.timeoutMs);
      case "gone":
        return waitForElementGone(win, expectation.selector, expectation.timeoutMs);
      case "url":
        return waitForUrl(win, expectation.pattern, expectation.timeoutMs);
      default:
        throw new Error(`unknown expectation type: ${expectation.type}`);
    }
  }

  function interpolate(template, vars) {
    return template.replace(/\{(\w+)\}/g, (_, key) => vars[key] ?? "");
  }

  // Returns the action's detail, or NAVIGATED when the page is being left.
  const NAVIGATED = Symbol("navigated");

  async function runAction(win, action, vars) {
    switch (action.type) {
      case "click": {
        const element = await waitForElement(win, action.selector, action.timeoutMs);
        element.click();
        await new Promise(resolve => win.setTimeout(resolve, action.settleMs));
        return undefined;
      }
      case "scroll": {
        const element = await waitForElement(win, action.selector, action.timeoutMs);
        element.scrollIntoView({ behavior: "smooth", block: "center" });
        return undefined;
      }
      case "expect":
        await Promise.all(action.expectations.map(e => checkExpectation(win, e)));
        return undefined;
      case "extract": {
        const element = await waitForElement(win, action.selector, action.timeoutMs);
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

  return {
    /**
     * Runs the actions of a `plan` reply from the controller in `win`, in
     * order, and reports every result with `report(actionId, status, detail)`.
     * Stops at the first failing action or when an action leaves the page.
     *
     * @returns {Promise<boolean>} Whether every action succeeded.
     */
    async runPlan(win, plan, report) {
      const vars = { ...plan.vars };
      report(`page:${plan.pageId}`, "success");
      for (const action of plan.actions) {
        try {
          const detail = await runAction(win, action, vars);
          if (detail === NAVIGATED) {
            const url = interpolate(action.url, vars);
            report(action.id, "success", url);
            win.location.href = url;
            return true;
          }
          report(action.id, "success", detail);
        } catch (e) {
          report(action.id, "error", String(e?.message || e));
          return false;
        }
      }
      return true;
    },
  };
})();
