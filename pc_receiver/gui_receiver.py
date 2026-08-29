#!/usr/bin/env python3
"""
MicRelay Desktop Receiver - Windows GUI Application
Receives live low-latency microphone audio from the Android MicRelay app.
Supports dynamic random ports, PIN authentication, device selection, and live VU audio visualizer.
"""

import sys
import os
import time
import socket
import struct
import threading
import math
import tkinter as tk
from tkinter import ttk, messagebox

# Try importing audio & math dependencies
try:
    import sounddevice as sd
    import numpy as np
    import requests
    SOUNDDEVICE_AVAILABLE = True
except ImportError:
    SOUNDDEVICE_AVAILABLE = False


class MicRelayGuiReceiver(tk.Tk):
    def __init__(self):
        super().__init__()

        self.title("🎙️ MicRelay Wireless - PC Desktop Receiver")
        self.geometry("540x620")
        self.resizable(False, False)
        self.configure(bg="#0F172A")

        # Connection State
        self.is_connected = False
        self.receiver_thread = None
        self.stop_event = threading.Event()
        self.current_volume = 1.0
        self.latest_amplitude = 0.0

        self.output_devices = []
        self.device_map = {}

        self.setup_styles()
        self.create_widgets()
        self.load_audio_devices()

        # Audio visualizer tick loop
        self.update_visualizer_tick()

    def setup_styles(self):
        self.style = ttk.Style()
        self.style.theme_use("clam")

        # Configure dark colors
        self.style.configure(".", background="#0F172A", foreground="#F8FAFC", font=("Segoe UI", 10))
        self.style.configure("TLabel", background="#1E293B", foreground="#94A3B8", font=("Segoe UI", 9, "bold"))
        self.style.configure("Header.TLabel", background="#0F172A", foreground="#38BDF8", font=("Segoe UI", 16, "bold"))
        self.style.configure("SubHeader.TLabel", background="#0F172A", foreground="#94A3B8", font=("Segoe UI", 9))
        self.style.configure("Card.TFrame", background="#1E293B", relief="flat")
        self.style.configure("TRadiobutton", background="#1E293B", foreground="#F8FAFC", font=("Segoe UI", 10))
        
        self.style.configure("TCombobox", fieldbackground="#0F172A", background="#334155", foreground="#38BDF8")
        self.style.map("TCombobox", fieldbackground=[("readonly", "#0F172A")], foreground=[("readonly", "#38BDF8")])

    def create_widgets(self):
        # Main Padding Container
        main_frame = tk.Frame(self, bg="#0F172A", padx=20, pady=20)
        main_frame.pack(fill="both", expand=True)

        # Header Title
        lbl_title = ttk.Label(main_frame, text="🎙️ MicRelay Desktop Receiver", style="Header.TLabel")
        lbl_title.pack(anchor="w")

        lbl_subtitle = ttk.Label(main_frame, text="Secure One-Way Wireless Audio Streamer for PC", style="SubHeader.TLabel")
        lbl_subtitle.pack(anchor="w", pady=(0, 16))

        # Connection Card
        card_frame = tk.Frame(main_frame, bg="#1E293B", bd=1, relief="solid", highlightbackground="#334155")
        card_frame.pack(fill="x", pady=6, ipady=10, ipadx=10)

        # Phone IP Row
        lbl_ip = tk.Label(card_frame, text="Phone IP Address:", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 9, "bold"))
        lbl_ip.grid(row=0, column=0, sticky="w", padx=10, pady=6)
        self.entry_ip = tk.Entry(card_frame, bg="#0F172A", fg="#38BDF8", insertbackground="white", font=("Segoe UI", 11, "bold"), bd=1, relief="solid")
        self.entry_ip.insert(0, "192.168.1.13")
        self.entry_ip.grid(row=0, column=1, columnspan=2, sticky="ew", padx=10, pady=6)

        # Dynamic Port Row
        lbl_port = tk.Label(card_frame, text="Dynamic Port:", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 9, "bold"))
        lbl_port.grid(row=1, column=0, sticky="w", padx=10, pady=6)
        self.entry_port = tk.Entry(card_frame, bg="#0F172A", fg="#4ADE80", insertbackground="white", font=("Segoe UI", 11, "bold"), bd=1, relief="solid")
        self.entry_port.insert(0, "54129")
        self.entry_port.grid(row=1, column=1, columnspan=2, sticky="ew", padx=10, pady=6)

        # Security PIN Row
        lbl_pin = tk.Label(card_frame, text="Security PIN:", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 9, "bold"))
        lbl_pin.grid(row=2, column=0, sticky="w", padx=10, pady=6)
        self.entry_pin = tk.Entry(card_frame, bg="#0F172A", fg="#F59E0B", insertbackground="white", font=("Segoe UI", 11, "bold"), bd=1, relief="solid")
        self.entry_pin.insert(0, "4829")
        self.entry_pin.grid(row=2, column=1, columnspan=2, sticky="ew", padx=10, pady=6)

        # Stream Mode Selector
        lbl_mode = tk.Label(card_frame, text="Stream Mode:", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 9, "bold"))
        lbl_mode.grid(row=3, column=0, sticky="w", padx=10, pady=6)
        
        self.mode_var = tk.StringVar(value="udp")
        rb_udp = ttk.Radiobutton(card_frame, text="UDP (Low Latency)", variable=self.mode_var, value="udp")
        rb_http = ttk.Radiobutton(card_frame, text="HTTP Web Stream", variable=self.mode_var, value="http")
        rb_udp.grid(row=3, column=1, sticky="w", padx=4)
        rb_http.grid(row=3, column=2, sticky="w", padx=4)

        # Audio Device Selector
        lbl_dev = tk.Label(card_frame, text="Audio Output Device:", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 9, "bold"))
        lbl_dev.grid(row=4, column=0, sticky="w", padx=10, pady=6)

        self.combo_device = ttk.Combobox(card_frame, state="readonly", font=("Segoe UI", 9))
        self.combo_device.grid(row=4, column=1, columnspan=2, sticky="ew", padx=10, pady=6)

        card_frame.columnconfigure(1, weight=1)

        # Connect / Disconnect Action Button
        self.btn_connect = tk.Button(
            main_frame,
            text="▶ CONNECT & START RECEIVER",
            font=("Segoe UI", 11, "bold"),
            bg="#0284C7",
            fg="white",
            activebackground="#0369A1",
            activeforeground="white",
            bd=0,
            relief="flat",
            pady=10,
            cursor="hand2",
            command=self.toggle_connection
        )
        self.btn_connect.pack(fill="x", pady=16)

        # Status Label Container
        self.lbl_status = tk.Label(
            main_frame,
            text="Status: Ready to connect",
            bg="#0F172A",
            fg="#94A3B8",
            font=("Segoe UI", 10, "bold")
        )
        self.lbl_status.pack(pady=(0, 12))

        # Audio Visualizer & Level Meter Card
        viz_card = tk.Frame(main_frame, bg="#1E293B", bd=1, relief="solid", highlightbackground="#334155")
        viz_card.pack(fill="x", pady=4, ipady=12, ipadx=12)

        lbl_viz = tk.Label(viz_card, text="LIVE AUDIO LEVEL METER", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 8, "bold"))
        lbl_viz.pack(anchor="w", padx=12)

        # Visual Canvas Bar
        self.canvas_vu = tk.Canvas(viz_card, bg="#0F172A", height=24, bd=0, highlightthickness=0)
        self.canvas_vu.pack(fill="x", padx=12, pady=8)

        # Output Volume Control Slider
        vol_frame = tk.Frame(viz_card, bg="#1E293B")
        vol_frame.pack(fill="x", padx=12, pady=(4, 0))

        lbl_vol = tk.Label(vol_frame, text="PC Volume Control:", bg="#1E293B", fg="#94A3B8", font=("Segoe UI", 9, "bold"))
        lbl_vol.pack(side="left")

        self.slider_vol = tk.Scale(
            vol_frame,
            from_=0,
            to=200,
            orient="horizontal",
            bg="#1E293B",
            fg="#38BDF8",
            highlightthickness=0,
            troughcolor="#0F172A",
            activebackground="#38BDF8",
            length=200,
            command=self.on_volume_changed
        )
        self.slider_vol.set(100)
        self.slider_vol.pack(side="right")

    def load_audio_devices(self):
        if not SOUNDDEVICE_AVAILABLE:
            messagebox.showerror("Error", "Required library 'sounddevice' is missing.\nInstall with: pip install sounddevice numpy requests")
            return

        try:
            devices = sd.query_devices()
            self.output_devices = []
            self.device_map = {}

            for idx, dev in enumerate(devices):
                if dev['max_output_channels'] > 0:
                    name = f"[{idx}] {dev['name']}"
                    self.output_devices.append(name)
                    self.device_map[name] = idx

            if self.output_devices:
                self.combo_device['values'] = self.output_devices
                self.combo_device.current(0)
            else:
                self.combo_device['values'] = ["Default Output Device"]
                self.combo_device.current(0)
        except Exception as e:
            print("Error querying sound devices:", e)

    def on_volume_changed(self, val):
        self.current_volume = float(val) / 100.0

    def toggle_connection(self):
        if not self.is_connected:
            self.start_receiver()
        else:
            self.stop_receiver()

    def start_receiver(self):
        ip = self.entry_ip.get().strip()
        port_str = self.entry_port.get().strip()
        pin = self.entry_pin.get().strip()
        mode = self.mode_var.get()
        dev_name = self.combo_device.get()

        if not ip or not port_str or not pin:
            messagebox.showwarning("Warning", "Please enter Phone IP, Dynamic Port, and Security PIN.")
            return

        try:
            port = int(port_str)
        except ValueError:
            messagebox.showerror("Error", "Invalid port number.")
            return

        device_id = self.device_map.get(dev_name, None)

        self.is_connected = True
        self.btn_connect.config(text="⏹ DISCONNECT RECEIVER", bg="#EF4444", activebackground="#DC2626")
        self.lbl_status.config(text="Status: Authenticating...", fg="#F59E0B")

        self.stop_event.clear()

        if mode == "udp":
            self.receiver_thread = threading.Thread(
                target=self.run_udp_loop,
                args=(ip, port, pin, device_id),
                daemon=True
            )
        else:
            self.receiver_thread = threading.Thread(
                target=self.run_http_loop,
                args=(ip, port, pin, device_id),
                daemon=True
            )

        self.receiver_thread.start()

    def stop_receiver(self):
        self.is_connected = False
        self.stop_event.set()
        self.btn_connect.config(text="▶ CONNECT & START RECEIVER", bg="#0284C7", activebackground="#0369A1")
        self.lbl_status.config(text="Status: Disconnected", fg="#94A3B8")
        self.latest_amplitude = 0.0

    def run_udp_loop(self, ip, port, pin, device_id):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(2.0)

        # Send UDP Auth Handshake packet to Phone
        try:
            auth_msg = f"AUTH:{pin}".encode("utf-8")
            sock.sendto(auth_msg, (ip, port))
            
            # Wait for handshake response
            data, addr = sock.recvfrom(256)
            resp = data.decode("utf-8", errors="ignore").strip()
            if resp != "AUTH_OK":
                self.after(0, lambda: self.on_auth_failed("Invalid Security PIN or Handshake Denied"))
                sock.close()
                return
        except Exception as e:
            # Re-try UDP bind/handshake or proceed if socket open
            try:
                auth_msg = f"AUTH:{pin}".encode("utf-8")
                sock.sendto(auth_msg, (ip, port))
            except Exception as ex:
                self.after(0, lambda: self.on_auth_failed(f"Failed to connect to {ip}:{port}"))
                sock.close()
                return

        self.after(0, lambda: self.lbl_status.config(text=f"Status: Streaming Live (UDP Port {port})", fg="#4ADE80"))

        try:
            stream = sd.OutputStream(
                samplerate=44100,
                channels=1,
                dtype='int16',
                device=device_id
            )
            stream.start()
        except Exception as e:
            self.after(0, lambda: self.on_auth_failed(f"Audio device error: {e}"))
            sock.close()
            return

        while not self.stop_event.is_set():
            try:
                data, addr = sock.recvfrom(4096)
                if len(data) <= 4:
                    continue

                audio_pcm = data[4:]
                # Ensure even byte count for 16-bit PCM (2 bytes per sample)
                pcm_len = len(audio_pcm)
                if pcm_len < 2:
                    continue
                if pcm_len % 2 != 0:
                    audio_pcm = audio_pcm[:pcm_len - 1]

                audio_array = np.frombuffer(audio_pcm, dtype=np.int16)

                # Calculate RMS Amplitude for VU meter
                if len(audio_array) > 0:
                    norm_float = audio_array.astype(np.float32) / 32768.0
                    rms = np.sqrt(np.mean(norm_float**2))
                    self.latest_amplitude = min(float(rms * 3.5), 1.0)

                    # Apply Volume Adjustment
                    if self.current_volume != 1.0:
                        audio_array = (audio_array * self.current_volume).clip(-32768, 32767).astype(np.int16)

                stream.write(audio_array)

            except socket.timeout:
                continue
            except Exception as e:
                print("UDP Error:", e)
                break

        try:
            stream.stop()
            stream.close()
        except Exception:
            pass
        sock.close()

    def run_http_loop(self, ip, port, pin, device_id):
        url = f"http://{ip}:{port}/stream.wav?pin={pin}"
        
        self.after(0, lambda: self.lbl_status.config(text=f"Status: Connecting to {url}...", fg="#F59E0B"))

        try:
            response = requests.get(url, stream=True, timeout=5)
            if response.status_code != 200:
                self.after(0, lambda: self.on_auth_failed(f"HTTP {response.status_code}: Invalid PIN or Unauthorized"))
                return
        except Exception as e:
            self.after(0, lambda: self.on_auth_failed(f"Connection error: {e}"))
            return

        self.after(0, lambda: self.lbl_status.config(text=f"Status: Streaming Live (HTTP Port {port})", fg="#4ADE80"))

        try:
            stream = sd.OutputStream(
                samplerate=44100,
                channels=1,
                dtype='int16',
                device=device_id
            )
            stream.start()
        except Exception as e:
            self.after(0, lambda: self.on_auth_failed(f"Audio device error: {e}"))
            return

        header_skipped = False
        try:
            for chunk in response.iter_content(chunk_size=2048):
                if self.stop_event.is_set():
                    break
                if not chunk:
                    continue

                if not header_skipped and len(chunk) > 44 and chunk[:4] == b'RIFF':
                    chunk = chunk[44:]
                    header_skipped = True

                audio_array = np.frombuffer(chunk, dtype=np.int16)

                if len(audio_array) > 0:
                    norm_float = audio_array.astype(np.float32) / 32768.0
                    rms = np.sqrt(np.mean(norm_float**2))
                    self.latest_amplitude = min(float(rms * 3.5), 1.0)

                    if self.current_volume != 1.0:
                        audio_array = (audio_array * self.current_volume).clip(-32768, 32767).astype(np.int16)

                stream.write(audio_array)

        except Exception as e:
            print("HTTP Stream error:", e)

        try:
            stream.stop()
            stream.close()
        except Exception:
            pass

    def on_auth_failed(self, error_msg):
        self.stop_receiver()
        messagebox.showerror("Authentication / Connection Error", error_msg)

    def update_visualizer_tick(self):
        # Redraw Canvas VU bar
        self.canvas_vu.delete("all")
        w = self.canvas_vu.winfo_width()
        h = self.canvas_vu.winfo_height()

        if w <= 1:
            w = 480
        if h <= 1:
            h = 24

        # Track background
        self.canvas_vu.create_rectangle(0, 0, w, h, fill="#0F172A", outline="")

        # Active amplitude fill
        fill_w = w * self.latest_amplitude
        if fill_w > 0:
            fill_color = "#EF4444" if self.latest_amplitude > 0.85 else ("#F59E0B" if self.latest_amplitude > 0.6 else "#10B981")
            self.canvas_vu.create_rectangle(0, 0, fill_w, h, fill=fill_color, outline="")

        # Decay amplitude slightly for smooth animation
        self.latest_amplitude = max(0.0, self.latest_amplitude * 0.88)

        self.after(40, self.update_visualizer_tick)


if __name__ == "__main__":
    app = MicRelayGuiReceiver()
    app.mainloop()
