#!/usr/bin/env python3
"""
PhoneMic Wireless - PC Audio Receiver Script
Listens for live low-latency microphone audio streamed from the Android PhoneMic app over UDP or HTTP,
and plays it to selected PC speakers or a Virtual Audio Cable (VB-Audio Cable).
"""

import sys
import socket
import struct
import argparse
import time

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

def run_udp_receiver(port=50005, sample_rate=44100, channels=1, device_id=None):
    try:
        import sounddevice as sd
        import numpy as np
    except ImportError:
        print("[ERROR] Please install dependencies: pip install sounddevice numpy")
        sys.exit(1)

    print(f"[*] Starting PhoneMic UDP Receiver on port {port}...")
    print(f"[*] Audio Settings: {sample_rate}Hz, {channels} Channel(s), PCM 16-bit")

    # Create UDP Socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", port))
    sock.settimeout(2.0)

    # Open SoundDevice OutputStream
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

    print("[✔] Receiver is ready! Waiting for live audio packets from Android app...\n")

    packets_received = 0
    last_seq = -1

    try:
        while True:
            try:
                data, addr = sock.recvfrom(4096)
                if len(data) <= 4:
                    continue

                # Header: 4-byte sequence number
                seq_num = struct.unpack(">I", data[:4])[0]
                audio_pcm = data[4:]

                # Check for lost packets
                if last_seq != -1 and seq_num != last_seq + 1:
                    lost = seq_num - last_seq - 1
                    if lost > 0 and lost < 100:
                        pass # Small packet loss drop

                last_seq = seq_num
                packets_received += 1

                # Convert raw PCM bytes to int16 numpy array
                audio_array = np.frombuffer(audio_pcm, dtype=np.int16)
                stream.write(audio_array)

                if packets_received % 100 == 0:
                    print(f"\r[LIVE] Connected to {addr[0]} | Packets received: {packets_received}", end="", flush=True)

            except socket.timeout:
                continue

    except KeyboardInterrupt:
        print("\n\n[*] Stopping receiver...")
    finally:
        stream.stop()
        stream.close()
        sock.close()
        print("[✔] Receiver closed.")

def run_http_receiver(url, device_id=None):
    try:
        import requests
        import sounddevice as sd
        import numpy as np
    except ImportError:
        print("[ERROR] Please install dependencies: pip install requests sounddevice numpy")
        sys.exit(1)

    print(f"[*] Connecting to HTTP Stream: {url}")
    try:
        response = requests.get(url, stream=True, timeout=5)
        if response.status_code != 200:
            print(f"[ERROR] HTTP Stream returned status code: {response.status_code}")
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
        # Skip 44-byte WAV header on initial chunk if present
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
    parser = argparse.ArgumentParser(description="PhoneMic PC Audio Receiver")
    parser.add_argument("--mode", choices=["udp", "http"], default="udp", help="Receiver mode: 'udp' (low latency) or 'http'")
    parser.add_argument("--port", type=int, default=50005, help="UDP port (default: 50005)")
    parser.add_argument("--url", type=str, default="http://192.168.1.100:8080/stream.wav", help="HTTP stream URL")
    parser.add_argument("--device", type=int, default=None, help="Audio output device ID (run --list-devices to view)")
    parser.add_argument("--list-devices", action="store_true", help="List available PC audio output devices")

    args = parser.parse_args()

    if args.list_devices:
        list_audio_devices()
        sys.exit(0)

    if args.mode == "udp":
        run_udp_receiver(port=args.port, device_id=args.device)
    else:
        run_http_receiver(url=args.url, device_id=args.device)
