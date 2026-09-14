from phonedesk_relay.devices import DeviceRegistry


def test_trust_requires_two_registered_distinct_devices():
    registry = DeviceRegistry()
    phone = registry.register("My Phone", "android", b"phone-key")
    pc = registry.register("Office Laptop", "windows", b"pc-key")
    registry.trust(phone.device_id, pc.device_id)
    assert registry.is_trusted(phone.device_id, pc.device_id)
    assert registry.is_trusted(pc.device_id, phone.device_id)
