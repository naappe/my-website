import pytest

from phonedesk_relay.pairing import PairingManager, PairingState


def test_pairing_claim_then_phone_confirm():
    manager = PairingManager(hmac_key=b"test-key")
    created = manager.create("phone", now=1000)
    assert len(created.manual_code) == 6
    assert created.manual_code.isdigit()
    claimed = manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
    assert claimed.state is PairingState.CLAIMED
    confirmed = manager.confirm(created.pairing_id, "phone", True, now=1020)
    assert confirmed.state is PairingState.CONFIRMED


def test_pairing_expires_after_300_seconds():
    manager = PairingManager(hmac_key=b"test-key")
    created = manager.create("phone", now=1000)
    assert manager.get(created.pairing_id, now=1301).state is PairingState.EXPIRED


def test_pairing_locks_after_five_bad_codes():
    manager = PairingManager(hmac_key=b"test-key", max_attempts=5)
    created = manager.create("phone", now=1000)
    wrong = "000000" if created.manual_code != "000000" else "999999"
    for second in range(5):
        with pytest.raises(ValueError):
            manager.claim(created.pairing_id, "pc", wrong, now=1001 + second)
    with pytest.raises(ValueError, match="attempt"):
        manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
