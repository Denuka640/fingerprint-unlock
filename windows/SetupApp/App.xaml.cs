using System.Threading;
using System.Threading.Tasks;
using System.Windows;

namespace BiometricUnlock.SetupApp;

public partial class App : System.Windows.Application
{
    private static Mutex? _singleInstanceMutex;
    private static EventWaitHandle? _restoreWaitHandle;
    private static bool _isFirstInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        _singleInstanceMutex = new Mutex(true, @"Global\BiometricUnlockSetupApp_Mutex", out _isFirstInstance);

        if (!_isFirstInstance)
        {
            // Another instance is already running in background/tray.
            // Signal the running instance to open its window and exit this second instance immediately.
            try
            {
                using var restoreEvent = EventWaitHandle.OpenExisting(@"Global\BiometricUnlockSetupApp_RestoreEvent");
                restoreEvent.Set();
            }
            catch { }

            Shutdown();
            return;
        }

        // Primary instance: create restore event handle
        _restoreWaitHandle = new EventWaitHandle(false, EventResetMode.AutoReset, @"Global\BiometricUnlockSetupApp_RestoreEvent");

        // Listen for restore signals on background thread
        Task.Run(() =>
        {
            while (_restoreWaitHandle != null)
            {
                try
                {
                    _restoreWaitHandle.WaitOne();
                    Dispatcher.Invoke(() =>
                    {
                        if (MainWindow is MainWindow mainWin)
                        {
                            mainWin.RestoreFromTray();
                        }
                    });
                }
                catch
                {
                    break;
                }
            }
        });

        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_isFirstInstance)
        {
            try
            {
                _restoreWaitHandle?.Dispose();
                _singleInstanceMutex?.ReleaseMutex();
                _singleInstanceMutex?.Dispose();
            }
            catch { }
        }
        base.OnExit(e);
    }
}

