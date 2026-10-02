# Full Stack Architecture & Security Document

## 🏗️ Architecture Overview

The Biometric Fingerprint Unlock system consists of four main components interacting across the mobile and desktop environments.

### 1. Android Mobile App (The Authenticator)
- **Role**: Acts as the physical key and biometric verifier.
- **Key Technologies**: Android BLE (Bluetooth Low Energy) APIs, Android Keystore System, BiometricPrompt API.
- **Workflow**:
  - Scans for the Windows PC's BLE GATT Server.
  - Connects and retrieves a cryptographic **Challenge** (a nonce + timestamp).
  - Prompts the user for a biometric authentication (fingerprint).
  - Uses the Android Hardware-Backed Keystore to sign the Challenge with an ECDSA P-256 private key.
  - Transmits the signed challenge back to the PC via BLE.

### 2. Windows Background Service (The Receiver)
- **Role**: Listens for BLE connections, verifies signatures, and manages the secure vault.
- **Key Technologies**: .NET 9/10, Windows.Devices.Bluetooth, DPAPI.
- **Workflow**:
  - Hosts a BLE GATT Server advertising a specific Service UUID.
  - Generates fresh Challenges to prevent replay attacks.
  - Validates the incoming ECDSA signature against the paired phone's Public Key.
  - If valid, decrypts the Windows Credentials from the DPAPI Vault.
  - Pushes the credentials over a local Named Pipe to the Credential Provider.

### 3. Windows Setup App (The Provisioner)
- **Role**: Allows the user to pair their phone and store their Windows credentials.
- **Key Technologies**: WPF, QRCoder, DPAPI.
- **Workflow**:
  - Accepts the user's Windows username and password.
  - Encrypts them using Windows DPAPI (LocalMachine scope).
  - Generates a QR code containing the PC's machine name and BLE UUIDs.
  - Listens for the phone's pairing payload (Public Key) and saves it to the Vault.

### 4. Windows Credential Provider (The Unlocker)
- **Role**: Injected into `LogonUI.exe` to perform the actual Windows session unlock.
- **Key Technologies**: C++, COM, Windows Credential Provider API.
- **Workflow**:
  - Runs on the Windows Lock Screen.
  - Connects to the Background Service's Named Pipe.
  - Receives the plaintext credentials (Username, Domain, Password) when an unlock is triggered.
  - Packages them into a `KERB_INTERACTIVE_UNLOCK_LOGON` struct and submits them to the Local Security Authority (LSA) to unlock the machine.

---

## 🔒 Security Audit & Threat Model

### 1. BLE Replay Attacks
- **Threat**: An attacker intercepts the BLE transmission and attempts to replay the signature later.
- **Mitigation**: The Background Service generates a fresh, 32-byte cryptographically secure nonce and a UTC timestamp for every connection. The Service caches used nonces and rejects any timestamp older than 15 seconds. Replay attacks are highly infeasible.

### 2. Private Key Extraction
- **Threat**: Malware on the Android device attempts to steal the private key.
- **Mitigation**: The ECDSA P-256 private key is generated inside the Android Trusted Execution Environment (TEE) / Secure Enclave. It is physically impossible to extract. Furthermore, it is configured with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`, meaning the key can *only* be used for a few seconds immediately following a successful biometric scan.

### 3. DPAPI Credential Storage
- **Threat**: A malicious local application attempts to read the saved Windows password.
- **Mitigation/Vulnerability**: The credentials are encrypted using DPAPI with `DataProtectionScope.LocalMachine`. This is necessary because the Setup App (running as the user) and the Background Service (running as `SYSTEM`) both need access.
- **Audit Recommendation**: Ensure the `vault.dat` file has strict NTFS file permissions applied so that only `SYSTEM` and `Administrators` can read it.

### 4. Named Pipe Credential Interception
- **Threat**: A malicious local process connects to the `BiometricUnlockPipe` before the legitimate Lock Screen, stealing the password when the phone unlocks the PC.
- **Vulnerability**: Currently, the pipe uses `WellKnownSidType.WorldSid` (Everyone), which is too permissive.
- **Audit Recommendation**: Restrict the pipe ACLs to `SYSTEM` and `Administrators` only. The Credential Provider runs inside `LogonUI.exe`, which executes as `SYSTEM`.

### 5. Accidental Multiple Installations
- **Threat**: Running `install.ps1` multiple times causing corrupted service states or hanging processes.
- **Mitigation**: The installer gracefully detects existing services, forces them to stop, and unregisters previous DLLs before reinstalling. No duplicate services can be created.
