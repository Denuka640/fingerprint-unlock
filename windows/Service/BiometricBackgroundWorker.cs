using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using BiometricUnlock.Service.Bluetooth;
using BiometricUnlock.Service.Crypto;
using BiometricUnlock.Service.Ipc;
using BiometricUnlock.Service.Security;

namespace BiometricUnlock.Service;

public class BiometricBackgroundWorker : BackgroundService
{
    private readonly ILogger<BiometricBackgroundWorker> _logger;
    private readonly PipeServer _pipeServer;
    private readonly BleGattServerManager _bleServer;
    private readonly BiometricUnlock.Service.Network.LocalNetworkManager _networkServer;

    public BiometricBackgroundWorker(
        ILogger<BiometricBackgroundWorker> logger,
        PipeServer pipeServer,
        BleGattServerManager bleServer,
        BiometricUnlock.Service.Network.LocalNetworkManager networkServer)
    {
        _logger = logger;
        _pipeServer = pipeServer;
        _bleServer = bleServer;
        _networkServer = networkServer;

        _bleServer.OnLog += msg => _logger.LogInformation("[BLE] {Message}", msg);
        _networkServer.OnLog += msg => _logger.LogInformation("[WiFi] {Message}", msg);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("Biometric Unlock Windows Service starting...");
        
        // Start Named Pipe Server for Credential Provider
        _pipeServer.Start();
        _logger.LogInformation("IPC Named Pipe Server listening on \\\\.\\pipe\\BiometricUnlockPipe");

        // Start Bluetooth GATT Server
        bool bleStarted = await _bleServer.StartAsync();
        if (!bleStarted)
        {
            _logger.LogWarning("BLE GATT Server could not start. Please ensure Bluetooth is enabled on this PC.");
        }

        // Start WiFi / Local Network Server
        _networkServer.Start();

        while (!stoppingToken.IsCancellationRequested)
        {
            await Task.Delay(5000, stoppingToken);
        }

        await _bleServer.DisposeAsync();
        await _networkServer.DisposeAsync();
        _pipeServer.Dispose();
        _logger.LogInformation("Biometric Unlock Windows Service stopped.");
    }
}
