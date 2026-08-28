# PhoneMic PC Receiver Setup Guide

The `receiver.py` Python script allows your PC to receive low-latency live microphone audio from the Android PhoneMic app.

---

## 🚀 Quick Start Instructions

### 1. Install Dependencies
Ensure Python 3.8+ is installed on your Windows PC, then run:
```bash
pip install -r requirements.txt
```

---

### 2. Run the Receiver

#### Option A: Quick Web Browser Receiver (No Python Needed!)
1. Open the PhoneMic app on your phone.
2. Make sure your phone is connected to the same Wi-Fi as your PC.
3. Open any browser (Chrome, Edge, Firefox, Brave) on your PC and navigate to the address shown on the phone screen (e.g., `http://192.168.1.100:8080`).
4. Click **Connect & Play Live Stream**.

---

#### Option B: Low-Latency UDP PC Receiver (Recommended for Discord/OBS/Zoom)
1. In terminal, view available sound output devices:
   ```bash
   python receiver.py --list-devices
   ```
2. Start receiving live audio over UDP:
   ```bash
   python receiver.py --port 50005
   ```

---

### 3. How to use PhoneMic as Microphone Input for Discord / Zoom / OBS

To route the phone microphone into software that expects a **Microphone Input**:
1. Download & Install [VB-Audio Virtual Cable](https://vb-audio.com/Cable/) (Free for Windows).
2. Run `--list-devices` to find the device index of **CABLE Input (VB-Audio Virtual Cable)**.
3. Start the receiver pointing to the VB-Audio Cable device ID (e.g. device `2`):
   ```bash
   python receiver.py --device 2
   ```
4. In Discord / Zoom / OBS / Windows Sound Settings, set your **Microphone Input** to **CABLE Output (VB-Audio Virtual Cable)**!
