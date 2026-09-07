from hermes_gateway.auth import DeviceStore
from tests.conftest import auth


def test_tokens_are_hashed_and_shown_once(tmp_path):
    store = DeviceStore(tmp_path / "devices.json")
    device, token = store.add("watch-phone")
    assert token.startswith("hg1_")
    on_disk = (tmp_path / "devices.json").read_text()
    assert token not in on_disk
    assert store.authenticate(token).id == device.id
    assert store.authenticate("hg1_nope") is None
    assert store.authenticate(None) is None


def test_revoked_token_stops_working(tmp_path):
    store = DeviceStore(tmp_path / "devices.json")
    device, token = store.add("old-phone")
    assert store.revoke(device.id)
    assert store.authenticate(token) is None
    reloaded = DeviceStore(tmp_path / "devices.json")
    assert reloaded.authenticate(token) is None
    assert reloaded.list()[0].revoked


async def test_endpoints_require_a_device(client, token):
    assert (await client.get("/v1/whoami")).status_code == 401
    r = await client.post("/v1/turns", json={"input": {"type": "text", "text": "hi"}}, headers=auth("hg1_bad"))
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "unauthorized"
    r = await client.get("/v1/whoami", headers=auth(token))
    assert r.status_code == 200
    assert r.json()["device"]["name"] == "test-phone"


async def test_health_is_public_and_versioned(client):
    r = await client.get("/v1/health")
    assert r.status_code == 200
    assert r.headers["X-Gateway-Api"] == "v1"
    body = r.json()
    assert body["ok"] and body["brain"] == {"kind": "fake", "reachable": True}
