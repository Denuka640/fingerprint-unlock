using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;
using BiometricUnlock.Service.Crypto;
using BiometricUnlock.Service.Security;
using BiometricUnlock.Service.Ipc;

namespace BiometricUnlock.Service.Bluetooth;

public class BleGattServerManager : IAsyncDisposable
{
    public static readonly Guid ServiceUuid = Guid.Parse("7B37A55C-3BF2-4D3E-A59B-51421DA10001");
    public static readonly Guid StatusCharUuid = Guid.Parse("7B37A55C-3BF2-4D3E-A59B-51421DA10002");
    public static readonly Guid ChallengeCharUuid = Guid.Parse("7B37A55C-3BF2-4D3E-A59B-51421DA10003");
    public static readonly Guid AuthResponseCharUuid = Guid.Parse("7B37A55C-3BF2-4D3E-A59B-51421DA10004");
    public static readonly Guid PairingCharUuid = Guid.Parse("7B37A55C-3BF2-4D3E-A59B-51421DA10005");

    private readonly CryptoEngine _crypto;
    private readonly DpapiVault _vault;
    private readonly PipeServer _pipeServer;

    private GattServiceProvider? _serviceProvider;
    private GattLocalCharacteristic? _statusChar;
    private GattLocalCharacteristic? _challengeChar;
    private GattLocalCharacteristic? _authResponseChar;
    private GattLocalCharacteristic? _pairingChar;

    private byte[] _currentChallenge = Array.Empty<byte>();

    public event Action<string>? OnLog;

    public BleGattServerManager(CryptoEngine crypto, DpapiVault vault, PipeServer pipeServer)
    {
        _crypto = crypto;
        _vault = vault;
        _pipeServer = pipeServer;
    }

    public async Task<bool> StartAsync()
    {
        try
        {
            var result = await GattServiceProvider.CreateAsync(ServiceUuid);
            if (result.Error != Windows.Devices.Bluetooth.BluetoothError.Success)
            {
                OnLog?.Invoke($"Failed to create GATT Service: {result.Error}");
                return false;
            }

            _serviceProvider = result.ServiceProvider;

            // 1. Status Characteristic (Read, Notify)
            var statusParams = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read | GattCharacteristicProperties.Notify,
                ReadProtectionLevel = GattProtectionLevel.Plain
            };
            var statusResult = await _serviceProvider.Service.CreateCharacteristicAsync(StatusCharUuid, statusParams);
            _statusChar = statusResult.Characteristic;
            _statusChar.ReadRequested += StatusChar_ReadRequested;

            // 2. Challenge Characteristic (Read, Notify)
            var challengeParams = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read | GattCharacteristicProperties.Notify,
                ReadProtectionLevel = GattProtectionLevel.Plain
            };
            var challengeResult = await _serviceProvider.Service.CreateCharacteristicAsync(ChallengeCharUuid, challengeParams);
            _challengeChar = challengeResult.Characteristic;
            _challengeChar.ReadRequested += ChallengeChar_ReadRequested;

            // 3. Auth Response Characteristic (Write)
            var authParams = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.Plain
            };
            var authResult = await _serviceProvider.Service.CreateCharacteristicAsync(AuthResponseCharUuid, authParams);
            _authResponseChar = authResult.Characteristic;
            _authResponseChar.WriteRequested += AuthResponseChar_WriteRequested;

            // 4. Pairing Characteristic (Write, Indicate)
            var pairingParams = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.Indicate,
                WriteProtectionLevel = GattProtectionLevel.Plain
            };
            var pairingResult = await _serviceProvider.Service.CreateCharacteristicAsync(PairingCharUuid, pairingParams);
            _pairingChar = pairingResult.Characteristic;
            _pairingChar.WriteRequested += PairingChar_WriteRequested;

            // Start Advertising
            var advParams = new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true
            };
            _serviceProvider.StartAdvertising(advParams);

            OnLog?.Invoke("BLE GATT Server started advertising successfully.");
            await _pipeServer.BroadcastStatusAsync("Bluetooth Ready: Waiting for phone...");
            return true;
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"BLE Server initialization error: {ex.Message}");
            return false;
        }
    }

    private void StatusChar_ReadRequested(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        Task.Run(async () =>
        {
            try
            {
                var request = await args.GetRequestAsync();
                var writer = new DataWriter();
                writer.WriteString(_pipeServer.CurrentStatus);
                request.RespondWithValue(writer.DetachBuffer());
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    private void ChallengeChar_ReadRequested(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        Task.Run(async () =>
        {
            try
            {
                var request = await args.GetRequestAsync();
                var (rawPayload, _) = _crypto.GenerateChallenge();
                _currentChallenge = rawPayload;

                var writer = new DataWriter();
                writer.WriteBytes(_currentChallenge);
                request.RespondWithValue(writer.DetachBuffer());

                OnLog?.Invoke("Fresh challenge generated and delivered to phone.");
                await _pipeServer.BroadcastStatusAsync("Phone Connected: Touch fingerprint sensor...");
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    private void AuthResponseChar_WriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        Task.Run(async () =>
        {
            try
            {
                var request = await args.GetRequestAsync();
                var reader = DataReader.FromBuffer(request.Value);
                byte[] data = new byte[request.Value.Length];
                reader.ReadBytes(data);

                // Protocol format: [DeviceIdLength:1][DeviceId:utf8][SignatureLength:2][Signature:bytes][ChallengeLength:2][Challenge:bytes]
                if (data.Length < 10)
                {
                    if (request.Option == GattWriteOption.WriteWithResponse)
                        request.RespondWithProtocolError(GattProtocolError.InvalidAttributeValueLength);
                    return;
                }

                using var ms = new MemoryStream(data);
                using var br = new BinaryReader(ms);
                byte devIdLen = br.ReadByte();
                string deviceId = System.Text.Encoding.UTF8.GetString(br.ReadBytes(devIdLen));
                ushort sigLen = br.ReadUInt16();
                byte[] signature = br.ReadBytes(sigLen);
                ushort chalLen = br.ReadUInt16();
                byte[] receivedChallenge = br.ReadBytes(chalLen);

                // 1. Verify Challenge Freshness & Nonce
                if (!_crypto.ValidateChallenge(receivedChallenge))
                {
                    OnLog?.Invoke("Auth Rejected: Challenge expired or replay attack detected.");
                    await _pipeServer.BroadcastStatusAsync("Auth Failed: Expired challenge");
                    if (request.Option == GattWriteOption.WriteWithResponse)
                        request.RespondWithProtocolError(GattProtocolError.UnlikelyError);
                    return;
                }

                // 2. Lookup Paired Device
                var pairedDev = _vault.FindPairedDevice(deviceId);
                if (pairedDev == null)
                {
                    OnLog?.Invoke($"Auth Rejected: Unknown device {deviceId}.");
                    await _pipeServer.BroadcastStatusAsync("Auth Failed: Unknown phone");
                    if (request.Option == GattWriteOption.WriteWithResponse)
                        request.RespondWithProtocolError(GattProtocolError.InsufficientAuthorization);
                    return;
                }

                // 3. Verify Hardware Biometric Signature
                byte[] phonePubKey = Convert.FromBase64String(pairedDev.PublicKeyBase64);
                bool valid = _crypto.VerifySignature(receivedChallenge, signature, phonePubKey);

                if (!valid)
                {
                    OnLog?.Invoke("Auth Rejected: Invalid biometric signature.");
                    await _pipeServer.BroadcastStatusAsync("Auth Failed: Bad signature");
                    if (request.Option == GattWriteOption.WriteWithResponse)
                        request.RespondWithProtocolError(GattProtocolError.InsufficientAuthentication);
                    return;
                }

                // 4. Success -> Fetch Credentials and Trigger Windows Unlock
                var creds = _vault.GetUserCredentials();
                if (creds == null)
                {
                    OnLog?.Invoke("Auth Error: No credentials registered in Windows vault.");
                    await _pipeServer.BroadcastStatusAsync("Setup needed: No credentials stored");
                    return;
                }

                OnLog?.Invoke($"Fingerprint Verified successfully for phone '{pairedDev.DeviceName}'! Unlocking Windows...");
                await _pipeServer.BroadcastStatusAsync("Biometric verified: Unlocking Windows...");
                await _pipeServer.SendUnlockTriggerAsync(creds.Value.Username, creds.Value.Domain, creds.Value.Password);

                if (request.Option == GattWriteOption.WriteWithResponse)
                {
                    request.Respond();
                }
            }
            catch (Exception ex)
            {
                OnLog?.Invoke($"Error processing auth write: {ex.Message}");
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    private void PairingChar_WriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        Task.Run(async () =>
        {
            try
            {
                var request = await args.GetRequestAsync();
                var reader = DataReader.FromBuffer(request.Value);
                byte[] data = new byte[request.Value.Length];
                reader.ReadBytes(data);

                // Pairing format: [DeviceIdLength:1][DeviceId:utf8][DeviceNameLength:1][DeviceName:utf8][PubKeyLength:2][PubKey:Der]
                using var ms = new MemoryStream(data);
                using var br = new BinaryReader(ms);
                byte devIdLen = br.ReadByte();
                string deviceId = System.Text.Encoding.UTF8.GetString(br.ReadBytes(devIdLen));
                byte devNameLen = br.ReadByte();
                string deviceName = System.Text.Encoding.UTF8.GetString(br.ReadBytes(devNameLen));
                ushort pubKeyLen = br.ReadUInt16();
                byte[] pubKey = br.ReadBytes(pubKeyLen);

                _vault.AddPairedDevice(deviceId, deviceName, pubKey);
                OnLog?.Invoke($"Successfully paired phone '{deviceName}' ({deviceId})!");

                if (request.Option == GattWriteOption.WriteWithResponse)
                {
                    request.Respond();
                }
            }
            catch (Exception ex)
            {
                OnLog?.Invoke($"Pairing error: {ex.Message}");
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    public ValueTask DisposeAsync()
    {
        if (_serviceProvider != null)
        {
            _serviceProvider.StopAdvertising();
            _serviceProvider = null;
        }
        return ValueTask.CompletedTask;
    }
}
