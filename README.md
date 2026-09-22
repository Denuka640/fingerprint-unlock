# Secure Bluetooth Biometric Phone Unlock for Windows

A complete software solution that enables unlocking your Windows laptop/PC using the fingerprint sensor of your smartphone over Bluetooth Low Energy (BLE), featuring custom lock screen integration and security mitigations against replay attacks, relay attacks, and credential exposure.

---

## Architecture Overview

```
┌───────────────────────────┐                ┌──────────────────────────┐
│   Smartphone (Android)    │                │      Windows 10/11       │
│                           │                │                          │
│  [BiometricPrompt / TEE]  │                │   [Windows Lock Screen]  │
│             │             │                │  (Credential Provider)   │
│    Hardware ECDSA P-256   │                │            ▲             │
│       Signature           │                │            │ Named Pipe  │
│             │             │   BLE GATT     │            ▼             │
│    [BLE Client Manager]   │───────────────>│  [Biometric Windows Svc] │
│                           │ (Encrypted)    │  (DPAPI Vault / Crypto)  │
└───────────────────────────┘                └──────────────────────────┘
```

---

## Security Mitigations & Threat Model

1. **Zero Plaintext Transmission**: Passwords and raw biometric data are never transmitted over Bluetooth.
2. **Hardware Key Isolation**: Android Keystore generates an ECDSA P-256 key pair backed by hardware (StrongBox / TEE) configured with `setUserAuthenticationRequired(true)`. The private key cannot sign anything without physical biometric confirmation.
3. **Replay Attack Defense**: PC generates a cryptographically secure 256-bit random nonce (`BCryptGenRandom`) with a 15-second TTL. Duplicate signatures or expired nonces are discarded.
4. **Relay & Distance Protection**: Signal strength (RSSI) proximity filtering ensures the user's phone is physically close to the laptop.
5. **Windows Credential Protection**: Windows login tokens and paired public keys are encrypted locally using **Windows DPAPI** (`DataProtectionScope.LocalMachine`).

---

## Project Structure

- `windows/CredentialProvider/`: Native 64-bit C++ COM DLL implementing `ICredentialProvider` and `ICredentialProviderCredential2` that renders the custom biometric tile on Windows LogonUI.
- `windows/Service/`: Windows Background Service (.NET 10) managing Bluetooth BLE GATT server, cryptographic challenge validation, DPAPI storage, and IPC named pipes.
- `windows/SetupApp/`: Modern WPF desktop companion app for entering DPAPI credentials, displaying pairing QR codes, adjusting RSSI proximity, and managing paired phones.
- `windows/scripts/`: Administrative PowerShell scripts for registering/unregistering the Credential Provider DLL and installing the Windows Service.
- `mobile/android/`: Complete Android application (Kotlin) with AndroidX `BiometricPrompt`, BLE GATT client, and Keystore hardware binding.

---

## Setup & Quick Start Guide

### 1. Register the Windows Credential Provider (Lock Screen Tile)
Open PowerShell as **Administrator** and run:
```powershell
.\windows\scripts\register_provider.ps1
```
*Note: Your regular password/PIN options remain completely available on the lock screen alongside the biometric tile.*

### 2. Configure Windows Unlock Credentials
Launch the **Biometric Unlock Setup** app:
```powershell
dotnet run --project windows\SetupApp\BiometricSetupApp.csproj
```
1. Enter your Windows Username and Password (saved locally in Windows DPAPI).
2. The app will generate a secure **Pairing QR Code**.

### 3. Install & Start the Windows Service
Run as **Administrator**:
```powershell
.\windows\scripts\install_service.ps1
```
Or run interactively in a console:
```powershell
dotnet run --project windows\Service\BiometricUnlockService.csproj
```

### 4. Pair Your Phone & Unlock
1. Open the **Biometric Unlock** app on your Android smartphone.
2. Tap **Pair PC** and scan the QR code from the Setup App.
3. Lock your PC (`Win + L`).
4. Select the **Phone Fingerprint Unlock** tile.
5. Touch your fingerprint sensor on your phone — Windows will immediately unlock!
