from phonedesk_relay.presence import PresenceRegistry


def test_connect_marks_device_online():
    registry = PresenceRegistry()
    presence = registry.connect("phone-1")
    assert presence.device_id == "phone-1"
    assert presence.state == "ONLINE"
    assert registry.get("phone-1") is presence


def test_disconnect_removes_device():
    registry = PresenceRegistry()
    registry.connect("phone-1")
    registry.disconnect("phone-1")
    assert registry.get("phone-1") is None


def test_touch_updates_existing_device_only():
    registry = PresenceRegistry()
    assert registry.touch("missing") is None
    before = registry.connect("phone-1")
    old_last_seen = before.last_seen
    after = registry.touch("phone-1")
    assert after is before
    assert after.last_seen >= old_last_seen
