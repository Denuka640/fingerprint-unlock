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
        builder.Logging.SetMinimumLevel(LogLevel.Information);

        // Core singletons
        builder.Services.AddSingleton<CryptoEngine>();
        builder.Services.AddSingleton<DpapiVault>();
        builder.Services.AddSingleton<PipeServer>();
        builder.Services.AddSingleton<BleGattServerManager>();

        builder.Services.AddHostedService<BiometricBackgroundWorker>();

        var app = builder.Build();
        await app.RunAsync();
    }
}
