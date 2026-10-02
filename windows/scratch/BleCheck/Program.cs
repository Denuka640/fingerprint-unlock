using System;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;

class Program
{
    static async Task Main()
    {
        var adapter = await BluetoothAdapter.GetDefaultAsync();
        if (adapter == null) {
            Console.WriteLine("No Bluetooth adapter found.");
        } else {
            Console.WriteLine("Peripheral Role Supported: " + adapter.IsPeripheralRoleSupported);
        }
    }
}
