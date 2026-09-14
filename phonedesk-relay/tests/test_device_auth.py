import base64

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from phonedesk_relay.device_auth import (
    ReplayGuard,
    canonical_json,
    canonical_request,
    device_id_from_public_key,
    verify_signature,
)


def test_device_id_vector_is_cross_client_stable():
    assert device_id_from_public_key(b"PhoneDesk") == "pd_vYVSrqAZQSyIHKdKfecnCA"


def test_canonical_json_is_sorted_and_minified():
    assert canonical_json({"z": 2, "a": 1}) == b'{"a":1,"z":2}'


def test_signed_request_verifies():
    key = ec.generate_private_key(ec.SECP256R1())
    der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    body = canonical_json({"approved": True})
    message = canonical_request("POST", "/v1/pairings/p1/confirm", "1700000000", "n1", body)
    signature = key.sign(message, ec.ECDSA(hashes.SHA256()))
    assert verify_signature(der, message, base64.b64encode(signature).decode("ascii"))


def test_replay_guard_rejects_reused_nonce_and_old_timestamp():
    guard = ReplayGuard(max_age_seconds=120)
    assert guard.accept("pd_test", 1000, "n1", now=1050)
    assert not guard.accept("pd_test", 1000, "n1", now=1051)
    assert not guard.accept("pd_test", 800, "n2", now=1051)
