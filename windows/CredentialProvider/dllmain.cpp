#include <windows.h>
#include <unknwn.h>
#include "guid.h"
#include "FingerprintCredentialProvider.h"

static LONG g_cRef = 0;
HINSTANCE g_hInst = nullptr;

class ClassFactory : public IClassFactory
{
public:
    ClassFactory() : _cRef(1) {}

    IFACEMETHODIMP QueryInterface(_In_ REFIID riid, _COM_Outptr_ void** ppv) override
    {
        static const QITAB qit[] =
        {
            QITABENT(ClassFactory, IClassFactory),
            { 0 },
        };
        return QISearch(this, qit, riid, ppv);
    }

    IFACEMETHODIMP_(ULONG) AddRef() override
    {
        return InterlockedIncrement(&_cRef);
    }

    IFACEMETHODIMP_(ULONG) Release() override
    {
        LONG cRef = InterlockedDecrement(&_cRef);
        if (cRef == 0)
        {
            delete this;
        }
        return cRef;
    }

    IFACEMETHODIMP CreateInstance(_In_opt_ IUnknown* pUnkOuter, _In_ REFIID riid, _COM_Outptr_ void** ppv) override
    {
        if (pUnkOuter != nullptr) return CLASS_E_NOAGGREGATION;

        FingerprintCredentialProvider* pProvider = new (std::nothrow) FingerprintCredentialProvider();
        if (!pProvider) return E_OUTOFMEMORY;

        HRESULT hr = pProvider->QueryInterface(riid, ppv);
        pProvider->Release();
        return hr;
    }

    IFACEMETHODIMP LockServer(BOOL bLock) override
    {
        if (bLock)
        {
            InterlockedIncrement(&g_cRef);
        }
        else
        {
            InterlockedDecrement(&g_cRef);
        }
        return S_OK;
    }

private:
    LONG _cRef;
};

BOOL WINAPI DllMain(HINSTANCE hInst, DWORD dwReason, LPVOID /*lpReserved*/)
{
    if (dwReason == DLL_PROCESS_ATTACH)
    {
        g_hInst = hInst;
        DisableThreadLibraryCalls(hInst);
    }
    return TRUE;
}

STDAPI DllCanUnloadNow()
{
    return (g_cRef == 0) ? S_OK : S_FALSE;
}

STDAPI DllGetClassObject(_In_ REFCLSID rclsid, _In_ REFIID riid, _COM_Outptr_ void** ppv)
{
    if (rclsid == CLSID_FingerprintCredentialProvider)
    {
        ClassFactory* pFactory = new (std::nothrow) ClassFactory();
        if (!pFactory) return E_OUTOFMEMORY;

        HRESULT hr = pFactory->QueryInterface(riid, ppv);
        pFactory->Release();
        return hr;
    }
    return CLASS_E_CLASSNOTAVAILABLE;
}

// Registry Helper
static HRESULT SetRegistryValue(HKEY hKeyRoot, PCWSTR subKey, PCWSTR valueName, PCWSTR data)
{
    HKEY hKey = nullptr;
    LSTATUS status = RegCreateKeyExW(hKeyRoot, subKey, 0, nullptr, REG_OPTION_NON_VOLATILE, KEY_WRITE, nullptr, &hKey, nullptr);
    if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);

    status = RegSetValueExW(hKey, valueName, 0, REG_SZ, reinterpret_cast<const BYTE*>(data), static_cast<DWORD>((wcslen(data) + 1) * sizeof(WCHAR)));
    RegCloseKey(hKey);
    return HRESULT_FROM_WIN32(status);
}

STDAPI DllRegisterServer()
{
    WCHAR szModule[MAX_PATH];
    if (GetModuleFileNameW(g_hInst, szModule, ARRAYSIZE(szModule)) == 0)
    {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    // Register CLSID in InprocServer32
    WCHAR szClsidKey[MAX_PATH];
    wcscpy_s(szClsidKey, L"CLSID\\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}");
    SetRegistryValue(HKEY_CLASSES_ROOT, szClsidKey, nullptr, L"FingerprintCredentialProvider");

    WCHAR szInprocKey[MAX_PATH];
    wcscpy_s(szInprocKey, L"CLSID\\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}\\InprocServer32");
    SetRegistryValue(HKEY_CLASSES_ROOT, szInprocKey, nullptr, szModule);
    SetRegistryValue(HKEY_CLASSES_ROOT, szInprocKey, L"ThreadingModel", L"Apartment");

    // Register into Windows Credential Providers list
    WCHAR szCpKey[MAX_PATH];
    wcscpy_s(szCpKey, L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}");
    SetRegistryValue(HKEY_LOCAL_MACHINE, szCpKey, nullptr, L"FingerprintCredentialProvider");

    return S_OK;
}

STDAPI DllUnregisterServer()
{
    RegDeleteKeyW(HKEY_CLASSES_ROOT, L"CLSID\\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}\\InprocServer32");
    RegDeleteKeyW(HKEY_CLASSES_ROOT, L"CLSID\\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}");
    RegDeleteKeyW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{8B37A55C-3BF2-4D3E-A59B-51421DA10842}");
    return S_OK;
}
