using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Media.Imaging;
using QRCoder;
using BiometricUnlock.Service.Bluetooth;
using BiometricUnlock.Service.Ipc;
using BiometricUnlock.Service.Security;

using MessageBox = System.Windows.MessageBox;
using Application = System.Windows.Application;

namespace BiometricUnlock.SetupApp;

public partial class MainWindow : Window
{
    private readonly DpapiVault _vault;
    private CancellationTokenSource? _pipeListenerCts;
    private System.Windows.Forms.NotifyIcon? _notifyIcon;

    public MainWindow()
    {
        InitializeComponent();
        _vault = new DpapiVault();

        InitializeNotifyIcon();
        LoadSettings();
        GeneratePairingQrCode();
        StartPipeListener();

        // Check if started minimized in background on Windows startup
        var args = Environment.GetCommandLineArgs();
        if (args.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase) || a.Equals("--autostart", StringComparison.OrdinalIgnoreCase)))
        {
            WindowState = WindowState.Minimized;
            ShowInTaskbar = false;
            Hide();
        }
    }

    private void InitializeNotifyIcon()
    {
        try
        {
            _notifyIcon = new System.Windows.Forms.NotifyIcon
            {
                Icon = System.Drawing.SystemIcons.Shield,
                Visible = true,
                Text = "Biometric Phone Unlock & Clipboard Sync"
            };

            var contextMenu = new System.Windows.Forms.ContextMenuStrip();
            contextMenu.Items.Add("Open Biometric Setup", null, (s, e) => RestoreFromTray());
            contextMenu.Items.Add("Exit", null, (s, e) => ExitApp());

            _notifyIcon.ContextMenuStrip = contextMenu;
            _notifyIcon.DoubleClick += (s, e) => RestoreFromTray();

            StateChanged += (s, e) =>
            {
                if (WindowState == WindowState.Minimized)
                {
                    ShowInTaskbar = false;
                    Hide();
                    _notifyIcon?.ShowBalloonTip(1500, "Biometric Sync Active", "Running in system tray for instant clipboard sync.", System.Windows.Forms.ToolTipIcon.Info);
                }
            };
        }
        catch { }
    }

    private void RestoreFromTray()
    {
        Show();
        ShowInTaskbar = true;
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitApp()
    {
        _notifyIcon?.Dispose();
        _notifyIcon = null;
        _pipeListenerCts?.Cancel();
        System.Windows.Application.Current.Shutdown();
    }

    private void StartPipeListener()
    {
        _pipeListenerCts = new CancellationTokenSource();
        Task.Run(() => ListenToPipeAsync(_pipeListenerCts.Token));
    }

    private async Task ListenToPipeAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var clientStream = new NamedPipeClientStream(".", "BiometricUnlockPipe", PipeDirection.InOut, PipeOptions.Asynchronous);
                await clientStream.ConnectAsync(3000, ct);

                byte[] buffer = new byte[Marshal.SizeOf<PipeMessage>()];
                while (clientStream.IsConnected && !ct.IsCancellationRequested)
                {
                    int bytesRead = await clientStream.ReadAsync(buffer.AsMemory(0, buffer.Length), ct);
                    if (bytesRead == 0) break;

                    IntPtr ptrIn = Marshal.AllocHGlobal(buffer.Length);
                    PipeMessage msg;
                    try
                    {
                        Marshal.Copy(buffer, 0, ptrIn, buffer.Length);
                        msg = Marshal.PtrToStructure<PipeMessage>(ptrIn);
                    }
                    finally
                    {
                        Marshal.FreeHGlobal(ptrIn);
                    }

                    if (msg.Command == PipeCommand.ClipboardSync)
                    {
                        string text = msg.StatusMessage;
                        Dispatcher.Invoke(() =>
                        {
                            try
                            {
                                System.Windows.Clipboard.SetText(text);
                                TxtServiceStatus.Text = $"📋 Copied to Windows Clipboard from Phone! ({text.Length} chars)";
                                string preview = text.Length > 30 ? text.Substring(0, 30) + "..." : text;
                                _notifyIcon?.ShowBalloonTip(2500, "Clipboard Synced 📋", $"Copied from Phone: {preview}", System.Windows.Forms.ToolTipIcon.Info);
                            }
                            catch (Exception ex)
                            {
                                TxtServiceStatus.Text = $"Clipboard Sync Warning: {ex.Message}";
                            }
                        });
                    }
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                await Task.Delay(2000, ct);
            }
        }
    }

    private void LoadSettings()
    {
        var config = _vault.LoadConfig();
        TxtDomain.Text = config.Credential?.Domain ?? Environment.UserDomainName;
        TxtUsername.Text = config.Credential?.Username ?? Environment.UserName;

        SliderRssi.Value = config.RssiThreshold;
        TxtRssiValue.Text = $"{config.RssiThreshold} dBm";

        RefreshPairedDevices();
    }

    private void RefreshPairedDevices()
    {
        var config = _vault.LoadConfig();
        ListPairedDevices.ItemsSource = config.PairedDevices;
    }

    private void GeneratePairingQrCode()
    {
        try
        {
            string ipAddress = "127.0.0.1";
            try
            {
                // Find the best IP: prefer WiFi/DHCP addresses, skip 169.254.x.x link-local
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    
                    var props = ni.GetIPProperties();
                    foreach (var addr in props.UnicastAddresses)
                    {
                        if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string ip = addr.Address.ToString();
                        // Skip link-local (169.254.x.x) and loopback
                        if (ip.StartsWith("169.254.") || ip.StartsWith("127.")) continue;
                        
                        ipAddress = ip;
                        // Prefer WiFi interfaces - if we find one, use it immediately
                        if (ni.Name.Contains("Wi-Fi", StringComparison.OrdinalIgnoreCase) ||
                            ni.Description.Contains("Wi-Fi", StringComparison.OrdinalIgnoreCase) ||
                            ni.Description.Contains("Wireless", StringComparison.OrdinalIgnoreCase))
                        {
                            goto foundIp;
                        }
                    }
                }
                foundIp:;
            }
            catch
            {
                // Last resort fallback
                var host = Dns.GetHostEntry(Dns.GetHostName());
                ipAddress = host.AddressList.FirstOrDefault(ip => ip.AddressFamily == AddressFamily.InterNetwork)?.ToString() ?? "127.0.0.1";
            }

            var pairingPayload = new
            {
                Version = 2,
                MachineName = Environment.MachineName,
                IPAddress = ipAddress,
                Port = 9898,
                ServiceUuid = BleGattServerManager.ServiceUuid.ToString(),
                StatusChar = BleGattServerManager.StatusCharUuid.ToString(),
                ChallengeChar = BleGattServerManager.ChallengeCharUuid.ToString(),
                AuthChar = BleGattServerManager.AuthResponseCharUuid.ToString(),
                PairingChar = BleGattServerManager.PairingCharUuid.ToString()
            };

            string json = JsonSerializer.Serialize(pairingPayload);
            
            TxtMachineInfo.Text = $"Machine: {Environment.MachineName}\nIP: {ipAddress}:9898";

            using var qrGenerator = new QRCodeGenerator();
            using var qrCodeData = qrGenerator.CreateQrCode(json, QRCodeGenerator.ECCLevel.Q);
            using var qrCode = new PngByteQRCode(qrCodeData);
            byte[] qrBytes = qrCode.GetGraphic(20);

            var bitmapImage = new BitmapImage();
            using (var ms = new MemoryStream(qrBytes))
            {
                bitmapImage.BeginInit();
                bitmapImage.CacheOption = BitmapCacheOption.OnLoad;
                bitmapImage.StreamSource = ms;
                bitmapImage.EndInit();
            }

            ImgQrCode.Source = bitmapImage;
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Failed to generate QR Code: {ex.Message}", "Error", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void BtnSaveCreds_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(TxtUsername.Text) || string.IsNullOrEmpty(TxtPassword.Password))
        {
            MessageBox.Show("Please enter both username and password.", "Validation", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        string domain = TxtDomain.Text.Trim();
        string username = TxtUsername.Text.Trim();
        
        // If it looks like a Microsoft Account email, force a blank domain so LSA routes it properly.
        if (username.Contains("@"))
        {
            domain = "";
        }

        _vault.SaveUserCredentials(username, domain, TxtPassword.Password);
        TxtCredsStatus.Text = "✓ Saved securely to DPAPI Vault";
    }

    private void SliderRssi_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (TxtRssiValue != null)
        {
            int val = (int)e.NewValue;
            TxtRssiValue.Text = $"{val} dBm";
            if (_vault != null)
            {
                var config = _vault.LoadConfig();
                config.RssiThreshold = val;
                _vault.SaveConfig(config);
            }
        }
    }

    private void BtnRemoveDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement elem && elem.Tag is string deviceId)
        {
            var config = _vault.LoadConfig();
            config.PairedDevices.RemoveAll(d => d.DeviceId == deviceId);
            _vault.SaveConfig(config);
            RefreshPairedDevices();
        }
    }

    private async void BtnTestUnlock_Click(object sender, RoutedEventArgs e)
    {
        var creds = _vault.GetUserCredentials();
        if (creds == null)
        {
            MessageBox.Show("Please save your credentials first.", "Notice", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        using var pipeServer = new PipeServer();
        pipeServer.Start();
        await Task.Delay(500);
        await pipeServer.SendUnlockTriggerAsync(creds.Value.Username, creds.Value.Domain, creds.Value.Password);

        MessageBox.Show("Test unlock signal dispatched to \\\\.\\pipe\\BiometricUnlockPipe.\nIf lock screen is active, it will consume the signal.", "Test Triggered", MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
