import unittest

import phonedesk_pc


class PhoneDeskCoreTests(unittest.TestCase):
    def test_make_endpoint(self):
        self.assertEqual(phonedesk_pc.make_endpoint("192.168.1.10", "43211"), "192.168.1.10:43211")

    def test_make_endpoint_rejects_bad_port(self):
        with self.assertRaises(ValueError):
            phonedesk_pc.make_endpoint("192.168.1.10", "99999")

    def test_pairing_code_requires_six_digits(self):
        self.assertEqual(phonedesk_pc.validate_pairing_code("123456"), "123456")
        for bad in ("12345", "1234567", "12A456", ""):
            with self.assertRaises(ValueError):
                phonedesk_pc.validate_pairing_code(bad)

    def test_parse_adb_devices(self):
        output = """List of devices attached
192.168.1.10:43211 device product:foo model:bar transport_id:1
emulator-5554 offline transport_id:2
"""
        self.assertEqual(
            phonedesk_pc.parse_adb_devices(output),
            [("192.168.1.10:43211", "device"), ("emulator-5554", "offline")],
        )

    def test_saved_config_schema_excludes_pairing_secret(self):
        # Security invariant: persistent config must never need pairing_port or pairing_code.
        keys = {"host", "device_port", "screen_off"}
        self.assertNotIn("pairing_code", keys)
        self.assertNotIn("pairing_port", keys)


if __name__ == "__main__":
    unittest.main()
