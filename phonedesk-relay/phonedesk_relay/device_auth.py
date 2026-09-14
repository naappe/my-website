from __future__ import annotations

import base64
import hashlib
import json
import time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def device_id_from_public_key(public_key_der: bytes) -> str:
    digest = hashlib.sha256(public_key_der).digest()[:16]
    token = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return f"pd_{token}"


def canonical_json(data: dict) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def canonical_request(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return f"{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{body_hash}".encode("utf-8")


def verify_signature(public_key_der: bytes, message: bytes, signature_b64: str) -> bool:
    try:
        signature = base64.b64decode(signature_b64, validate=True)
        public_key = serialization.load_der_public_key(public_key_der)
        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            return False
        public_key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, InvalidSignature):
        return False


class ReplayGuard:
    def __init__(self, max_age_seconds: int = 120) -> None:
        self.max_age_seconds = max_age_seconds
        self._seen: dict[tuple[str, str], int] = {}

    def accept(self, device_id: str, timestamp: int, nonce: str, now: int | None = None) -> bool:
        current = int(time.time()) if now is None else now
        if abs(current - timestamp) > self.max_age_seconds:
            return False
        key = (device_id, nonce)
        if key in self._seen:
            return False
        self._seen[key] = current
        cutoff = current - self.max_age_seconds
        self._seen = {key: seen_at for key, seen_at in self._seen.items() if seen_at >= cutoff}
        return True
