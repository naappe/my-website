import json
import os
import re
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

APP_NAME = "PhoneDesk"
VERSION = "0.1"


def normalize_host(host: str) -> str:
    value = host.strip()
    if not value:
        raise ValueError("Enter the phone IP address")
    if any(ch.isspace() for ch in value):
        raise ValueError("IP/host cannot contain spaces")
    return value


def normalize_port(port: str) -> int:
    try:
        value = int(port.strip())
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
        "device_port": device_port.strip(),
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
        self.geometry("700x660")
        self.minsize(660, 610)
        self.configure(bg="#0f172a")

        cfg = load_config()
        self.host_var = tk.StringVar(value=cfg.get("host", ""))
        self.pair_port_var = tk.StringVar()
        self.code_var = tk.StringVar()
        self.device_port_var = tk.StringVar(value=cfg.get("device_port", ""))
        self.screen_off_var = tk.BooleanVar(value=cfg.get("screen_off", True))
        self.status_var = tk.StringVar(value="Ready")

        self._setup_style()
        self._build_ui()
        self.after(250, self.refresh_devices)

    def _setup_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background="#0f172a")
        style.configure("Card.TFrame", background="#111827")
        style.configure("TLabel", background="#0f172a", foreground="#e5e7eb", font=("Segoe UI", 10))
        style.configure("Title.TLabel", background="#0f172a", foreground="#f8fafc", font=("Segoe UI Semibold", 23))
        style.configure("Sub.TLabel", background="#0f172a", foreground="#94a3b8", font=("Segoe UI", 10))
        style.configure("Card.TLabel", background="#111827", foreground="#e5e7eb", font=("Segoe UI", 10))
        style.configure("CardTitle.TLabel", background="#111827", foreground="#f8fafc", font=("Segoe UI Semibold", 13))
        style.configure("TButton", font=("Segoe UI Semibold", 10), padding=(12, 8))
        style.configure("Accent.TButton", font=("Segoe UI Semibold", 10), padding=(12, 9))
        style.configure("TEntry", padding=7)
        style.configure("TCheckbutton", background="#111827", foreground="#e5e7eb", font=("Segoe UI", 10))

    def _build_ui(self):
        outer = ttk.Frame(self, padding=22)
        outer.pack(fill="both", expand=True)

        ttk.Label(outer, text="PhoneDesk", style="Title.TLabel").pack(anchor="w")
        ttk.Label(
            outer,
            text="Pair once, reconnect over Wi-Fi, and control your Android phone from Windows.",
            style="Sub.TLabel",
        ).pack(anchor="w", pady=(2, 18))

        pair = ttk.Frame(outer, style="Card.TFrame", padding=16)
        pair.pack(fill="x", pady=(0, 12))
        ttk.Label(pair, text="1  Pair this Windows PC", style="CardTitle.TLabel").grid(row=0, column=0, columnspan=4, sticky="w", pady=(0, 10))
        ttk.Label(pair, text="Phone IP", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        ttk.Label(pair, text="Pairing port", style="Card.TLabel").grid(row=1, column=1, sticky="w", padx=(10, 0))
        ttk.Label(pair, text="6-digit code", style="Card.TLabel").grid(row=1, column=2, sticky="w", padx=(10, 0))
        ttk.Entry(pair, textvariable=self.host_var, width=22).grid(row=2, column=0, sticky="ew", pady=(4, 0))
        ttk.Entry(pair, textvariable=self.pair_port_var, width=12).grid(row=2, column=1, sticky="ew", padx=(10, 0), pady=(4, 0))
        ttk.Entry(pair, textvariable=self.code_var, width=12, show="•").grid(row=2, column=2, sticky="ew", padx=(10, 0), pady=(4, 0))
        ttk.Button(pair, text="PAIR", style="Accent.TButton", command=self.pair_pc).grid(row=2, column=3, padx=(10, 0), pady=(4, 0))
        ttk.Label(
            pair,
            text="On Android: Developer options → Wireless debugging → Pair device with pairing code. The code is never saved.",
            style="Card.TLabel",
            wraplength=620,
        ).grid(row=3, column=0, columnspan=4, sticky="w", pady=(11, 0))
        pair.columnconfigure(0, weight=2)
        pair.columnconfigure(1, weight=1)
        pair.columnconfigure(2, weight=1)

        connect = ttk.Frame(outer, style="Card.TFrame", padding=16)
        connect.pack(fill="x", pady=(0, 12))
        ttk.Label(connect, text="2  Connect to the trusted phone", style="CardTitle.TLabel").grid(row=0, column=0, columnspan=4, sticky="w", pady=(0, 10))
        ttk.Label(connect, text="Wireless debugging device port", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        ttk.Entry(connect, textvariable=self.device_port_var, width=15).grid(row=2, column=0, sticky="ew", pady=(4, 0))
        ttk.Button(connect, text="CONNECT", command=self.connect_phone).grid(row=2, column=1, padx=(10, 0), pady=(4, 0))
        ttk.Button(connect, text="REFRESH", command=self.refresh_devices).grid(row=2, column=2, padx=(10, 0), pady=(4, 0))
        ttk.Button(connect, text="DISCOVER", command=self.discover).grid(row=2, column=3, padx=(10, 0), pady=(4, 0))
        ttk.Label(
            connect,
            text="Use the port shown on the main Wireless debugging screen. It is usually different from the pairing port.",
            style="Card.TLabel",
            wraplength=620,
        ).grid(row=3, column=0, columnspan=4, sticky="w", pady=(11, 0))
        connect.columnconfigure(0, weight=1)

        control = ttk.Frame(outer, style="Card.TFrame", padding=16)
        control.pack(fill="x", pady=(0, 12))
        ttk.Label(control, text="3  Control", style="CardTitle.TLabel").pack(anchor="w", pady=(0, 8))
        ttk.Checkbutton(control, text="Keep the physical phone screen OFF while controlling", variable=self.screen_off_var).pack(anchor="w")
        btn_row = ttk.Frame(control, style="Card.TFrame")
        btn_row.pack(fill="x", pady=(12, 0))
        ttk.Button(btn_row, text="START PHONE CONTROL", style="Accent.TButton", command=self.start_control).pack(side="left")
        ttk.Button(btn_row, text="DISCONNECT", command=self.disconnect_phone).pack(side="left", padx=(10, 0))

        status_card = ttk.Frame(outer, style="Card.TFrame", padding=16)
        status_card.pack(fill="both", expand=True)
        ttk.Label(status_card, text="Status", style="CardTitle.TLabel").pack(anchor="w")
        ttk.Label(status_card, textvariable=self.status_var, style="Card.TLabel", wraplength=620).pack(anchor="w", pady=(6, 8))
        self.device_box = tk.Text(
            status_card,
            height=7,
            bg="#020617",
            fg="#cbd5e1",
            insertbackground="#f8fafc",
            relief="flat",
            font=("Cascadia Mono", 9),
            padx=10,
            pady=8,
        )
        self.device_box.pack(fill="both", expand=True)
        self.device_box.insert("1.0", "No device information yet.")
        self.device_box.configure(state="disabled")

    def set_status(self, text: str):
        self.after(0, lambda: self.status_var.set(text))

    def set_devices_text(self, text: str):
        def apply():
            self.device_box.configure(state="normal")
            self.device_box.delete("1.0", "end")
            self.device_box.insert("1.0", text or "No devices found.")
            self.device_box.configure(state="disabled")
        self.after(0, apply)

    def background(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def adb(self) -> Path:
        return find_tool("adb.exe" if os.name == "nt" else "adb")

    def scrcpy(self) -> Path:
        return find_tool("scrcpy.exe" if os.name == "nt" else "scrcpy")

    def pair_pc(self):
        try:
            endpoint = make_endpoint(self.host_var.get(), self.pair_port_var.get())
            code = validate_pairing_code(self.code_var.get())
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))
            return

        def task():
            try:
                self.set_status(f"Pairing with {endpoint}…")
                rc, output = run_capture([self.adb(), "pair", endpoint, code], timeout=35)
                # Never save or log the six-digit code.
                self.after(0, lambda: self.code_var.set(""))
                if rc == 0 and "success" in output.lower():
                    self.set_status("Pairing succeeded. Now enter the normal Wireless debugging device port below.")
                else:
                    self.set_status(output or "Pairing failed")
            except Exception as exc:
                self.after(0, lambda: self.code_var.set(""))
                self.set_status(f"Pairing error: {exc}")
        self.background(task)

    def _device_endpoint(self):
        return make_endpoint(self.host_var.get(), self.device_port_var.get())

    def connect_phone(self):
        try:
            endpoint = self._device_endpoint()
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))
            return

        def task():
            try:
                self.set_status(f"Connecting to {endpoint}…")
                rc, output = run_capture([self.adb(), "connect", endpoint])
                if rc == 0 and ("connected" in output.lower() or "already" in output.lower()):
                    save_config(self.host_var.get(), self.device_port_var.get(), self.screen_off_var.get())
                    self.set_status(f"Trusted phone connected: {endpoint}")
                    self.refresh_devices()
                else:
                    self.set_status(output or "Connection failed")
            except Exception as exc:
                self.set_status(f"Connection error: {exc}")
        self.background(task)

    def refresh_devices(self):
        def task():
            try:
                rc, output = run_capture([self.adb(), "devices", "-l"])
                self.set_devices_text(output)
                devices = parse_adb_devices(output)
                good = [d for d in devices if d[1] == "device"]
                if good:
                    self.set_status(f"{len(good)} Android device(s) available")
                elif rc == 0:
                    self.set_status("ADB is ready. No connected phone yet.")
                else:
                    self.set_status(output or "ADB error")
            except Exception as exc:
                self.set_status(f"ADB not ready: {exc}")
        self.background(task)

    def discover(self):
        def task():
            try:
                self.set_status("Looking for Android Wireless Debugging services…")
                _, output = run_capture([self.adb(), "mdns", "services"], timeout=15)
                self.set_devices_text(output or "No mDNS Wireless Debugging services found.")
                self.set_status("Discovery finished")
            except Exception as exc:
                self.set_status(f"Discovery error: {exc}")
        self.background(task)

    def start_control(self):
        try:
            endpoint = self._device_endpoint()
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))
            return

        def task():
            try:
                rc, output = run_capture([self.adb(), "connect", endpoint])
                if rc != 0 or ("unable" in output.lower() or "failed" in output.lower()):
                    self.set_status(output or "Could not connect to phone")
                    return
                save_config(self.host_var.get(), self.device_port_var.get(), self.screen_off_var.get())
                args = [self.scrcpy(), "--serial", endpoint, "--window-title", "PhoneDesk", "--disable-screensaver"]
                if self.screen_off_var.get():
                    args += ["--turn-screen-off", "--power-off-on-close"]
                subprocess.Popen([str(x) for x in args], cwd=str(app_folder()), creationflags=hidden_flags())
                self.set_status(f"Phone control opened for {endpoint}")
            except Exception as exc:
                self.set_status(f"Could not start control: {exc}")
        self.background(task)

    def disconnect_phone(self):
        try:
            endpoint = self._device_endpoint()
        except ValueError as exc:
            messagebox.showerror("PhoneDesk", str(exc))
            return

        def task():
            try:
                _, output = run_capture([self.adb(), "disconnect", endpoint])
                self.set_status(output or f"Disconnected {endpoint}")
                self.refresh_devices()
            except Exception as exc:
                self.set_status(f"Disconnect error: {exc}")
        self.background(task)


if __name__ == "__main__":
    PhoneDeskApp().mainloop()
