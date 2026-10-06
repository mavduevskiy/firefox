/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

// The web-flow-journeys dump is the default journey config on desktop, and
// the Android build copies it as its fallback asset: every record has to
// compile.

const DUMP_URL = "resource://app/defaults/settings/main/web-flow-journeys.json";

const scope = {};
Services.scriptloader.loadSubScript(
  "resource://webflow/extension/web-flow-controller.js",
  scope
);

add_task(async function test_dump_records_compile() {
  const { data } = await (await fetch(DUMP_URL)).json();
  Assert.deepEqual(
    data.map(record => record.id).sort(),
    ["google-passwords-import", "google-takeout-bookmarks"],
    "the dump holds the expected journeys"
  );
  for (const record of data) {
    const controller = new scope.WebFlowController(record);
    Assert.equal(controller.error, null, `${record.id} compiles`);
    Assert.equal(typeof record["launch-url"], "string", `${record.id} has a launch URL`);
  }
});
