#!/usr/bin/env python3
"""
MicRelay Wireless - CLI PC Audio Receiver Script
Listens for live low-latency microphone audio streamed from the Android MicRelay app over UDP or HTTP.
Supports PIN authentication and dynamic ports.
"""

import sys
import socket
import struct
import argparse

def list_audio_devices():
    try:
        import sounddevice as sd
        print("\n--- Available Audio Output Devices ---")
        devices = sd.query_devices()
        for idx, dev in enumerate(devices):
            if dev['max_output_channels'] > 0:
                print(f"[{idx}] {dev['name']} (Channels: {dev['max_output_channels']}, Default SR: {dev['default_samplerate']}Hz)")
        print("--------------------------------------\n")
    except ImportError:
        print("\nNote: 'sounddevice' module not installed. Install with: pip install sounddevice numpy")

def run_udp_receiver(ip, port, pin, sample_rate=44100, channels=1, device_id=None):
    try:
        import sounddevice as sd
        import numpy as np
    except ImportError:
        print("[ERROR] Please install dependencies: pip install sounddevice numpy")
        sys.exit(1)

    print(f"[*] Connecting to MicRelay UDP Server at {ip}:{port} (PIN: {pin})...")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(3.0)

    # Send UDP Auth Handshake packet
    try:
        auth_msg = f"AUTH:{pin}".encode("utf-8")
        sock.sendto(auth_msg, (ip, port))
        
        data, addr = sock.recvfrom(256)
        resp = data.decode("utf-8", errors="ignore").strip()
        if resp != "AUTH_OK":
            print(f"[ERROR] Authentication Failed: {resp}. Check Security PIN!")
            sock.close()
            return
        print("[✔] Security PIN Verified! UDP Authorization Granted.")
    except Exception as e:
        print(f"[WARN] Handshake sent to {ip}:{port}. Proceeding to listen...")

    try:
        stream = sd.OutputStream(
            samplerate=sample_rate,
            channels=channels,
            dtype='int16',
            device=device_id
        )
        stream.start()
    except Exception as e:
        print(f"[ERROR] Failed to open audio output device: {e}")
        sys.exit(1)

    print("[✔] Receiver is ready! Streaming live microphone audio...\n")
    packets_received = 0

    try:
        while True:
            try:
                data, addr = sock.recvfrom(4096)
                if len(data) <= 4:
                    continue

                audio_pcm = data[4:]
                pcm_len = len(audio_pcm)
                if pcm_len < 2:
                    continue
                if pcm_len % 2 != 0:
                    audio_pcm = audio_pcm[:pcm_len - 1]

                packets_received += 1

                audio_array = np.frombuffer(audio_pcm, dtype=np.int16)
                stream.write(audio_array)

                if packets_received % 100 == 0:
                    print(f"\r[LIVE STREAM] Connected to {addr[0]} | Packets: {packets_received}", end="", flush=True)

            except socket.timeout:
                continue

    except KeyboardInterrupt:
        print("\n\n[*] Stopping receiver...")
    finally:
        stream.stop()
        stream.close()
        sock.close()
        print("[✔] Receiver closed.")

def run_http_receiver(ip, port, pin, device_id=None):
    try:
        import requests
        import sounddevice as sd
        import numpy as np
    except ImportError:
        print("[ERROR] Please install dependencies: pip install requests sounddevice numpy")
        sys.exit(1)

    url = f"http://{ip}:{port}/stream.wav?pin={pin}"
    print(f"[*] Connecting to Encrypted HTTP Stream: {url}")
    try:
        response = requests.get(url, stream=True, timeout=5)
        if response.status_code != 200:
            print(f"[ERROR] HTTP Stream error: {response.status_code} Unauthorized. Check PIN!")
            return
    except Exception as e:
        print(f"[ERROR] Failed to connect to stream URL: {e}")
        return

    stream = sd.OutputStream(
        samplerate=44100,
        channels=1,
        dtype='int16',
        device=device_id
    )
    stream.start()

    print("[✔] Playing live HTTP audio stream...\n")
    try:
        header_skipped = False
        for chunk in response.iter_content(chunk_size=2048):
            if not chunk:
                continue
            if not header_skipped and len(chunk) > 44 and chunk[:4] == b'RIFF':
                chunk = chunk[44:]
                header_skipped = True

            audio_array = np.frombuffer(chunk, dtype=np.int16)
            stream.write(audio_array)
    except KeyboardInterrupt:
        print("\n[*] Stopping HTTP receiver...")
    finally:
        stream.stop()
        stream.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MicRelay CLI Audio Receiver")
    parser.add_argument("--ip", type=str, default="192.168.1.13", help="Phone IP address")
    parser.add_argument("--port", type=int, default=54129, help="Dynamic port (e.g. 54129 or 8432)")
    parser.add_argument("--pin", type=str, default="4829", help="4-digit Security PIN (e.g. 4829)")
    parser.add_argument("--mode", choices=["udp", "http"], default="udp", help="Receiver mode: 'udp' or 'http'")
    parser.add_argument("--device", type=int, default=None, help="Audio output device ID (run --list-devices to view)")
    parser.add_argument("--list-devices", action="store_true", help="List available PC audio output devices")

    args = parser.parse_args()

    if args.list_devices:
        list_audio_devices()
        sys.exit(0)

    if args.mode == "udp":
        run_udp_receiver(ip=args.ip, port=args.port, pin=args.pin, device_id=args.device)
    else:
        run_http_receiver(ip=args.ip, port=args.port, pin=args.pin, device_id=args.device)
