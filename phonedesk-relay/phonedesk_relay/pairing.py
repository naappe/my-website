from dataclasses import dataclass
from enum import StrEnum
import hashlib
import hmac
import secrets


class PairingState(StrEnum):
    CREATED = "CREATED"
    CLAIMED = "CLAIMED"
    CONFIRMED = "CONFIRMED"
    DENIED = "DENIED"
    EXPIRED = "EXPIRED"


@dataclass(frozen=True)
class CreatedPairing:
    pairing_id: str
    manual_code: str
    expires_at: int


@dataclass(frozen=True)
class PairingView:
    pairing_id: str
    phone_id: str
    computer_id: str | None
    state: PairingState
    expires_at: int


@dataclass
class _PairingRecord:
    pairing_id: str
    phone_id: str
    code_digest: bytes
    expires_at: int
    computer_id: str | None = None
    state: PairingState = PairingState.CREATED
    failed_attempts: int = 0


class PairingManager:
    def __init__(self, hmac_key: bytes, max_attempts: int = 5) -> None:
        self.hmac_key = hmac_key
        self.max_attempts = max_attempts
        self._records: dict[str, _PairingRecord] = {}

    def _digest(self, code: str) -> bytes:
        return hmac.new(self.hmac_key, code.encode("ascii"), hashlib.sha256).digest()

    def create(self, phone_id: str, now: int) -> CreatedPairing:
        pairing_id = secrets.token_urlsafe(18)
        code = f"{secrets.randbelow(1_000_000):06d}"
        record = _PairingRecord(pairing_id, phone_id, self._digest(code), now + 300)
        self._records[pairing_id] = record
        return CreatedPairing(pairing_id, code, record.expires_at)

    def _view(self, record: _PairingRecord, now: int) -> PairingView:
        if now > record.expires_at and record.state not in {PairingState.CONFIRMED, PairingState.DENIED}:
            record.state = PairingState.EXPIRED
        return PairingView(record.pairing_id, record.phone_id, record.computer_id, record.state, record.expires_at)

    def get(self, pairing_id: str, now: int) -> PairingView:
        return self._view(self._records[pairing_id], now)

    def claim(self, pairing_id: str, computer_id: str, code: str, now: int) -> PairingView:
        record = self._records[pairing_id]
        view = self._view(record, now)
        if view.state is PairingState.EXPIRED:
            raise ValueError("pairing expired")
        if record.failed_attempts >= self.max_attempts:
            raise ValueError("pairing attempt limit reached")
        if record.state is not PairingState.CREATED:
            raise ValueError("pairing is not claimable")
        if not hmac.compare_digest(record.code_digest, self._digest(code)):
            record.failed_attempts += 1
            raise ValueError("invalid pairing code")
        record.computer_id = computer_id
        record.state = PairingState.CLAIMED
        return self._view(record, now)

    def confirm(self, pairing_id: str, phone_id: str, approved: bool, now: int) -> PairingView:
        record = self._records[pairing_id]
        self._view(record, now)
        if record.phone_id != phone_id:
            raise ValueError("only the pairing phone can confirm")
        if record.state is not PairingState.CLAIMED:
            raise ValueError("pairing has not been claimed")
        record.state = PairingState.CONFIRMED if approved else PairingState.DENIED
        return self._view(record, now)
