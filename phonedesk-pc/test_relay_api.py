import tempfile
import unittest
from pathlib import Path

import httpx

from identity import DeviceIdentityStore
from relay_api import RelayApi
from test_identity import ReversibleTestProtector


class RelayApiTests(unittest.TestCase):
    def test_claim_pairing_sends_signed_headers_and_canonical_body(self):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/v1/devices/register":
                return httpx.Response(
                    200,
                    json={
                        "device_id": "pd_pc",
                        "display_name": "Office Laptop",
                        "platform": "windows",
                        "fingerprint": "AA:BB:CC:DD",
                        "public_key_der_b64": "BASE64PUBLIC",
                    },
                )
            seen["headers"] = dict(request.headers)
            seen["body"] = request.content
            return httpx.Response(200, json={"pairing_id": "pair-1", "state": "CLAIMED"})

        with tempfile.TemporaryDirectory() as directory:
            identity = DeviceIdentityStore(Path(directory) / "id.json", ReversibleTestProtector())
            transport = httpx.MockTransport(handler)
            api = RelayApi("https://relay.test", identity, "Office Laptop", transport=transport)
            registration = api.register()
            self.assertEqual(registration.device_id, "pd_pc")
            state = api.claim_pairing("pair-1", "123456")

        self.assertEqual(state, "CLAIMED")
        self.assertEqual(seen["body"], b'{"manual_code":"123456"}')
        self.assertIn("x-phonedesk-signature", seen["headers"])
        self.assertIn("x-phonedesk-device", seen["headers"])
        self.assertIn("x-phonedesk-nonce", seen["headers"])
        self.assertIn("x-phonedesk-timestamp", seen["headers"])


if __name__ == "__main__":
    unittest.main()
