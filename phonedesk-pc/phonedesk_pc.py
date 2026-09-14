import json
import os
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

APP_NAME = "PhoneDesk"
VERSION = "0.2"


def normalize_host(host: str) -> str:
    value = host.strip()
    if not value:
        raise ValueError("Enter the phone IP address")
    if any(ch.isspace() for ch in value):
        raise ValueError("IP/host cannot contain spaces")
    return value


def normalize_port(port: str) -> int:
    try:
        value = int(str(port).strip())
    except Exception as exc:
        raise ValueError("Port must be a number") from exc
    if not 1 <= value <= 65535:
        raise ValueError("Port must be between 1 and 65535")
    return value


def make_endpoint(host: str, port: str) -> str:
    return f"{normalize_host(host)}:{normalize_port(port)}"


def validate_pairing_code(code: str) -> str:
    value = code.strip()
    if not re.fullmatch(r"\d{6}", value):
        raise ValueError("Pairing code must be exactly 6 digits")
    return value


def parse_adb_devices(output: str):
    devices = []
    for raw in output.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices


def parse_mdns_services(output: str):
    """Return the first Wireless Debugging pairing/connect endpoints ADB reports."""
    found = {}
    for raw in output.splitlines():
        line = raw.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2:
            continue

        service_type = None
        for part in parts:
            clean = part.rstrip(".")
            if clean == "_adb-tls-pairing._tcp":
                service_type = "pairing"
                break
            if clean == "_adb-tls-connect._tcp":
                service_type = "connect"
                break
        if service_type is None:
            continue

        endpoint = parts[-1]
        if ":" not in endpoint:
            continue
        host, port_text = endpoint.rsplit(":", 1)
        host = host.strip("[]")
        try:
            port = normalize_port(port_text)
            host = normalize_host(host)
        except ValueError:
            continue
        found.setdefault(service_type, (host, port))
    return found


def app_folder() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def config_path() -> Path:
    root = Path(os.environ.get("APPDATA", Path.home())) / APP_NAME
    root.mkdir(parents=True, exist_ok=True)
    return root / "config.json"


def load_config():
    try:
        return json.loads(config_path().read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_config(host: str, device_port: str, screen_off: bool):
    # Pairing port and six-digit pairing code are intentionally never stored.
    data = {
        "host": host.strip(),
        "device_port": str(device_port).strip(),
        "screen_off": bool(screen_off),
    }
    config_path().write_text(json.dumps(data, indent=2), encoding="utf-8")


def find_tool(name: str) -> Path:
    base = app_folder()
    direct = base / name
    if direct.exists():
        return direct
    for candidate in base.rglob(name):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"{name} was not found in the PhoneDesk folder")


def hidden_flags():
    return subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def run_capture(args, timeout=25):
    proc = subprocess.run(
        [str(x) for x in args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        creationflags=hidden_flags(),
    )
    return proc.returncode, proc.stdout.strip()


class PhoneDeskApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"PhoneDesk {VERSION}")
        self.geometry("650x600")
        self.minsize(610, 560)
        self.configure(bg="#0f172a")

        cfg = load_config()
        self.host_var = tk.StringVar(value=cfg.get("host", ""))
        self.pair_port_var = tk.StringVar()
        self.code_var = tk.StringVar()
        self.device_port_var = tk.StringVar(value=cfg.get("device_port", ""))
        self.screen_off_var = tk.BooleanVar(value=cfg.get("screen_off", True))
        self.status_var = tk.StringVar(value="Open Wireless debugging on your phone to begin.")
        self.phone_var = tk.StringVar(value="No phone found yet")
        self.pairing_endpoint = None
        self.connected_endpoint = None
        self.manual_open = False

        self._setup_style()
        self._build_ui()
        self.after(500, self.auto_reconnect)

    def _setup_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background="#0f172a")
        style.configure("Card.TFrame", background="#111827")
        style.configure("TLabel", background="#0f172a", foreground="#e5e7eb", font=("Segoe UI", 10))
        style.configure("Title.TLabel", background="#0f172a", foreground="#f8fafc", font=("Segoe UI Semibold", 25))
        style.configure("Sub.TLabel", background="#0f172a", foreground="#94a3b8", font=("Segoe UI", 10))
        style.configure("Card.TLabel", background="#111827", foreground="#e5e7eb", font=("Segoe UI", 10))
        style.configure("CardTitle.TLabel", background="#111827", foreground="#f8fafc", font=("Segoe UI Semibold", 15))
        style.configure("Phone.TLabel", background="#111827", foreground="#86efac", font=("Segoe UI Semibold", 12))
        style.configure("TButton", font=("Segoe UI Semibold", 10), padding=(12, 9))
        style.configure("Big.TButton", font=("Segoe UI Semibold", 13), padding=(18, 13))
        style.configure("TEntry", padding=9, font=("Segoe UI", 12))
        style.configure("TCheckbutton", background="#111827", foreground="#e5e7eb", font=("Segoe UI", 10))

    def _build_ui(self):
        outer = ttk.Frame(self, padding=24)
        outer.pack(fill="both", expand=True)

        ttk.Label(outer, text="PhoneDesk", style="Title.TLabel").pack(anchor="w")
        ttk.Label(
            outer,
            text="Easy wireless Android control — no IP or port typing in normal use.",
            style="Sub.TLabel",
        ).pack(anchor="w", pady=(2, 18))

        card = ttk.Frame(outer, style="Card.TFrame", padding=20)
        card.pack(fill="x")

        ttk.Label(card, text="1  Find your phone", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(
            card,
            text="On Android: Wireless debugging → Pair device with pairing code. Keep that popup open.",
            style="Card.TLabel",
            wraplength=560,
        ).pack(anchor="w", pady=(7, 12))
        ttk.Button(card, text="FIND MY PHONE", style="Big.TButton", command=self.find_phone).pack(fill="x")
        ttk.Label(card, textvariable=self.phone_var, style="Phone.TLabel", wraplength=560).pack(anchor="w", pady=(12, 0))

        ttk.Separator(card).pack(fill="x", pady=18)
        ttk.Label(card, text="2  Enter the 6-digit code", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(
            card,
            text="Only enter the code shown by Android. PhoneDesk never saves it.",
            style="Card.TLabel",
        ).pack(anchor="w", pady=(6, 8))
        self.code_entry = ttk.Entry(card, textvariable=self.code_var, justify="center", show="•")
        self.code_entry.pack(fill="x")
        self.pair_button = ttk.Button(card, text="PAIR & CONNECT", style="Big.TButton", command=self.pair_and_connect)
        self.pair_button.pack(fill="x", pady=(10, 0))

        ttk.Separator(card).pack(fill="x", pady=18)
        ttk.Label(card, text="3  Control", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Checkbutton(card, text="Keep the physical phone screen OFF while controlling", variable=self.screen_off_var).pack(anchor="w", pady=(8, 10))
        self.control_button = ttk.Button(card, text="CONTROL PHONE", style="Big.TButton", command=self.start_control)
        self.control_button.pack(fill="x")

        status = ttk.Frame(outer, style="Card.TFrame", padding=16)
        status.pack(fill="x", pady=(14, 0))
        ttk.Label(status, text="Status", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(status, textvariable=self.status_var, style="Card.TLabel", wraplength=560).pack(anchor="w", pady=(5, 0))

        self.manual_frame = ttk.Frame(outer, style="Card.TFrame", padding=16)
        manual_toggle = ttk.Button(outer, text="Manual setup (only if automatic discovery fails)", command=self.toggle_manual)
        manual_toggle.pack(anchor="w", pady=(12, 0))

        ttk.Label(self.manual_frame, text="Manual fallback", style="CardTitle.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(self.manual_frame, text="Phone IP", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=(8, 0))
        ttk.Label(self.manual_frame, text="Pairing port", style="Card.TLabel").grid(row=1, column=1, sticky="w", padx=(8, 0), pady=(8, 0))
        ttk.Label(self.manual_frame, text="Device port", style="Card.TLabel").grid(row=1, column=2, sticky="w", padx=(8, 0), pady=(8, 0))
        ttk.Entry(self.manual_frame, textvariable=self.host_var).grid(row=2, column=0, sticky="ew")
        ttk.Entry(self.manual_frame, textvariable=self.pair_port_var, width=12).grid(row=2, column=1, sticky="ew", padx=(8, 0))
        ttk.Entry(self.manual_frame, textvariable=self.device_port_var, width=12).grid(row=2, column=2, sticky="ew", padx=(8, 0))
        ttk.Button(self.manual_frame, text="USE MANUAL VALUES", command=self.use_manual_values).grid(row=3, column=0, columnspan=3, sticky="ew", pady=(10, 0))
        self.manual_frame.columnconfigure(0, weight=2)
        self.manual_frame.columnconfigure(1, weight=1)
        self.manual_frame.columnconfigure(2, weight=1)

    def set_status(self, text: str):
        self.after(0, lambda: self.status_var.set(text))

    def set_phone(self, text: str):
        self.after(0, lambda: self.phone_var.set(text))

    def background(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def adb(self) -> Path:
        return find_tool("adb.exe" if os.name == "nt" else "adb")

    def scrcpy(self) -> Path:
        return find_tool("scrcpy.exe" if os.name == "nt" else "scrcpy")

    def toggle_manual(self):
        if self.manual_open:
            self.manual_frame.pack_forget()
            self.manual_open = False
        else:
            self.manual_frame.pack(fill="x", pady=(8, 0))
            self.manual_open = True

    def use_manual_values(self):
        try:
            host = normalize_host(self.host_var.get())
            pair_port = normalize_port(self.pair_port_var.get())
            self.pairing_endpoint = (host, pair_port)
            if self.device_port_var.get().strip():
                self.connected_endpoint = (host, normalize_port(self.device_port_var.get()))
            self.set_phone(f"Phone set manually: {host}")
            self.set_status("Manual values loaded. Enter the 6-digit code and press PAIR & CONNECT.")
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))

    def _mdns(self):
        _, output = run_capture([self.adb(), "mdns", "services"], timeout=15)
        return parse_mdns_services(output), output

    def find_phone(self):
        def task():
            try:
                self.set_status("Looking for your phone…")
                run_capture([self.adb(), "start-server"], timeout=15)
                found, _ = self._mdns()
                pairing = found.get("pairing")
                connect = found.get("connect")

                if pairing:
                    self.pairing_endpoint = pairing
                    self.host_var.set(pairing[0])
                    self.pair_port_var.set(str(pairing[1]))
                    self.set_phone(f"Phone found: {pairing[0]}")
                    self.set_status("Phone found. Enter the 6-digit code shown on Android, then press PAIR & CONNECT.")
                    self.after(0, self.code_entry.focus_set)
                    return

                if connect:
                    self.connected_endpoint = connect
                    self.host_var.set(connect[0])
                    self.device_port_var.set(str(connect[1]))
                    self.set_phone(f"Trusted phone found: {connect[0]}")
                    if self._connect(connect):
                        return

                self.set_phone("Phone not visible yet")
                self.set_status("On the phone, open Wireless debugging → Pair device with pairing code, keep the popup open, then press FIND MY PHONE again.")
            except Exception as exc:
                self.set_status(f"Could not find phone: {exc}")
        self.background(task)

    def _connect(self, endpoint):
        target = make_endpoint(endpoint[0], endpoint[1])
        rc, output = run_capture([self.adb(), "connect", target], timeout=20)
        ok = rc == 0 and ("connected" in output.lower() or "already" in output.lower())
        if ok:
            self.connected_endpoint = endpoint
            self.host_var.set(endpoint[0])
            self.device_port_var.set(str(endpoint[1]))
            save_config(endpoint[0], endpoint[1], self.screen_off_var.get())
            self.set_phone(f"Connected: {endpoint[0]}")
            self.set_status("Connected. Press CONTROL PHONE.")
            return True
        self.set_status(output or "Could not connect to the phone")
        return False

    def pair_and_connect(self):
        try:
            code = validate_pairing_code(self.code_var.get())
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))
            return

        def task():
            try:
                pairing = self.pairing_endpoint
                if pairing is None:
                    try:
                        pairing = (normalize_host(self.host_var.get()), normalize_port(self.pair_port_var.get()))
                    except ValueError:
                        self.set_status("Press FIND MY PHONE first while the Android pairing-code popup is open.")
                        return

                pair_target = make_endpoint(pairing[0], pairing[1])
                self.set_status("Pairing securely with your phone…")
                rc, output = run_capture([self.adb(), "pair", pair_target, code], timeout=35)
                self.after(0, lambda: self.code_var.set(""))
                if rc != 0 or "success" not in output.lower():
                    self.set_status(output or "Pairing failed. Generate a new pairing code and try again.")
                    return

                self.set_status("Paired. Finding the connection automatically…")
                host = pairing[0]
                connect = None
                for _ in range(12):
                    found, _ = self._mdns()
                    candidate = found.get("connect")
                    if candidate and candidate[0] == host:
                        connect = candidate
                        break
                    devices_rc, devices_out = run_capture([self.adb(), "devices"], timeout=10)
                    if devices_rc == 0:
                        for serial, state in parse_adb_devices(devices_out):
                            if state == "device" and serial.startswith(host + ":"):
                                serial_host, serial_port = serial.rsplit(":", 1)
                                connect = (serial_host, normalize_port(serial_port))
                                break
                    if connect:
                        break
                    time.sleep(1)

                if connect and self._connect(connect):
                    return

                self.set_phone(f"Paired: {host}")
                self.set_status("Pairing succeeded. Close the pairing popup but stay on Wireless debugging, then press FIND MY PHONE once more.")
            except Exception as exc:
                self.after(0, lambda: self.code_var.set(""))
                self.set_status(f"Pairing error: {exc}")
        self.background(task)

    def auto_reconnect(self):
        cfg = load_config()
        host = str(cfg.get("host", "")).strip()
        port = str(cfg.get("device_port", "")).strip()
        if not host or not port:
            return

        def task():
            try:
                found, _ = self._mdns()
                connect = found.get("connect")
                if connect and (not host or connect[0] == host):
                    self._connect(connect)
                else:
                    self._connect((host, normalize_port(port)))
            except Exception:
                pass
        self.background(task)

    def _usable_endpoint(self):
        if self.connected_endpoint:
            return self.connected_endpoint
        host = normalize_host(self.host_var.get())
        port = normalize_port(self.device_port_var.get())
        return host, port

    def start_control(self):
        def task():
            try:
                endpoint = self._usable_endpoint()
                if not self._connect(endpoint):
                    return
                target = make_endpoint(endpoint[0], endpoint[1])
                args = [self.scrcpy(), "--serial", target, "--window-title", "PhoneDesk", "--disable-screensaver"]
                if self.screen_off_var.get():
                    args += ["--turn-screen-off", "--power-off-on-close"]
                subprocess.Popen([str(x) for x in args], cwd=str(app_folder()), creationflags=hidden_flags())
                self.set_status("Phone control opened. You can use the phone from the PC.")
            except ValueError:
                self.set_status("No connected phone yet. Press FIND MY PHONE first.")
            except Exception as exc:
                self.set_status(f"Could not start phone control: {exc}")
        self.background(task)


if __name__ == "__main__":
    PhoneDeskApp().mainloop()
