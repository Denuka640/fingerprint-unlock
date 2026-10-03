using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using BiometricUnlock.Service.Bluetooth;
using BiometricUnlock.Service.Crypto;
using BiometricUnlock.Service.Ipc;
using BiometricUnlock.Service.Security;

namespace BiometricUnlock.Service;

public class Program
{
    public static async Task Main(string[] args)
    {
        var builder = Host.CreateApplicationBuilder(args);

        // Allow running as a Windows Service if started by SCM
        if (args.Contains("--service") || OperatingSystem.IsWindows())
        {
            builder.Services.AddWindowsService(options =>
            {
                options.ServiceName = "BiometricUnlockService";
            });
        }

        builder.Logging.ClearProviders();
        builder.Logging.AddConsole();
        // File-based logging for diagnostics
        var logDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "BiometricUnlock");
        Directory.CreateDirectory(logDir);
        var logPath = Path.Combine(logDir, "service.log");
        builder.Logging.AddProvider(new FileLoggerProvider(logPath));
        builder.Logging.SetMinimumLevel(LogLevel.Debug);

        // Core singletons
        builder.Services.AddSingleton<CryptoEngine>();
        builder.Services.AddSingleton<DpapiVault>();
        builder.Services.AddSingleton<PipeServer>();
        builder.Services.AddSingleton<BleGattServerManager>();
        builder.Services.AddSingleton<BiometricUnlock.Service.Network.LocalNetworkManager>();

        builder.Services.AddHostedService<BiometricBackgroundWorker>();

        var app = builder.Build();
        await app.RunAsync();
    }
}
