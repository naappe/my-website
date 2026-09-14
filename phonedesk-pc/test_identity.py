import os
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from identity import DeviceIdentityStore, WindowsDpapiProtector, fingerprint_public_key


class ReversibleTestProtector:
    def protect(self, data: bytes) -> bytes:
        return bytes(value ^ 0xA5 for value in data)

    def unprotect(self, data: bytes) -> bytes:
        return bytes(value ^ 0xA5 for value in data)


class IdentityTests(unittest.TestCase):
    def test_fingerprint_format_is_stable(self):
        self.assertEqual("BD:85:52:AE", fingerprint_public_key(b"PhoneDesk"))

    def test_identity_survives_reload_without_plaintext_private_key(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "identity.json"
            first_store = DeviceIdentityStore(path, ReversibleTestProtector())
            first = first_store.load_or_create()
            raw_file = path.read_bytes()

            second_store = DeviceIdentityStore(path, ReversibleTestProtector())
            second = second_store.load_or_create()

            self.assertEqual(first.public_key_der, second.public_key_der)
            self.assertEqual(first.fingerprint, second.fingerprint)
            self.assertNotIn(b"PRIVATE KEY", raw_file)

    def test_signature_verifies_with_public_key(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DeviceIdentityStore(
                Path(directory) / "identity.json",
                ReversibleTestProtector(),
            )
            identity = store.load_or_create()
            message = b"PhoneDesk identity proof"
            signature = store.sign(message)
            public_key = serialization.load_der_public_key(identity.public_key_der)
            public_key.verify(signature, message, ec.ECDSA(hashes.SHA256()))

    @unittest.skipUnless(os.name == "nt", "Windows DPAPI test")
    def test_windows_dpapi_roundtrip(self):
        protector = WindowsDpapiProtector()
        plaintext = os.urandom(48)
        protected = protector.protect(plaintext)
        self.assertNotEqual(plaintext, protected)
        self.assertEqual(plaintext, protector.unprotect(protected))


if __name__ == "__main__":
    unittest.main()
