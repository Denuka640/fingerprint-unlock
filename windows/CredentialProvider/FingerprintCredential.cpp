#include "FingerprintCredential.h"
#include "helpers.h"
#include <strsafe.h>

FingerprintCredential::FingerprintCredential() :
    _cRef(1),
    _cpus(CPUS_INVALID),
    _dwAuthPackage(0),
    _pCredEvents(nullptr),
    _pProviderEvents(nullptr),
    _upProviderContext(0),
    _pszUserSid(nullptr),
    _bAutoLogonReady(FALSE),
    _hListenerThread(nullptr),
    _hCancelEvent(nullptr)
{
    InitializeCriticalSection(&_cs);
    StringCchCopyW(_szStatus, ARRAYSIZE(_szStatus), L"Waiting for phone connection...");
    ZeroMemory(_szUsername, sizeof(_szUsername));
    ZeroMemory(_szDomain, sizeof(_szDomain));
    ZeroMemory(_szPassword, sizeof(_szPassword));
}

FingerprintCredential::~FingerprintCredential()
{
    if (_hCancelEvent)
    {
        SetEvent(_hCancelEvent);
    }

    if (_hListenerThread)
    {
        WaitForSingleObject(_hListenerThread, 1000);
        CloseHandle(_hListenerThread);
        _hListenerThread = nullptr;
    }

    if (_hCancelEvent)
    {
        CloseHandle(_hCancelEvent);
        _hCancelEvent = nullptr;
    }

    if (_pCredEvents)
    {
        _pCredEvents->Release();
        _pCredEvents = nullptr;
    }

    if (_pszUserSid)
    {
        CoTaskMemFree(_pszUserSid);
        _pszUserSid = nullptr;
    }
    if (_pProviderEvents)
    {
        _pProviderEvents->Release();
        _pProviderEvents = nullptr;
    }

    DeleteCriticalSection(&_cs);
}

HRESULT FingerprintCredential::Initialize(
    _In_ CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus,
    _In_opt_ PCWSTR pszSid,
    _In_ DWORD dwAuthPackage
)
{
    _cpus = cpus;
    _dwAuthPackage = dwAuthPackage;

    if (pszSid)
    {
        size_t cch = wcslen(pszSid) + 1;
        _pszUserSid = static_cast<PWSTR>(CoTaskMemAlloc(cch * sizeof(WCHAR)));
        if (_pszUserSid)
        {
            StringCchCopyW(_pszUserSid, cch, pszSid);
        }
    }

    // Initial status query
    QueryPipeStatus(_szStatus, ARRAYSIZE(_szStatus));
    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::QueryInterface(_In_ REFIID riid, _COM_Outptr_ void** ppv)
{
    static const QITAB qit[] =
    {
        QITABENT(FingerprintCredential, ICredentialProviderCredential),
        QITABENT(FingerprintCredential, ICredentialProviderCredential2),
        { 0 },
    };
    return QISearch(this, qit, riid, ppv);
}

IFACEMETHODIMP_(ULONG) FingerprintCredential::AddRef()
{
    return InterlockedIncrement(&_cRef);
}

IFACEMETHODIMP_(ULONG) FingerprintCredential::Release()
{
    LONG cRef = InterlockedDecrement(&_cRef);
    if (cRef == 0)
    {
        delete this;
    }
    return cRef;
}

IFACEMETHODIMP FingerprintCredential::Advise(_In_ ICredentialProviderCredentialEvents* pcpce)
{
    EnterCriticalSection(&_cs);
    if (_pCredEvents)
    {
        _pCredEvents->Release();
    }
    _pCredEvents = pcpce;
    if (_pCredEvents)
    {
        _pCredEvents->AddRef();
    }

    // Start background pipe listener immediately so we catch unlock signals
    // without requiring the user to manually click the tile
    if (!_hListenerThread)
    {
        _hCancelEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        _hListenerThread = CreateThread(nullptr, 0, BackgroundListenerThread, this, 0, nullptr);
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::UnAdvise()
{
    EnterCriticalSection(&_cs);
    if (_pCredEvents)
    {
        _pCredEvents->Release();
        _pCredEvents = nullptr;
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::SetSelected(_Out_ BOOL* pbAutoLogon)
{
    if (!pbAutoLogon) return E_INVALIDARG;

    EnterCriticalSection(&_cs);
    // If credentials are already ready (phone unlocked before tile was selected),
    // trigger auto-logon immediately
    *pbAutoLogon = _bAutoLogonReady;
    LeaveCriticalSection(&_cs);

    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::SetDeselected()
{
    EnterCriticalSection(&_cs);
    if (_hCancelEvent)
    {
        SetEvent(_hCancelEvent);
    }
    if (_hListenerThread)
    {
        WaitForSingleObject(_hListenerThread, 500);
        CloseHandle(_hListenerThread);
        _hListenerThread = nullptr;
    }
    if (_hCancelEvent)
    {
        CloseHandle(_hCancelEvent);
        _hCancelEvent = nullptr;
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::GetFieldState(
    _In_ DWORD dwFieldID,
    _Out_ CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs,
    _Out_ CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis)
{
    if (!pcpfs || !pcpfis) return E_INVALIDARG;

    switch (dwFieldID)
    {
    case FID_TILE_IMAGE:
        *pcpfs = CPFS_DISPLAY_IN_BOTH;
        *pcpfis = CPFIS_NONE;
        break;
    case FID_LARGE_TEXT:
        *pcpfs = CPFS_DISPLAY_IN_BOTH;
        *pcpfis = CPFIS_NONE;
        break;
    case FID_STATUS_TEXT:
        *pcpfs = CPFS_DISPLAY_IN_BOTH;
        *pcpfis = CPFIS_NONE;
        break;
    case FID_SUBMIT_BUTTON:
        *pcpfs = CPFS_DISPLAY_IN_SELECTED_TILE;
        *pcpfis = CPFIS_NONE;
        break;
    default:
        *pcpfs = CPFS_HIDDEN;
        *pcpfis = CPFIS_NONE;
        return E_INVALIDARG;
    }
    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::GetStringValue(_In_ DWORD dwFieldID, _Outptr_result_nullonfailure_ PWSTR* ppsz)
{
    if (!ppsz) return E_INVALIDARG;
    *ppsz = nullptr;

    PCWSTR pszSrc = nullptr;
    switch (dwFieldID)
    {
    case FID_LARGE_TEXT:
        pszSrc = L"Phone Fingerprint Unlock";
        break;
    case FID_STATUS_TEXT:
        pszSrc = _szStatus;
        break;
    default:
        return E_INVALIDARG;
    }

    size_t cch = wcslen(pszSrc) + 1;
    *ppsz = static_cast<PWSTR>(CoTaskMemAlloc(cch * sizeof(WCHAR)));
    if (!*ppsz) return E_OUTOFMEMORY;

    return StringCchCopyW(*ppsz, cch, pszSrc);
}

IFACEMETHODIMP FingerprintCredential::GetBitmapValue(_In_ DWORD dwFieldID, _Outptr_result_nullonfailure_ HBITMAP* phbmp)
{
    if (!phbmp) return E_INVALIDARG;
    *phbmp = nullptr;

    if (dwFieldID == FID_TILE_IMAGE)
    {
        HMODULE hModule = nullptr;
        GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(FingerprintCredential::BackgroundListenerThread), &hModule);
        *phbmp = (HBITMAP)LoadImageW(hModule, MAKEINTRESOURCEW(101), IMAGE_BITMAP, 0, 0, LR_CREATEDIBSECTION);
        if (*phbmp) return S_OK;
    }

    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::GetCheckboxValue(_In_ DWORD, _Out_ BOOL*, _Outptr_result_nullonfailure_ PWSTR*)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::GetSubmitButtonValue(_In_ DWORD dwFieldID, _Out_ DWORD* pdwAdjacentTo)
{
    if (!pdwAdjacentTo) return E_INVALIDARG;
    if (dwFieldID == FID_SUBMIT_BUTTON)
    {
        *pdwAdjacentTo = FID_STATUS_TEXT;
        return S_OK;
    }
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::GetComboBoxValueCount(_In_ DWORD, _Out_ DWORD*, _Out_ DWORD*)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::GetComboBoxValueAt(_In_ DWORD, _In_ DWORD, _Outptr_result_nullonfailure_ PWSTR*)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::SetStringValue(_In_ DWORD, _In_ PCWSTR)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::SetCheckboxValue(_In_ DWORD, _In_ BOOL)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::SetComboBoxSelectedValue(_In_ DWORD, _In_ DWORD)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::CommandLinkClicked(_In_ DWORD)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::GetUserSid(_Outptr_result_nullonfailure_ PWSTR* ppszSid)
{
    if (!ppszSid) return E_INVALIDARG;
    *ppszSid = nullptr;

    if (_pszUserSid)
    {
        size_t cch = wcslen(_pszUserSid) + 1;
        *ppszSid = static_cast<PWSTR>(CoTaskMemAlloc(cch * sizeof(WCHAR)));
        if (!*ppszSid) return E_OUTOFMEMORY;
        return StringCchCopyW(*ppszSid, cch, _pszUserSid);
    }
    return E_NOTIMPL;
}

IFACEMETHODIMP FingerprintCredential::GetSerialization(
    _Out_ CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
    _Out_ CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs,
    _Outptr_result_maybenull_ PWSTR* ppszOptionalStatusText,
    _Out_ CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon)
{
    if (!pcpgsr || !pcpcs || !ppszOptionalStatusText || !pcpsiOptionalStatusIcon)
    {
        return E_INVALIDARG;
    }

    *pcpgsr = CPGSR_NO_CREDENTIAL_FINISHED;
    *ppszOptionalStatusText = nullptr;
    *pcpsiOptionalStatusIcon = CPSI_NONE;
    ZeroMemory(pcpcs, sizeof(*pcpcs));

    EnterCriticalSection(&_cs);
    if (_bAutoLogonReady && wcslen(_szPassword) > 0)
    {
        BYTE* pbAuthPackage = nullptr;
        DWORD cbAuthPackage = 0;

        HRESULT hr = KerbInteractiveUnlockLogonPack(
            _szDomain,
            _szUsername,
            _szPassword,
            &pbAuthPackage,
            &cbAuthPackage
        );

        if (SUCCEEDED(hr))
        {
            pcpcs->ulAuthenticationPackage = _dwAuthPackage;
            pcpcs->cbSerialization = cbAuthPackage;
            pcpcs->rgbSerialization = pbAuthPackage;
            *pcpgsr = CPGSR_RETURN_CREDENTIAL_FINISHED;
            _bAutoLogonReady = FALSE;
            LeaveCriticalSection(&_cs);
            return S_OK;
        }
    }
    LeaveCriticalSection(&_cs);

    return S_OK;
}

IFACEMETHODIMP FingerprintCredential::ReportResult(
    _In_ NTSTATUS ntsStatus,
    _In_ NTSTATUS /*ntsSubstatus*/,
    _Outptr_result_maybenull_ PWSTR* ppszOptionalStatusText,
    _Out_ CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon)
{
    if (ppszOptionalStatusText) *ppszOptionalStatusText = nullptr;
    if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_NONE;

    if (ntsStatus == 0) // STATUS_SUCCESS
    {
        UpdateStatusText(L"Unlocked!");
    }
    else
    {
        UpdateStatusText(L"Authentication Failed");
    }

    return S_OK;
}

void FingerprintCredential::UpdateStatusText(PCWSTR status)
{
    EnterCriticalSection(&_cs);
    StringCchCopyW(_szStatus, ARRAYSIZE(_szStatus), status);
    if (_pCredEvents)
    {
        _pCredEvents->SetFieldString(this, FID_STATUS_TEXT, _szStatus);
    }
    LeaveCriticalSection(&_cs);
}

void FingerprintCredential::SetProviderEvents(ICredentialProviderEvents* pEvents, UINT_PTR upContext)
{
    EnterCriticalSection(&_cs);
    if (_pProviderEvents)
    {
        _pProviderEvents->Release();
    }
    _pProviderEvents = pEvents;
    _upProviderContext = upContext;
    if (_pProviderEvents)
    {
        _pProviderEvents->AddRef();
    }
    LeaveCriticalSection(&_cs);
}

void FingerprintCredential::TriggerUnlock(PCWSTR username, PCWSTR domain, PCWSTR password)
{
    EnterCriticalSection(&_cs);
    StringCchCopyW(_szUsername, ARRAYSIZE(_szUsername), username);
    StringCchCopyW(_szDomain, ARRAYSIZE(_szDomain), domain);
    StringCchCopyW(_szPassword, ARRAYSIZE(_szPassword), password);
    _bAutoLogonReady = TRUE;

    if (_pCredEvents)
    {
        _pCredEvents->SetFieldString(this, FID_STATUS_TEXT, L"Unlocking...");
        _pCredEvents->SetFieldSubmitButton(this, FID_SUBMIT_BUTTON, FID_STATUS_TEXT);
    }
    
    if (_pProviderEvents)
    {
        // Tell LogonUI to re-poll GetCredentialCount which will now return auto-logon = TRUE
        _pProviderEvents->CredentialsChanged(_upProviderContext);
    }
    LeaveCriticalSection(&_cs);
}

DWORD WINAPI FingerprintCredential::BackgroundListenerThread(LPVOID lpParam)
{
    FingerprintCredential* pThis = static_cast<FingerprintCredential*>(lpParam);

    while (WaitForSingleObject(pThis->_hCancelEvent, 0) == WAIT_TIMEOUT)
    {
        WCHAR username[128] = {};
        WCHAR domain[128] = {};
        WCHAR password[128] = {};
        WCHAR status[256] = {};

        HRESULT hr = WaitForPipeUnlock(
            pThis->_hCancelEvent,
            username, ARRAYSIZE(username),
            domain, ARRAYSIZE(domain),
            password, ARRAYSIZE(password),
            status, ARRAYSIZE(status)
        );

        if (hr == S_OK) // Unlock triggered
        {
            pThis->TriggerUnlock(username, domain, password);
            break;
        }
        else if (hr == S_FALSE) // Status update
        {
            pThis->UpdateStatusText(status);
        }
        else if (hr == E_ABORT) // Cancelled
        {
            break;
        }
        else
        {
            // Retry delay
            Sleep(1000);
        }
    }

    return 0;
}
