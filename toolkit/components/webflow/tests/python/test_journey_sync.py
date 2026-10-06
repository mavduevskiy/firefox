# Any copyright is dedicated to the Public Domain.
# http://creativecommons.org/publicdomain/zero/1.0/

"""The Google Takeout journey ships twice: as the default of the Fenix Nimbus
feature and as the desktop default in toolkit. Both must stay the same."""

import json
import unittest
from pathlib import Path

import mozunit
import yaml

TOPSRCDIR = Path(__file__).resolve().parents[5]
TOOLKIT_JOURNEY = (
    TOPSRCDIR / "toolkit/components/webflow/journeys/google-takeout-bookmarks.json"
)
FENIX_FEATURE = TOPSRCDIR / "mobile/android/fenix/app/google-takeout-import.fml.yaml"

# Variables that control the Fenix entry point rather than the journey.
FENIX_ONLY_VARIABLES = {"enabled"}


class TestJourneySync(unittest.TestCase):
    def test_fenix_defaults_match_toolkit_journey(self):
        variables = yaml.safe_load(FENIX_FEATURE.read_text())["features"][
            "google-takeout-import"
        ]["variables"]
        fenix_defaults = {
            name: variable["default"]
            for name, variable in variables.items()
            if name not in FENIX_ONLY_VARIABLES
        }
        toolkit_journey = json.loads(TOOLKIT_JOURNEY.read_text())

        self.assertEqual(
            fenix_defaults,
            toolkit_journey,
            f"{FENIX_FEATURE.name} and {TOOLKIT_JOURNEY.name} differ; update both.",
        )


if __name__ == "__main__":
    mozunit.main()
