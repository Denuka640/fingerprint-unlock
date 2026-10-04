# 📱 Biometric Fingerprint Unlock for Windows

Unlock your Windows PC seamlessly using your Android smartphone's fingerprint scanner. This project consists of an Android application (acting as the authenticator) and a Windows service/credential provider (acting as the lock screen receiver).

## 🚀 Features
- **True Auto-Unlock**: No need to touch your mouse or keyboard! Simply authenticate on your phone and Windows unlocks instantly.
- **Hardware-backed Security**: Private keys are stored in your Android device's hardware keystore (TEE).
- **Secure Encrypted Pairing**: ECDH P-256 Key Exchange and AES-256-GCM encryption ensure your credentials are never intercepted.
- **Zero Internet Required**: Operates entirely over local Bluetooth Low Energy (BLE).
- **DPAPI Credential Vault**: Windows credentials never leave your machine; they are encrypted using Windows Data Protection API (DPAPI) and unlocked via your phone's biometric signature.

---

## 🛠️ Components

1. **Mobile App (Android)**: Connects to the PC via BLE, receives a cryptographic challenge, signs it using the hardware keystore (upon successful biometric prompt), and sends the response.
2. **Background Service (Windows)**: A `.NET 10` Windows Service that acts as a BLE GATT Server. It verifies the phone's signature against the paired public key.
3. **Setup App (Windows)**: A WPF application used for pairing the phone (via QR Code) and securely saving your Windows credentials.
4. **Credential Provider (Windows)**: A custom C++ COM DLL injected into `LogonUI.exe` that listens for unlock signals from the Background Service and performs the interactive logon.

---

## 📖 Installation Instructions

### 1. Windows Installation (The Receiver)

We've provided a unified installer script that handles building and registering all components automatically.

1. Open **PowerShell as Administrator**.
2. Navigate to the `windows` directory:
   ```powershell
   cd "D:\visual studio projects\fingerprint unlock\windows"
   ```
3. Run the installer script:
   ```powershell
   .\install.ps1
   ```
4. The installer will build the projects, register the background service, register the credential provider, and create shortcuts on your Desktop and Start Menu.

### 2. Android Installation (The Key)

1. Build the APK using Android Studio or Gradle:
   ```bash
   cd mobile/android
   gradlew assembleDebug
   ```
2. Install the generated APK on your Android phone.
3. Ensure Bluetooth and Location permissions are granted.

---

## 🔗 Setup & Pairing Guide

1. Open **Biometric Setup** from your Desktop shortcut on Windows.
2. Enter your Windows Username, Domain/Machine Name, and Password. Click **Save to Secure DPAPI Vault**.
3. On your Android phone, open the **Fingerprint Unlock** app.
4. Tap the **Pair** button in the mobile app.
5. Scan the QR code displayed on the Windows Setup App.
6. The app will confirm pairing and the PC will save the phone's public key.

---

## 🔓 Usage (How to unlock)

1. Lock your Windows PC by pressing `Win + L`.
2. Ensure your phone's Bluetooth is on and open the mobile app.
3. The app will automatically connect to your locked PC and present a fingerprint prompt.
4. Scan your fingerprint on your phone.
5. Watch your Windows PC unlock automatically!

---

## 🧹 Uninstallation

To completely remove the service, credential provider, and shortcuts:
1. Open **PowerShell as Administrator**.
2. Run the uninstaller:
   ```powershell
   cd "D:\visual studio projects\fingerprint unlock\windows"
   .\uninstall.ps1
   ```
3. It will prompt you if you wish to wipe the securely stored credentials.

---
*Designed by Denuka*

