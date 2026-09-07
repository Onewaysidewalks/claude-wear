"""Every committed fixture must round-trip through the models, and the generator must be current."""

import json
import subprocess
import sys
from pathlib import Path

import pytest
from pydantic import TypeAdapter

from hermes_gateway import events as ev

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = sorted((ROOT / "fixtures" / "v1").glob("*.json"))


@pytest.mark.parametrize("path", FIXTURES, ids=[p.stem for p in FIXTURES])
def test_fixture_round_trips(path: Path):
    data = json.loads(path.read_text())
    if path.stem.startswith("request-approval"):
        model = ev.ApprovalRequest.model_validate(data)
    elif path.stem.startswith("request-"):
        model = ev.TurnRequest.model_validate(data)
    else:
        model = TypeAdapter(ev.Event).validate_python(data)
    assert model.model_dump(mode="json") == data


def test_every_event_type_has_a_fixture():
    covered = {json.loads(p.read_text()).get("type") for p in FIXTURES if not p.stem.startswith("request-")}
    expected = {t.model_fields["type"].default for t in ev.EVENT_TYPES}
    assert expected <= covered


def test_generated_files_are_current():
    r = subprocess.run([sys.executable, "scripts/gen_fixtures.py", "--check"], cwd=ROOT, capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
