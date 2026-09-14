import base64
import time
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from phonedesk_relay.app import create_app
from phonedesk_relay.device_auth import canonical_json, canonical_request


class Signer:
    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public_der = self.key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self.device_id = None

    def register(self, client: TestClient, name: str, platform: str):
        response = client.post("/v1/devices/register", json={
            "display_name": name,
            "platform": platform,
            "public_key_der_b64": base64.b64encode(self.public_der).decode("ascii"),
        })
        assert response.status_code == 200
        self.device_id = response.json()["device_id"]
        return response.json()

    def headers(self, method: str, path: str, body: bytes):
        timestamp = str(int(time.time()))
        nonce = uuid.uuid4().hex
        message = canonical_request(method, path, timestamp, nonce, body)
        signature = self.key.sign(message, ec.ECDSA(hashes.SHA256()))
        return {
            "X-PhoneDesk-Device": self.device_id,
            "X-PhoneDesk-Timestamp": timestamp,
            "X-PhoneDesk-Nonce": nonce,
            "X-PhoneDesk-Signature": base64.b64encode(signature).decode("ascii"),
            "Content-Type": "application/json",
        }

    def post(self, client: TestClient, path: str, payload: dict):
        body = canonical_json(payload)
        return client.post(path, content=body, headers=self.headers("POST", path, body))

    def get(self, client: TestClient, path: str):
        body = b""
        return client.get(path, headers=self.headers("GET", path, body))


def test_manual_pairing_creates_trust_only_after_phone_confirmation():
    with TestClient(create_app(pairing_hmac_key=b"test-pairing-key")) as client:
        phone = Signer()
        pc = Signer()
        phone.register(client, "My Phone", "android")
        pc.register(client, "Office Laptop", "windows")

        created = phone.post(client, "/v1/pairings", {})
        assert created.status_code == 200
        pairing_id = created.json()["pairing_id"]
        code = created.json()["manual_code"]

        claim = pc.post(client, f"/v1/pairings/{pairing_id}/claim", {"manual_code": code})
        assert claim.status_code == 200
        assert claim.json()["state"] == "CLAIMED"

        status = phone.get(client, f"/v1/pairings/{pairing_id}")
        assert status.status_code == 200
        assert status.json()["computer"]["display_name"] == "Office Laptop"

        before = phone.get(client, "/v1/trusted-devices")
        assert before.json()["devices"] == []

        confirm = phone.post(client, f"/v1/pairings/{pairing_id}/confirm", {"approved": True})
        assert confirm.status_code == 200
        assert confirm.json()["state"] == "CONFIRMED"

        trusted = phone.get(client, "/v1/trusted-devices")
        assert trusted.json()["devices"][0]["display_name"] == "Office Laptop"
