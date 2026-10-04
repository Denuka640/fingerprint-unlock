using System.Net;
using System.Net.Sockets;
using BiometricUnlock.Service.Crypto;
using BiometricUnlock.Service.Ipc;
using BiometricUnlock.Service.Security;

namespace BiometricUnlock.Service.Network;

public class LocalNetworkManager : IAsyncDisposable
{
    private readonly CryptoEngine _crypto;
    private readonly DpapiVault _vault;
    private readonly PipeServer _pipeServer;
    private readonly HttpListener _listener;
    private UdpClient? _udpListener;
    private CancellationTokenSource? _cts;
    private Task? _listenTask;
    private Task? _udpListenTask;

    private byte[] _currentChallenge = Array.Empty<byte>();

    public event Action<string>? OnLog;

    // Java DataOutputStream.writeShort() is big-endian, C# BinaryReader.ReadUInt16() is little-endian.
    private static ushort ReadUInt16BE(BinaryReader br)
    {
        byte hi = br.ReadByte();
        byte lo = br.ReadByte();
        return (ushort)((hi << 8) | lo);
    }

    public LocalNetworkManager(CryptoEngine crypto, DpapiVault vault, PipeServer pipeServer)
    {
        _crypto = crypto;
        _vault = vault;
        _pipeServer = pipeServer;
        
        _listener = new HttpListener();
        // Listen on all interfaces, port 9898
        _listener.Prefixes.Add("http://+:9898/");
    }

    public void Start()
    {
        try
        {
            _listener.Start();
            _cts = new CancellationTokenSource();
            _listenTask = Task.Run(() => ListenLoopAsync(_cts.Token));
            
            StartUdpDiscoveryListener(_cts.Token);

            OnLog?.Invoke("Local Network (WiFi) Server started on TCP 9898 & UDP 9899.");
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"Failed to start HTTP/UDP server: {ex.Message}");
            // To bind to +:9898 without admin, we need urlacl, but running as a Windows Service (SYSTEM) bypasses this.
        }
    }

    private void StartUdpDiscoveryListener(CancellationToken ct)
    {
        _udpListenTask = Task.Run(async () =>
        {
            try
            {
                _udpListener = new UdpClient();
                _udpListener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _udpListener.Client.Bind(new IPEndPoint(IPAddress.Any, 9899));

                while (!ct.IsCancellationRequested)
                {
                    var receiveResult = await _udpListener.ReceiveAsync(ct);
                    string message = System.Text.Encoding.UTF8.GetString(receiveResult.Buffer);
                    
                    if (message == "DISCOVER_FINGERPRINT_PC")
                    {
                        var responseData = new
                        {
                            MachineName = Environment.MachineName,
                            Port = 9898
                        };
                        string json = System.Text.Json.JsonSerializer.Serialize(responseData);
                        byte[] responseBytes = System.Text.Encoding.UTF8.GetBytes(json);

                        await _udpListener.SendAsync(responseBytes, responseBytes.Length, receiveResult.RemoteEndPoint);
                        OnLog?.Invoke($"[WiFi Discovery] Responded to discovery ping from {receiveResult.RemoteEndPoint.Address}");
                    }
                }
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                OnLog?.Invoke($"UDP Discovery Listener warning: {ex.Message}");
            }
        }, ct);
    }

    private async Task ListenLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var context = await _listener.GetContextAsync();
                _ = Task.Run(() => ProcessRequestAsync(context), ct);
            }
            catch (HttpListenerException)
            {
                break; // Listener stopped
            }
        }
    }

    private async Task ProcessRequestAsync(HttpListenerContext context)
    {
        var req = context.Request;
        var res = context.Response;

        try
        {
            if (req.HttpMethod == "GET" && req.Url?.AbsolutePath == "/challenge")
            {
                var (rawPayload, _) = _crypto.GenerateChallenge();
                _currentChallenge = rawPayload;
                
                res.ContentType = "application/octet-stream";
                res.ContentLength64 = _currentChallenge.Length;
                await res.OutputStream.WriteAsync(_currentChallenge, 0, _currentChallenge.Length);
                
                OnLog?.Invoke("Fresh challenge requested via WiFi.");
                await _pipeServer.BroadcastStatusAsync("WiFi Connected: Touch fingerprint sensor...");
            }
            else if (req.HttpMethod == "POST" && req.Url?.AbsolutePath == "/unlock")
            {
                using var ms = new MemoryStream();
                await req.InputStream.CopyToAsync(ms);
                var data = ms.ToArray();

                if (data.Length < 10)
                {
                    res.StatusCode = 400;
                    return;
                }

                using var readerMs = new MemoryStream(data);
                using var br = new BinaryReader(readerMs);
                
                byte devIdLen = br.ReadByte();
                string deviceId = System.Text.Encoding.UTF8.GetString(br.ReadBytes(devIdLen));
                
                ushort sigLen = ReadUInt16BE(br);
                byte[] signature = br.ReadBytes(sigLen);
                
                ushort chalLen = ReadUInt16BE(br);
                byte[] receivedChallenge = br.ReadBytes(chalLen);

                if (!_crypto.ValidateChallenge(receivedChallenge))
                {
                    OnLog?.Invoke("Auth Rejected (WiFi): Challenge expired or replay attack detected.");
                    await _pipeServer.BroadcastStatusAsync("Auth Failed: Expired challenge");
                    res.StatusCode = 401;
                    return;
                }

                var pairedDev = _vault.FindPairedDevice(deviceId);
                if (pairedDev == null)
                {
                    OnLog?.Invoke($"Auth Rejected (WiFi): Unknown device {deviceId}.");
                    await _pipeServer.BroadcastStatusAsync("Auth Failed: Unknown phone");
                    res.StatusCode = 403;
                    return;
                }

                byte[] phonePubKey = Convert.FromBase64String(pairedDev.PublicKeyBase64);
                bool valid = _crypto.VerifySignature(receivedChallenge, signature, phonePubKey);

                if (!valid)
                {
                    OnLog?.Invoke("Auth Rejected (WiFi): Invalid biometric signature.");
                    await _pipeServer.BroadcastStatusAsync("Auth Failed: Bad signature");
                    res.StatusCode = 401;
                    return;
                }

                var creds = _vault.GetUserCredentials();
                if (creds == null)
                {
                    OnLog?.Invoke("Auth Error (WiFi): No credentials registered in Windows vault.");
                    await _pipeServer.BroadcastStatusAsync("Setup needed: No credentials stored");
                    res.StatusCode = 500;
                    return;
                }

                OnLog?.Invoke($"Fingerprint Verified (WiFi) for phone '{pairedDev.DeviceName}'! Unlocking Windows...");
                await _pipeServer.BroadcastStatusAsync("Biometric verified (WiFi): Unlocking Windows...");
                await _pipeServer.SendUnlockTriggerAsync(creds.Value.Username, creds.Value.Domain, creds.Value.Password);

                res.StatusCode = 200;
            }
            else if (req.HttpMethod == "POST" && req.Url?.AbsolutePath == "/pair")
            {
                using var ms = new MemoryStream();
                await req.InputStream.CopyToAsync(ms);
                var data = ms.ToArray();

                using var readerMs = new MemoryStream(data);
                using var br = new BinaryReader(readerMs);
                
                byte devIdLen = br.ReadByte();
                string deviceId = System.Text.Encoding.UTF8.GetString(br.ReadBytes(devIdLen));
                
                byte devNameLen = br.ReadByte();
                string deviceName = System.Text.Encoding.UTF8.GetString(br.ReadBytes(devNameLen));
                
                ushort pubKeyLen = ReadUInt16BE(br);
                byte[] pubKey = br.ReadBytes(pubKeyLen);

                _vault.AddPairedDevice(deviceId, deviceName, pubKey);
                OnLog?.Invoke($"Successfully paired phone '{deviceName}' ({deviceId}) via WiFi!");

                res.StatusCode = 200;
            }
            else
            {
                res.StatusCode = 404;
            }
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"WiFi request error: {ex.Message}");
            res.StatusCode = 500;
        }
        finally
        {
            res.Close();
        }
    }

    public ValueTask DisposeAsync()
    {
        _cts?.Cancel();
        try { _udpListener?.Close(); } catch { }
        try { _udpListener?.Dispose(); } catch { }
        _listener.Stop();
        _listener.Close();
        return ValueTask.CompletedTask;
    }
}
