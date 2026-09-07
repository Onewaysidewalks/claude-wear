"""`hermes-gateway` command line: serve, mint device tokens, probe Hermes, talk to a gateway."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from . import API_VERSION, GATEWAY_VERSION
from .auth import DeviceStore
from .config import Settings


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="hermes-gateway", description="The one door into Hermes.")
    p.add_argument("--config", type=Path, default=None, help="config JSON (default ~/.hermes-gateway/config.json)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve", help="run the gateway")
    s.add_argument("--host")
    s.add_argument("--port", type=int)
    s.add_argument("--brain", choices=["hermes", "fake"])
    s.add_argument("--allow-any-bind", action="store_true", help="permit binding outside loopback/tailnet ranges")

    t = sub.add_parser("token", help="manage device tokens")
    tsub = t.add_subparsers(dest="tcmd", required=True)
    ta = tsub.add_parser("add")
    ta.add_argument("name")
    tsub.add_parser("list")
    tr = tsub.add_parser("revoke")
    tr.add_argument("device_id")

    pr = sub.add_parser("probe", help="print what the installed Hermes really sends")
    pr.add_argument("--prompt", default="Reply with exactly the word: pong")

    say = sub.add_parser("say", help="send a text turn to a running gateway and print its events")
    say.add_argument("text")
    say.add_argument("--url", default="http://127.0.0.1:8731")
    say.add_argument("--token", required=True)
    say.add_argument("--session", default="cli")
    say.add_argument("--approve", choices=["allow", "deny"], default=None, help="auto-answer approvals")

    sub.add_parser("version")

    args = p.parse_args(argv)
    settings = Settings.load(args.config)
    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
    )

    if args.cmd == "version":
        print(f"hermes-gateway {GATEWAY_VERSION} (api {API_VERSION})")
        return 0
    if args.cmd == "token":
        store = DeviceStore(settings.tokens_path)
        if args.tcmd == "add":
            device, token = store.add(args.name)
            print(f"device {device.id} ({device.name})")
            print("token (shown once, put it in the phone app):")
            print(token)
        elif args.tcmd == "list":
            for d in store.list():
                print(f"{d.id}\t{d.name}\t{d.created_at}\t{'revoked' if d.revoked else 'active'}")
        elif args.tcmd == "revoke":
            print("revoked" if store.revoke(args.device_id) else "no such device")
        return 0
    if args.cmd == "probe":
        from .brains.hermes import probe

        return asyncio.run(probe(settings, args.prompt))
    if args.cmd == "say":
        return asyncio.run(_say(args.url, args.token, args.session, args.text, args.approve))
    if args.cmd == "serve":
        import uvicorn

        from .app import bind_is_private, create_app

        if args.host:
            settings.host = args.host
        if args.port:
            settings.port = args.port
        if args.brain:
            settings.brain = args.brain
        if not bind_is_private(settings.host) and not args.allow_any_bind:
            print(
                f"refusing to bind {settings.host}: use the Mac's Tailscale address (100.x.y.z) or loopback,"
                " or pass --allow-any-bind if you really mean it",
                file=sys.stderr,
            )
            return 2
        if settings.brain == "hermes" and not settings.hermes_api_key:
            print(
                "HERMES_API_KEY is not set (Hermes' API_SERVER_KEY); use --brain fake to run without Hermes",
                file=sys.stderr,
            )
            return 2
        uvicorn.run(create_app(settings), host=settings.host, port=settings.port, log_level=settings.log_level)
        return 0
    return 1


async def _say(url: str, token: str, session: str, text: str, approve: str | None) -> int:
    import httpx
    from httpx_sse import aconnect_sse

    body = {"session": {"key": session}, "input": {"type": "text", "text": text}}
    headers = {"Authorization": f"Bearer {token}"}
    async with httpx.AsyncClient(timeout=httpx.Timeout(30, read=None)) as client:
        async with aconnect_sse(client, "POST", f"{url}/v1/turns", json=body, headers=headers) as source:
            if source.response.status_code >= 400:
                print(f"{source.response.status_code}: {(await source.response.aread()).decode()}")
                return 1
            async for sse in source.aiter_sse():
                data = json.loads(sse.data)
                print(f"{sse.event:18} {json.dumps({k: v for k, v in data.items() if k not in ('type', 'at')})}")
                if sse.event == "output.done":
                    print(f"\n{data['text']}\n")
                if sse.event == "approval.required" and approve:
                    r = await client.post(
                        f"{url}/v1/turns/{data['turn_id']}/approvals/{data['approval_id']}",
                        json={"decision": approve},
                        headers=headers,
                    )
                    print(f"  -> approval {approve}: {r.status_code}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
