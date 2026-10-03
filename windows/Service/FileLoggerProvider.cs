using Microsoft.Extensions.Logging;

namespace BiometricUnlock.Service;

/// <summary>
/// Simple file logger that appends log lines to a text file for diagnostics.
/// </summary>
public class FileLoggerProvider : ILoggerProvider
{
    private readonly string _filePath;

    public FileLoggerProvider(string filePath)
    {
        _filePath = filePath;
        // Truncate on startup so we only see current session
        try { File.WriteAllText(_filePath, $"=== Service started at {DateTime.Now:yyyy-MM-dd HH:mm:ss} ==={Environment.NewLine}"); }
        catch { }
    }

    public ILogger CreateLogger(string categoryName) => new FileLogger(_filePath, categoryName);
    public void Dispose() { }

    private class FileLogger : ILogger
    {
        private readonly string _filePath;
        private readonly string _category;
        private static readonly object _lock = new();

        public FileLogger(string filePath, string category)
        {
            _filePath = filePath;
            _category = category;
        }

        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Debug;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var msg = $"[{DateTime.Now:HH:mm:ss.fff}] [{logLevel}] {_category}: {formatter(state, exception)}";
            if (exception != null) msg += Environment.NewLine + exception.ToString();
            lock (_lock)
            {
                try { File.AppendAllText(_filePath, msg + Environment.NewLine); }
                catch { }
            }
        }
    }
}
