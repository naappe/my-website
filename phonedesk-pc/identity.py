from __future__ import annotations

import base64
import ctypes
import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


IDENTITY_VERSION = 1
IDENTITY_ALGORITHM = "ECDSA_P256_SHA256"
CRYPTPROTECT_UI_FORBIDDEN = 0x1


@dataclass(frozen=True)
class DeviceIdentity:
    public_key_der: bytes
    fingerprint: str


def fingerprint_public_key(public_key_der: bytes) -> str:
    digest = hashlib.sha256(public_key_der).digest()
    return ":".join(f"{value:02X}" for value in digest[:4])


class SecretProtector(Protocol):
    def protect(self, data: bytes) -> bytes: ...

    def unprotect(self, data: bytes) -> bytes: ...


class _DataBlob(ctypes.Structure):
    _fields_ = [
        ("cbData", ctypes.c_ulong),
        ("pbData", ctypes.POINTER(ctypes.c_ubyte)),
    ]


def _blob_from_bytes(data: bytes) -> tuple[_DataBlob, ctypes.Array]:
    size = max(1, len(data))
    buffer = (ctypes.c_ubyte * size)()
    if data:
        ctypes.memmove(buffer, data, len(data))
    blob = _DataBlob(
        len(data),
        ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)),
    )
    return blob, buffer


class WindowsDpapiProtector:
    def __init__(self) -> None:
        if os.name != "nt":
            raise OSError("Windows DPAPI is available only on Windows")

        self._crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

        self._crypt32.CryptProtectData.argtypes = [
            ctypes.POINTER(_DataBlob),
            ctypes.c_wchar_p,
            ctypes.POINTER(_DataBlob),
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.c_ulong,
            ctypes.POINTER(_DataBlob),
        ]
        self._crypt32.CryptProtectData.restype = ctypes.c_int

        self._crypt32.CryptUnprotectData.argtypes = [
            ctypes.POINTER(_DataBlob),
            ctypes.POINTER(ctypes.c_wchar_p),
            ctypes.POINTER(_DataBlob),
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.c_ulong,
            ctypes.POINTER(_DataBlob),
        ]
        self._crypt32.CryptUnprotectData.restype = ctypes.c_int

        self._kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        self._kernel32.LocalFree.restype = ctypes.c_void_p

    def protect(self, data: bytes) -> bytes:
        source, source_buffer = _blob_from_bytes(data)
        destination = _DataBlob()
        del source_buffer  # source memory remains owned by the ctypes object referenced by source

        ok = self._crypt32.CryptProtectData(
            ctypes.byref(source),
            "PhoneDesk Remote identity",
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            ctypes.byref(destination),
        )
        if not ok:
            raise ctypes.WinError(ctypes.get_last_error())

        try:
            return ctypes.string_at(destination.pbData, destination.cbData)
        finally:
            if destination.pbData:
                self._kernel32.LocalFree(destination.pbData)

    def unprotect(self, data: bytes) -> bytes:
        source, source_buffer = _blob_from_bytes(data)
        destination = _DataBlob()
        del source_buffer

        ok = self._crypt32.CryptUnprotectData(
            ctypes.byref(source),
            None,
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            ctypes.byref(destination),
        )
        if not ok:
            raise ctypes.WinError(ctypes.get_last_error())

        try:
            return ctypes.string_at(destination.pbData, destination.cbData)
        finally:
            if destination.pbData:
                self._kernel32.LocalFree(destination.pbData)


class DeviceIdentityStore:
    def __init__(self, path: Path, protector: SecretProtector) -> None:
        self._path = Path(path)
        self._protector = protector
        self._private_key: ec.EllipticCurvePrivateKey | None = None

    def load_or_create(self) -> DeviceIdentity:
        private_key = self._load_private_key() if self._path.exists() else self._create_private_key()
        self._private_key = private_key
        public_key_der = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        return DeviceIdentity(
            public_key_der=public_key_der,
            fingerprint=fingerprint_public_key(public_key_der),
        )

    def sign(self, message: bytes) -> bytes:
        if self._private_key is None:
            self.load_or_create()
        assert self._private_key is not None
        return self._private_key.sign(message, ec.ECDSA(hashes.SHA256()))

    def _create_private_key(self) -> ec.EllipticCurvePrivateKey:
        private_key = ec.generate_private_key(ec.SECP256R1())
        private_der = private_key.private_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
        protected = self._protector.protect(private_der)
        document = {
            "version": IDENTITY_VERSION,
            "algorithm": IDENTITY_ALGORITHM,
            "protected_private_key": base64.b64encode(protected).decode("ascii"),
        }
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_suffix(self._path.suffix + ".tmp")
        temporary.write_text(json.dumps(document, separators=(",", ":")), encoding="utf-8")
        temporary.replace(self._path)
        return private_key

    def _load_private_key(self) -> ec.EllipticCurvePrivateKey:
        document = json.loads(self._path.read_text(encoding="utf-8"))
        if document.get("version") != IDENTITY_VERSION:
            raise ValueError("unsupported identity file version")
        if document.get("algorithm") != IDENTITY_ALGORITHM:
            raise ValueError("unsupported identity algorithm")

        encoded = document.get("protected_private_key")
        if not isinstance(encoded, str) or not encoded:
            raise ValueError("identity file has no protected private key")

        try:
            protected = base64.b64decode(encoded, validate=True)
        except Exception as exc:
            raise ValueError("identity file contains invalid protected key data") from exc

        private_der = self._protector.unprotect(protected)
        private_key = serialization.load_der_private_key(private_der, password=None)
        if not isinstance(private_key, ec.EllipticCurvePrivateKey):
            raise ValueError("identity key is not an EC private key")
        if not isinstance(private_key.curve, ec.SECP256R1):
            raise ValueError("identity key is not P-256")
        return private_key


def default_identity_store() -> DeviceIdentityStore:
    root = Path(os.environ.get("APPDATA", Path.home())) / "PhoneDesk"
    return DeviceIdentityStore(root / "remote-identity.json", WindowsDpapiProtector())
