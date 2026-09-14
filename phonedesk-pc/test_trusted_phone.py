import tempfile
import unittest
from pathlib import Path

from trusted_phone import TrustedPhone, TrustedPhoneStore


class TrustedPhoneStoreTests(unittest.TestCase):
    def test_roundtrip_contains_no_pairing_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trusted-phone.json"
            store = TrustedPhoneStore(path)
            phone = TrustedPhone("pd_phone", "My Phone", "AA:BB:CC:DD", "BASE64PUBLIC")
            store.save(phone)
            self.assertEqual(TrustedPhoneStore(path).load(), phone)
            raw = path.read_text(encoding="utf-8")
            self.assertNotIn("pairing", raw.lower())
            self.assertNotIn("123456", raw)


if __name__ == "__main__":
    unittest.main()
