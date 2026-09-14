# PhoneDesk Remote Relay — Milestone 1

This directory contains the first Remote Mode relay milestone: a versioned protocol, in-memory presence registry, health endpoint, and WebSocket router for opaque PhoneDesk envelopes.

## What this milestone does

- Protocol version `1` validation.
- `GET /health` health check.
- `WS /v1/ws` device connection endpoint.
- `HELLO` / `HELLO_ACK` connection setup.
- `PING` / `PONG` heartbeat support.
- In-memory online presence.
- Opaque `ROUTE` forwarding between connected device IDs.
- Controlled errors for offline targets and invalid protocol versions.
- Automatic FastAPI docs/OpenAPI endpoints are disabled.

## What this milestone does not do

It does not yet implement device identity authentication, one-time pairing, end-to-end encryption, PostgreSQL persistence, ADB tunneling, scrcpy transport, or Android native fallback. Those are later Remote Mode milestones.

The current `device_id` in a HELLO message is only a protocol identifier. It is **not yet cryptographically authenticated**. Do not deploy Milestone 1 as an internet-facing production relay for real remote-control sessions.

## Local development

Use Python 3.12.

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q
python run.py
```

The local development server listens only on:

```text
http://127.0.0.1:8765
ws://127.0.0.1:8765/v1/ws
```

This plain HTTP/WS listener is development-only.

## Production transport requirement

A future deployable relay must sit behind TLS and expose only HTTPS/WSS on public port `443`.

Do not publicly expose raw ADB, scrcpy, PostgreSQL, a Python debug server, or this local development listener.

## Privacy rule

The relay must never log or persist Android lock credentials, Wireless Debugging pairing codes, one-time pairing secrets, private encryption keys, decrypted ADB traffic, screen contents, or input-event contents.

`ROUTE` payloads are treated as opaque application data and are forwarded without server-side payload interpretation.
