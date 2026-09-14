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
VERSION = "0.2.3"


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


def pairing_succeeded(returncode: int, output: str) -> bool:
    return returncode == 0 and "success" in (output or "").lower()


def pairing_code_after_result(code: str, success: bool) -> str:
    return "" if success else code


def choose_pairing_endpoint(cached, discovered):
    """Prefer the pairing endpoint Android is advertising right now."""
    return discovered or cached


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


def find_live_tcp_endpoint(output: str, preferred_host=None):
    """Find an already-connected network ADB endpoint, preferring the known phone host."""
    candidates = []
    preferred = preferred_host.strip() if preferred_host else None
    for serial, state in parse_adb_devices(output):
        if state != "device" or ":" not in serial:
            continue
        host_text, port_text = serial.rsplit(":", 1)
        host_text = host_text.strip("[]")
        try:
            endpoint = (normalize_host(host_text), normalize_port(port_text))
        except ValueError:
            continue
        if preferred and endpoint[0] == preferred:
            return endpoint
        candidates.append(endpoint)
    if len(candidates) == 1:
        return candidates[0]
    return None


def endpoint_state(output: str, endpoint):
    target = make_endpoint(endpoint[0], endpoint[1])
    for serial, state in parse_adb_devices(output):
        if serial == target:
            return state
    return None


def endpoint_is_connected(output: str, endpoint) -> bool:
    return endpoint_state(output, endpoint) == "device"


def build_scrcpy_args(scrcpy_path, endpoint, screen_off: bool):
    args = [
        str(scrcpy_path),
        "--serial",
        make_endpoint(endpoint[0], endpoint[1]),
        "--window-title",
        "PhoneDesk",
        "--disable-screensaver",
    ]
    if screen_off:
        args += ["--turn-screen-off", "--power-off-on-close"]
    return args


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
    data = {"host": host.strip(), "device_port": str(device_port).strip(), "screen_off": bool(screen_off)}
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
        self.geometry("680x570")
        self.minsize(620, 520)
        self.configure(bg="#0f172a")
        cfg = load_config()
        self.host_var = tk.StringVar(value=cfg.get("host", ""))
        self.pair_port_var = tk.StringVar()
        self.code_var = tk.StringVar()
        self.device_port_var = tk.StringVar(value=cfg.get("device_port", ""))
        self.screen_off_var = tk.BooleanVar(value=cfg.get("screen_off", True))
        self.status_var = tk.StringVar(value="Open Wireless debugging on your phone to begin.")
        self.phone_var = tk.StringVar(value="No phone found yet")
        self.control_result_var = tk.StringVar(value="Connect the phone, then press CONTROL PHONE.")
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
        style.configure("Status.TLabel", background="#111827", foreground="#facc15", font=("Segoe UI Semibold", 10))
        style.configure("Control.TLabel", background="#111827", foreground="#93c5fd", font=("Segoe UI Semibold", 11))
        style.configure("TButton", font=("Segoe UI Semibold", 10), padding=(12, 9))
        style.configure("Big.TButton", font=("Segoe UI Semibold", 13), padding=(18, 13))
        style.configure("Control.TButton", font=("Segoe UI Semibold", 16), padding=(20, 16))
        style.configure("TEntry", padding=9, font=("Segoe UI", 12))
        style.configure("TCheckbutton", background="#111827", foreground="#e5e7eb", font=("Segoe UI", 10))

    def _build_ui(self):
        outer = ttk.Frame(self, padding=20)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="PhoneDesk", style="Title.TLabel").pack(anchor="w")
        ttk.Label(outer, text="Easy Android control — connect first, then test CONTROL PHONE.", style="Sub.TLabel").pack(anchor="w", pady=(2, 14))

        card = ttk.Frame(outer, style="Card.TFrame", padding=18)
        card.pack(fill="x")
        self.card = card

        ttk.Label(card, text="1  Find your phone", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(card, text="On Android: open Wireless debugging. Pair only if this PC is not already trusted.", style="Card.TLabel", wraplength=590).pack(anchor="w", pady=(5, 9))
        ttk.Button(card, text="FIND MY PHONE", style="Big.TButton", command=self.find_phone).pack(fill="x")
        ttk.Label(card, textvariable=self.phone_var, style="Phone.TLabel", wraplength=590).pack(anchor="w", pady=(10, 0))
        ttk.Label(card, textvariable=self.status_var, style="Status.TLabel", wraplength=590).pack(anchor="w", pady=(7, 0))

        self.pairing_frame = ttk.Frame(card, style="Card.TFrame")
        self.pairing_frame.pack(fill="x", pady=(14, 0))
        ttk.Separator(self.pairing_frame).pack(fill="x", pady=(0, 14))
        ttk.Label(self.pairing_frame, text="2  Pair only if needed", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(self.pairing_frame, text="Enter the temporary 6-digit Android pairing code.", style="Card.TLabel").pack(anchor="w", pady=(5, 7))
        self.code_entry = ttk.Entry(self.pairing_frame, textvariable=self.code_var, justify="center", show="•")
        self.code_entry.pack(fill="x")
        self.pair_button = ttk.Button(self.pairing_frame, text="PAIR & CONNECT", style="Big.TButton", command=self.pair_and_connect)
        self.pair_button.pack(fill="x", pady=(8, 0))

        self.control_frame = ttk.Frame(card, style="Card.TFrame")
        self.control_frame.pack(fill="x", pady=(14, 0))
        ttk.Separator(self.control_frame).pack(fill="x", pady=(0, 14))
        ttk.Label(self.control_frame, text="3  Control", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Checkbutton(
            self.control_frame,
            text="Keep the physical phone screen OFF while controlling",
            variable=self.screen_off_var,
        ).pack(anchor="w", pady=(7, 8))
        self.control_button = ttk.Button(
            self.control_frame,
            text="CONTROL PHONE",
            style="Control.TButton",
            command=self.start_control,
        )
        self.control_button.pack(fill="x")
        ttk.Label(
            self.control_frame,
            textvariable=self.control_result_var,
            style="Control.TLabel",
            wraplength=590,
        ).pack(anchor="w", pady=(8, 0))

        self.manual_frame = ttk.Frame(outer, style="Card.TFrame", padding=16)
        ttk.Button(outer, text="Manual setup (only if automatic discovery fails)", command=self.toggle_manual).pack(anchor="w", pady=(10, 0))
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

    def set_control_result(self, text: str):
        self.after(0, lambda: self.control_result_var.set(text))

    def background(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def adb(self) -> Path:
        return find_tool("adb.exe" if os.name == "nt" else "adb")

    def scrcpy(self) -> Path:
        return find_tool("scrcpy.exe" if os.name == "nt" else "scrcpy")

    def _show_connected_ui(self):
        self.pairing_frame.pack_forget()
        self.control_result_var.set("READY — press CONTROL PHONE to verify screen control.")

    def _show_pairing_ui(self):
        if not self.pairing_frame.winfo_manager():
            self.pairing_frame.pack(fill="x", pady=(14, 0), before=self.control_frame)

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
            self.after(0, self._show_pairing_ui)
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))

    def _mdns(self):
        _, output = run_capture([self.adb(), "mdns", "services"], timeout=15)
        return parse_mdns_services(output), output

    def _adb_devices_output(self):
        _, output = run_capture([self.adb(), "devices"], timeout=10)
        return output

    def _endpoint_connected(self, endpoint):
        return endpoint_is_connected(self._adb_devices_output(), endpoint)

    def _use_live_endpoint(self, endpoint, status_text):
        self.connected_endpoint = endpoint
        self.host_var.set(endpoint[0])
        self.device_port_var.set(str(endpoint[1]))
        save_config(endpoint[0], endpoint[1], self.screen_off_var.get())
        self.set_phone(f"Connected: {endpoint[0]}")
        self.set_status(status_text)
        self.after(0, self._show_connected_ui)

    def _find_live_endpoint(self):
        preferred = self.host_var.get().strip() or load_config().get("host", "").strip()
        return find_live_tcp_endpoint(self._adb_devices_output(), preferred_host=preferred or None)

    def find_phone(self):
        def task():
            try:
                self.set_status("Looking for your phone…")
                run_capture([self.adb(), "start-server"], timeout=15)

                live = self._find_live_endpoint()
                if live:
                    self._use_live_endpoint(live, "Already connected through ADB. Pairing is not needed.")
                    return

                found, _ = self._mdns()
                pairing = found.get("pairing")
                connect = found.get("connect")
                if connect:
                    self.connected_endpoint = connect
                    self.host_var.set(connect[0])
                    self.device_port_var.set(str(connect[1]))
                    self.set_phone(f"Trusted phone found: {connect[0]}")
                    if self._connect(connect):
                        return
                if pairing:
                    self.pairing_endpoint = pairing
                    self.host_var.set(pairing[0])
                    self.pair_port_var.set(str(pairing[1]))
                    self.set_phone(f"Phone found: {pairing[0]}")
                    self.set_status("Phone found. Enter the fresh 6-digit code currently shown on Android, then press PAIR & CONNECT.")
                    self.after(0, self._show_pairing_ui)
                    self.after(0, self.code_entry.focus_set)
                    return
                self.set_phone("Phone not visible yet")
                self.set_status("Open Android Wireless debugging. If this PC is not trusted, open Pair device with pairing code and try FIND MY PHONE again.")
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
            self.set_status("Connected. Pairing is no longer needed.")
            self.after(0, self._show_connected_ui)
            return True
        self.set_status(output or "Could not connect to the phone")
        return False

    def pair_and_connect(self):
        raw_code = self.code_var.get()

        def task():
            try:
                live = self._find_live_endpoint()
                if live:
                    self._use_live_endpoint(live, "Already connected through ADB. No new pairing code is needed.")
                    return

                if self.connected_endpoint is not None and self._endpoint_connected(self.connected_endpoint):
                    self._use_live_endpoint(self.connected_endpoint, "Already connected. No new pairing code is needed.")
                    return

                found, _ = self._mdns()
                connect = found.get("connect")
                if connect and self._connect(connect):
                    self.set_status("Trusted phone connected. No new pairing code was needed.")
                    return

                try:
                    code = validate_pairing_code(raw_code)
                except ValueError as exc:
                    self.after(0, lambda: messagebox.showerror("PhoneDesk", str(exc)))
                    return

                fresh_pairing = found.get("pairing")
                pairing = choose_pairing_endpoint(self.pairing_endpoint, fresh_pairing)
                if fresh_pairing:
                    self.pairing_endpoint = fresh_pairing
                    self.host_var.set(fresh_pairing[0])
                    self.pair_port_var.set(str(fresh_pairing[1]))

                if pairing is None:
                    try:
                        pairing = (normalize_host(self.host_var.get()), normalize_port(self.pair_port_var.get()))
                    except ValueError:
                        self.set_status("Open Pair device with pairing code on Android, press FIND MY PHONE, then enter the fresh 6-digit code.")
                        return

                pair_target = make_endpoint(pairing[0], pairing[1])
                self.set_status(f"Pairing with the current Android pairing endpoint {pair_target}…")
                rc, output = run_capture([self.adb(), "pair", pair_target, code], timeout=35)
                success = pairing_succeeded(rc, output)
                self.after(0, lambda: self.code_var.set(pairing_code_after_result(raw_code, success)))
                if not success:
                    detail = output or "ADB did not accept the pairing code."
                    if "protocol fault" in detail.lower():
                        self.pairing_endpoint = None
                        self.set_status("Pairing endpoint changed or expired. On Android reopen Pair device with pairing code, then press FIND MY PHONE and enter the NEW 6-digit code.")
                    else:
                        self.set_status(f"Pairing failed: {detail} Keep the pairing popup open and use the current fresh code.")
                    return

                self.set_status("Pairing succeeded. Finding the control connection…")
                deadline = time.time() + 15
                connect = None
                while time.time() < deadline:
                    live = self._find_live_endpoint()
                    if live:
                        self._use_live_endpoint(live, "Pairing succeeded. Android control connection is ready.")
                        return
                    found, _ = self._mdns()
                    connect = found.get("connect")
                    if connect:
                        break
                    time.sleep(1)
                if connect and self._connect(connect):
                    return
                self.set_status("Pairing succeeded, but the control endpoint was not discovered yet. Keep Wireless debugging on and press FIND MY PHONE.")
            except Exception as exc:
                self.set_status(f"Pairing error: {exc}")
        self.background(task)

    def auto_reconnect(self):
        cfg = load_config()
        host = cfg.get("host", "")
        port = cfg.get("device_port", "")
        if host and port:
            def task():
                try:
                    live = self._find_live_endpoint()
                    if live:
                        self._use_live_endpoint(live, "Connected to your trusted phone.")
                        return
                    endpoint = (normalize_host(host), normalize_port(port))
                    self.set_status("Reconnecting to your trusted phone…")
                    if not self._connect(endpoint):
                        self.set_status("Trusted phone saved. Its Wireless debugging port may have changed; press FIND MY PHONE.")
                except Exception:
                    pass
            self.background(task)

    def start_control(self):
        endpoint = self.connected_endpoint
        if endpoint is None:
            live = self._find_live_endpoint()
            if live:
                endpoint = live
                self._use_live_endpoint(live, "Connected through the live ADB endpoint.")
            else:
                try:
                    endpoint = (normalize_host(self.host_var.get()), normalize_port(self.device_port_var.get()))
                except ValueError:
                    messagebox.showinfo("PhoneDesk", "Connect the phone first. Press FIND MY PHONE.")
                    return

        def task():
            try:
                self.set_control_result("CHECKING — verifying the Android control connection…")
                if not self._connect(endpoint):
                    self.set_control_result("CONTROL FAILED — PhoneDesk could not connect to the saved Android endpoint.")
                    return

                state = endpoint_state(self._adb_devices_output(), endpoint)
                if state != "device":
                    shown = state or "not found"
                    self.set_control_result(f"CONTROL FAILED — ADB state is '{shown}'. Reconnect the phone and try again.")
                    return

                scrcpy_path = self.scrcpy()
                rc, version_output = run_capture([scrcpy_path, "--version"], timeout=10)
                if rc != 0:
                    self.set_control_result(f"CONTROL FAILED — scrcpy could not start: {version_output or 'unknown error'}")
                    return

                args = build_scrcpy_args(scrcpy_path, endpoint, self.screen_off_var.get())
                proc = subprocess.Popen(
                    [str(x) for x in args],
                    cwd=str(app_folder()),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    creationflags=hidden_flags(),
                )
                time.sleep(1.25)
                if proc.poll() is None:
                    self.set_control_result("CONTROL OPENED — the PhoneDesk screen-control window is running.")
                    return

                detail = ""
                try:
                    detail = (proc.communicate(timeout=2)[0] or "").strip()
                except Exception:
                    pass
                if len(detail) > 320:
                    detail = detail[-320:]
                self.set_control_result(f"CONTROL FAILED — scrcpy exited immediately. {detail or 'No additional error text was returned.'}")
            except Exception as exc:
                self.set_control_result(f"CONTROL FAILED — {exc}")
        self.background(task)


if __name__ == "__main__":
    PhoneDeskApp().mainloop()
