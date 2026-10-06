/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const RUNNER_URL = "resource://webflow/extension/web-flow-runner.js";

/**
 * Runs the web flow journey in a page of WebFlow.sys.mjs's hidden browser.
 * The actor only exists in that browser (it is registered for its message
 * manager group), so every page it sees belongs to the journey. On each page
 * it asks the parent for the page's actions and runs them with the runner
 * Android uses too.
 */
export class WebFlowChild extends JSWindowActorChild {
  async handleEvent(event) {
    if (event.type !== "pageshow" || this.#started) {
      return;
    }
    this.#started = true;

    const win = this.contentWindow;
    const plan = await this.sendQuery("WebFlow:Ready");
    const report = (actionId, status, detail) =>
      this.sendAsyncMessage("WebFlow:Result", {
        actionId,
        status,
        ...(detail === undefined ? {} : { detail }),
      });

    switch (plan?.type) {
      case "plan":
        if (!(await WebFlowChild.#runner().runPlan(win, plan, report))) {
          this.sendAsyncMessage("WebFlow:Failed", { onceKey: plan.onceKey });
        }
        break;
      case "idle":
        report(plan.reason, "success", plan.detail);
        break;
      default:
        report("plan-request", "error", plan?.reason);
    }
  }

  #started = false;

  static #loadedRunner = null;

  static #runner() {
    if (!this.#loadedRunner) {
      const scope = {};
      Services.scriptloader.loadSubScript(RUNNER_URL, scope);
      this.#loadedRunner = scope.WebFlowRunner;
    }
    return this.#loadedRunner;
  }
}
