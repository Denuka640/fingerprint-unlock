using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media.Imaging;
using QRCoder;
using BiometricUnlock.Service.Bluetooth;
using BiometricUnlock.Service.Ipc;
using BiometricUnlock.Service.Security;

namespace BiometricUnlock.SetupApp;

public partial class MainWindow : Window
{
    private readonly DpapiVault _vault;

    public MainWindow()
    {
        InitializeComponent();
        _vault = new DpapiVault();

        LoadSettings();
        GeneratePairingQrCode();
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
            var pairingPayload = new
            {
                Version = 1,
                MachineName = Environment.MachineName,
                ServiceUuid = BleGattServerManager.ServiceUuid.ToString(),
                StatusChar = BleGattServerManager.StatusCharUuid.ToString(),
                ChallengeChar = BleGattServerManager.ChallengeCharUuid.ToString(),
                AuthChar = BleGattServerManager.AuthResponseCharUuid.ToString(),
                PairingChar = BleGattServerManager.PairingCharUuid.ToString()
            };

            string json = JsonSerializer.Serialize(pairingPayload);

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

        _vault.SaveUserCredentials(TxtUsername.Text.Trim(), TxtDomain.Text.Trim(), TxtPassword.Password);
        TxtCredsStatus.Text = "✓ Saved securely to DPAPI Vault";
    }

    private void SliderRssi_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (TxtRssiValue != null)
        {
            int val = (int)e.NewValue;
            TxtRssiValue.Text = $"{val} dBm";

            var config = _vault.LoadConfig();
            config.RssiThreshold = val;
            _vault.SaveConfig(config);
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
