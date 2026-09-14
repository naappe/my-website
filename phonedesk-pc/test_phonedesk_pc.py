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

    def test_pairing_success_detects_adb_success(self):
        self.assertTrue(phonedesk_pc.pairing_succeeded(0, "Successfully paired to 192.168.1.10:37123"))

    def test_pairing_failure_does_not_clear_code(self):
        self.assertEqual(
            phonedesk_pc.pairing_code_after_result("123456", False),
            "123456",
        )

    def test_pairing_success_clears_code(self):
        self.assertEqual(phonedesk_pc.pairing_code_after_result("123456", True), "")

    def test_fresh_pairing_endpoint_wins_over_cached_endpoint(self):
        cached = ("192.168.100.65", 3483)
        fresh = ("192.168.100.65", 42117)
        self.assertEqual(phonedesk_pc.choose_pairing_endpoint(cached, fresh), fresh)

    def test_cached_pairing_endpoint_is_used_only_when_fresh_missing(self):
        cached = ("192.168.100.65", 3483)
        self.assertEqual(phonedesk_pc.choose_pairing_endpoint(cached, None), cached)

    def test_find_live_tcp_endpoint_prefers_requested_host(self):
        output = """List of devices attached
192.168.100.20:40111 device product:foo model:a transport_id:1
192.168.100.65:37721 device product:foo model:b transport_id:2
192.168.100.65:39999 offline transport_id:3
emulator-5554 device product:sdk model:emu transport_id:4
"""
        self.assertEqual(
            phonedesk_pc.find_live_tcp_endpoint(output, preferred_host="192.168.100.65"),
            ("192.168.100.65", 37721),
        )

    def test_find_live_tcp_endpoint_returns_only_live_network_device(self):
        output = """List of devices attached
192.168.100.65:37721 device product:foo model:b transport_id:2
emulator-5554 device product:sdk model:emu transport_id:4
"""
        self.assertEqual(phonedesk_pc.find_live_tcp_endpoint(output), ("192.168.100.65", 37721))

    def test_endpoint_is_connected_only_when_adb_state_is_device(self):
        output = """List of devices attached
192.168.100.65:37721 device product:foo model:bar transport_id:1
192.168.100.65:38888 offline transport_id:2
"""
        self.assertTrue(phonedesk_pc.endpoint_is_connected(output, ("192.168.100.65", 37721)))
        self.assertFalse(phonedesk_pc.endpoint_is_connected(output, ("192.168.100.65", 38888)))
        self.assertFalse(phonedesk_pc.endpoint_is_connected(output, ("192.168.100.65", 39999)))

    def test_endpoint_state_reports_real_adb_state(self):
        output = """List of devices attached
192.168.100.65:37721 device product:foo model:bar transport_id:1
192.168.100.65:38888 unauthorized transport_id:2
"""
        self.assertEqual(phonedesk_pc.endpoint_state(output, ("192.168.100.65", 37721)), "device")
        self.assertEqual(phonedesk_pc.endpoint_state(output, ("192.168.100.65", 38888)), "unauthorized")
        self.assertIsNone(phonedesk_pc.endpoint_state(output, ("192.168.100.65", 39999)))

    def test_build_scrcpy_args_adds_screen_off_flags_when_enabled(self):
        args = phonedesk_pc.build_scrcpy_args(
            "C:/PhoneDesk/scrcpy.exe",
            ("192.168.100.65", 37721),
            True,
        )
        self.assertEqual(args[:5], [
            "C:/PhoneDesk/scrcpy.exe",
            "--serial",
            "192.168.100.65:37721",
            "--window-title",
            "PhoneDesk",
        ])
        self.assertIn("--turn-screen-off", args)
        self.assertIn("--power-off-on-close", args)

    def test_build_scrcpy_args_omits_screen_off_flags_when_disabled(self):
        args = phonedesk_pc.build_scrcpy_args(
            "C:/PhoneDesk/scrcpy.exe",
            ("192.168.100.65", 37721),
            False,
        )
        self.assertNotIn("--turn-screen-off", args)
        self.assertNotIn("--power-off-on-close", args)

    def test_parse_adb_devices(self):
        output = """List of devices attached
192.168.1.10:43211 device product:foo model:bar transport_id:1
emulator-5554 offline transport_id:2
"""
        self.assertEqual(
            phonedesk_pc.parse_adb_devices(output),
            [("192.168.1.10:43211", "device"), ("emulator-5554", "offline")],
        )

    def test_parse_mdns_services_finds_pairing_and_connect_endpoints(self):
        output = """List of discovered mdns services
adb-1234 _adb-tls-pairing._tcp. 192.168.100.65:3483
adb-1234 _adb-tls-connect._tcp. 192.168.100.65:37721
"""
        found = phonedesk_pc.parse_mdns_services(output)
        self.assertEqual(found["pairing"], ("192.168.100.65", 3483))
        self.assertEqual(found["connect"], ("192.168.100.65", 37721))

    def test_parse_mdns_services_accepts_service_names_without_trailing_dot(self):
        output = "adb-abc _adb-tls-pairing._tcp 10.0.0.20:5555"
        found = phonedesk_pc.parse_mdns_services(output)
        self.assertEqual(found["pairing"], ("10.0.0.20", 5555))

    def test_parse_mdns_services_returns_empty_when_nothing_found(self):
        self.assertEqual(phonedesk_pc.parse_mdns_services("List of discovered mdns services"), {})

    def test_saved_config_schema_excludes_pairing_secret(self):
        keys = {"host", "device_port", "screen_off"}
        self.assertNotIn("pairing_code", keys)
        self.assertNotIn("pairing_port", keys)

    def test_remote_pairing_panel_hidden_after_trust(self):
        self.assertTrue(phonedesk_pc.remote_pairing_panel_visible(False))
        self.assertFalse(phonedesk_pc.remote_pairing_panel_visible(True))

    def test_remote_primary_status(self):
        self.assertEqual(phonedesk_pc.remote_primary_status(False), "Pair your phone")
        self.assertEqual(phonedesk_pc.remote_primary_status(True), "Ready")


if __name__ == "__main__":
    unittest.main()
