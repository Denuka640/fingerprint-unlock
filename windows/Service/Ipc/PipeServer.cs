using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;

namespace BiometricUnlock.Service.Ipc;

public enum PipeCommand : uint
{
    QueryStatus = 1,
    StatusUpdate = 2,
    UnlockTriggered = 3,
    Acknowledge = 4
}

[StructLayout(LayoutKind.Sequential, Pack = 1, CharSet = CharSet.Unicode)]
public struct PipeMessage
{
    public PipeCommand Command;
    public uint StatusLength;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
    public string StatusMessage;
    public uint UsernameLength;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
    public string Username;
    public uint DomainLength;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
    public string Domain;
    public uint PasswordLength;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
    public string Password;
}

public class PipeServer : IDisposable
{
    private const string PipeName = "BiometricUnlockPipe";
    private readonly CancellationTokenSource _cts = new();
    private Task? _serverTask;
    private readonly List<NamedPipeServerStream> _activeClients = new();
    private readonly object _clientsLock = new();

    public string CurrentStatus { get; set; } = "Waiting for phone connection...";

    public void Start()
    {
        _serverTask = Task.Run(() => ListenLoopAsync(_cts.Token));
    }

    private async Task ListenLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var pipeSecurity = new PipeSecurity();
                // Security Audit Fix: Only allow LocalSystem and Administrators to connect to the pipe.
                // LogonUI.exe runs as SYSTEM. This prevents local malware from stealing the plaintext password.
                var sidSystem = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
                var sidAdmins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
                pipeSecurity.AddAccessRule(new PipeAccessRule(sidSystem, PipeAccessRights.ReadWrite | PipeAccessRights.CreateNewInstance, AccessControlType.Allow));
                pipeSecurity.AddAccessRule(new PipeAccessRule(sidAdmins, PipeAccessRights.ReadWrite | PipeAccessRights.CreateNewInstance, AccessControlType.Allow));

                var serverStream = NamedPipeServerStreamAcl.Create(
                    PipeName,
                    PipeDirection.InOut,
                    NamedPipeServerStream.MaxAllowedServerInstances,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous,
                    4096,
                    4096,
                    pipeSecurity
                );

                await serverStream.WaitForConnectionAsync(ct);

                lock (_clientsLock)
                {
                    _activeClients.Add(serverStream);
                }

                _ = Task.Run(() => HandleClientAsync(serverStream, ct), ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception)
            {
                await Task.Delay(1000, ct);
            }
        }
    }

    private async Task HandleClientAsync(NamedPipeServerStream stream, CancellationToken ct)
    {
        byte[] buffer = new byte[Marshal.SizeOf<PipeMessage>()];

        try
        {
            while (stream.IsConnected && !ct.IsCancellationRequested)
            {
                int bytesRead = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), ct);
                if (bytesRead == 0) break;

                var msg = MemoryMarshal.Read<PipeMessage>(buffer);
                if (msg.Command == PipeCommand.QueryStatus)
                {
                    var resp = new PipeMessage
                    {
                        Command = PipeCommand.StatusUpdate,
                        StatusLength = (uint)CurrentStatus.Length,
                        StatusMessage = CurrentStatus
                    };

                    byte[] respBytes = new byte[Marshal.SizeOf<PipeMessage>()];
                    MemoryMarshal.Write(respBytes, in resp);
                    await stream.WriteAsync(respBytes, ct);
                    await stream.FlushAsync(ct);
                }
            }
        }
        catch
        {
            // Client disconnected
        }
        finally
        {
            lock (_clientsLock)
            {
                _activeClients.Remove(stream);
            }
            stream.Dispose();
        }
    }

    public async Task BroadcastStatusAsync(string status)
    {
        CurrentStatus = status;
        var msg = new PipeMessage
        {
            Command = PipeCommand.StatusUpdate,
            StatusLength = (uint)status.Length,
            StatusMessage = status
        };

        byte[] msgBytes = new byte[Marshal.SizeOf<PipeMessage>()];
        MemoryMarshal.Write(msgBytes, in msg);

        List<NamedPipeServerStream> clients;
        lock (_clientsLock)
        {
            clients = _activeClients.ToList();
        }

        foreach (var client in clients)
        {
            try
            {
                if (client.IsConnected)
                {
                    await client.WriteAsync(msgBytes);
                    await client.FlushAsync();
                }
            }
            catch { }
        }
    }

    public async Task SendUnlockTriggerAsync(string username, string domain, string password)
    {
        var msg = new PipeMessage
        {
            Command = PipeCommand.UnlockTriggered,
            StatusLength = 9,
            StatusMessage = "Unlocking",
            UsernameLength = (uint)username.Length,
            Username = username,
            DomainLength = (uint)domain.Length,
            Domain = domain,
            PasswordLength = (uint)password.Length,
            Password = password
        };

        byte[] msgBytes = new byte[Marshal.SizeOf<PipeMessage>()];
        MemoryMarshal.Write(msgBytes, in msg);

        List<NamedPipeServerStream> clients;
        lock (_clientsLock)
        {
            clients = _activeClients.ToList();
        }

        foreach (var client in clients)
        {
            try
            {
                if (client.IsConnected)
                {
                    await client.WriteAsync(msgBytes);
                    await client.FlushAsync();
                }
            }
            catch { }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        lock (_clientsLock)
        {
            foreach (var client in _activeClients)
            {
                try { client.Dispose(); } catch { }
            }
            _activeClients.Clear();
        }
    }
}
