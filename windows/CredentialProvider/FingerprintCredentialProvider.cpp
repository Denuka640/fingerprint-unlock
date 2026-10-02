#include "FingerprintCredentialProvider.h"
#include <strsafe.h>

struct CP_FIELD_DESC_INTERNAL
{
    DWORD dwFieldID;
    CREDENTIAL_PROVIDER_FIELD_TYPE cpft;
    PCWSTR pszLabel;
    GUID guidFieldType;
};

static const CP_FIELD_DESC_INTERNAL s_rgFieldDescriptors[] =
{
    { FID_TILE_IMAGE, CPFT_TILE_IMAGE, L"Fingerprint Icon", { 0 } },
    { FID_LARGE_TEXT, CPFT_LARGE_TEXT, L"Biometric Unlock", { 0 } },
    { FID_STATUS_TEXT, CPFT_SMALL_TEXT, L"Status", { 0 } },
    { FID_SUBMIT_BUTTON, CPFT_SUBMIT_BUTTON, L"Submit", { 0 } },
};

FingerprintCredentialProvider::FingerprintCredentialProvider() :
    _cRef(1),
    _cpus(CPUS_INVALID),
    _pEvents(nullptr),
    _upAdviseContext(0),
    _dwAuthPackage(0),
    _pCredential(nullptr),
    _pUserArray(nullptr)
{
    InitializeCriticalSection(&_cs);

    // Retrieve Negotiate / Kerberos Auth Package ID
    HANDLE hLsa = nullptr;
    if (LsaConnectUntrusted(&hLsa) == 0) // STATUS_SUCCESS
    {
        const char* szAuthPkg = "Negotiate";
        LSA_STRING authPackageName = {};
        authPackageName.Buffer = const_cast<PCHAR>(szAuthPkg);
        authPackageName.Length = static_cast<USHORT>(strlen(szAuthPkg));
        authPackageName.MaximumLength = authPackageName.Length;

        ULONG packageId = 0;
        if (LsaLookupAuthenticationPackage(hLsa, &authPackageName, &packageId) == 0)
        {
            _dwAuthPackage = packageId;
        }
        LsaDeregisterLogonProcess(hLsa);
    }
}

FingerprintCredentialProvider::~FingerprintCredentialProvider()
{
    _ReleaseCredentials();

    if (_pEvents)
    {
        _pEvents->Release();
        _pEvents = nullptr;
    }

    if (_pUserArray)
    {
        _pUserArray->Release();
        _pUserArray = nullptr;
    }

    DeleteCriticalSection(&_cs);
}

IFACEMETHODIMP FingerprintCredentialProvider::QueryInterface(_In_ REFIID riid, _COM_Outptr_ void** ppv)
{
    static const QITAB qit[] =
    {
        QITABENT(FingerprintCredentialProvider, ICredentialProvider),
        QITABENT(FingerprintCredentialProvider, ICredentialProviderSetUserArray),
        { 0 },
    };
    return QISearch(this, qit, riid, ppv);
}

IFACEMETHODIMP_(ULONG) FingerprintCredentialProvider::AddRef()
{
    return InterlockedIncrement(&_cRef);
}

IFACEMETHODIMP_(ULONG) FingerprintCredentialProvider::Release()
{
    LONG cRef = InterlockedDecrement(&_cRef);
    if (cRef == 0)
    {
        delete this;
    }
    return cRef;
}

IFACEMETHODIMP FingerprintCredentialProvider::SetUsageScenario(
    _In_ CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus,
    _In_ DWORD /*dwFlags*/)
{
    switch (cpus)
    {
    case CPUS_LOGON:
    case CPUS_UNLOCK_WORKSTATION:
        _cpus = cpus;
        return S_OK;
    case CPUS_CHANGE_PASSWORD:
    case CPUS_CREDUI:
        return E_NOTIMPL;
    default:
        return E_INVALIDARG;
    }
}

IFACEMETHODIMP FingerprintCredentialProvider::SetSerialization(
    _In_ const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* /*pcpcs*/)
{
    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::Advise(
    _In_ ICredentialProviderEvents* pcpe,
    _In_ UINT_PTR upAdviseContext)
{
    EnterCriticalSection(&_cs);
    if (_pEvents)
    {
        _pEvents->Release();
    }
    _pEvents = pcpe;
    _upAdviseContext = upAdviseContext;
    if (_pEvents)
    {
        _pEvents->AddRef();
    }
    if (_pCredential)
    {
        _pCredential->SetProviderEvents(_pEvents, _upAdviseContext);
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::UnAdvise()
{
    EnterCriticalSection(&_cs);
    if (_pEvents)
    {
        _pEvents->Release();
        _pEvents = nullptr;
    }
    _upAdviseContext = 0;
    if (_pCredential)
    {
        _pCredential->SetProviderEvents(nullptr, 0);
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::GetFieldDescriptorCount(_Out_ DWORD* pdwCount)
{
    if (!pdwCount) return E_INVALIDARG;
    *pdwCount = FID_NUM_FIELDS;
    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::GetFieldDescriptorAt(
    _In_ DWORD dwIndex,
    _Outptr_result_nullonfailure_ CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd)
{
    if (!ppcpfd || dwIndex >= FID_NUM_FIELDS) return E_INVALIDARG;
    *ppcpfd = nullptr;

    const CP_FIELD_DESC_INTERNAL& src = s_rgFieldDescriptors[dwIndex];
    CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR* pcpfd = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
        CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
    if (!pcpfd) return E_OUTOFMEMORY;

    pcpfd->dwFieldID = src.dwFieldID;
    pcpfd->cpft = src.cpft;
    pcpfd->guidFieldType = src.guidFieldType;

    size_t cch = wcslen(src.pszLabel) + 1;
    pcpfd->pszLabel = static_cast<PWSTR>(CoTaskMemAlloc(cch * sizeof(WCHAR)));
    if (!pcpfd->pszLabel)
    {
        CoTaskMemFree(pcpfd);
        return E_OUTOFMEMORY;
    }
    StringCchCopyW(pcpfd->pszLabel, cch, src.pszLabel);

    *ppcpfd = pcpfd;
    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::GetCredentialCount(
    _Out_ DWORD* pdwCount,
    _Out_ DWORD* pdwDefault,
    _Out_ BOOL* pbAutoLogonWithDefault)
{
    if (!pdwCount || !pdwDefault || !pbAutoLogonWithDefault) return E_INVALIDARG;

    *pdwCount = 0;
    *pdwDefault = CREDENTIAL_PROVIDER_NO_DEFAULT;
    *pbAutoLogonWithDefault = FALSE;

    HRESULT hr = _CreateCredentials();
    if (SUCCEEDED(hr) && _pCredential)
    {
        *pdwCount = 1;
        *pdwDefault = 0;
        
        // If the credential has already received an unlock trigger via the background listener,
        // we can tell LogonUI to auto-logon immediately with this default.
        BOOL bAutoLogon = FALSE;
        _pCredential->SetSelected(&bAutoLogon);
        *pbAutoLogonWithDefault = bAutoLogon;
    }

    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::GetCredentialAt(
    _In_ DWORD dwIndex,
    _Outptr_result_nullonfailure_ ICredentialProviderCredential** ppcpc)
{
    if (!ppcpc || dwIndex != 0 || !_pCredential) return E_INVALIDARG;

    *ppcpc = _pCredential;
    (*ppcpc)->AddRef();
    return S_OK;
}

IFACEMETHODIMP FingerprintCredentialProvider::SetUserArray(_In_ ICredentialProviderUserArray* pUsers)
{
    EnterCriticalSection(&_cs);
    if (_pUserArray)
    {
        _pUserArray->Release();
    }
    _pUserArray = pUsers;
    if (_pUserArray)
    {
        _pUserArray->AddRef();
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

HRESULT FingerprintCredentialProvider::_CreateCredentials()
{
    EnterCriticalSection(&_cs);
    if (!_pCredential)
    {
        _pCredential = new (std::nothrow) FingerprintCredential();
        if (!_pCredential)
        {
            LeaveCriticalSection(&_cs);
            return E_OUTOFMEMORY;
        }

        PWSTR pszSid = nullptr;
        if (_pUserArray)
        {
            DWORD userCount = 0;
            if (SUCCEEDED(_pUserArray->GetCount(&userCount)) && userCount > 0)
            {
                ICredentialProviderUser* pUser = nullptr;
                if (SUCCEEDED(_pUserArray->GetAt(0, &pUser)) && pUser)
                {
                    pUser->GetSid(&pszSid);
                    pUser->Release();
                }
            }
        }

        _pCredential->Initialize(_cpus, pszSid, _dwAuthPackage);
        if (_pEvents)
        {
            _pCredential->SetProviderEvents(_pEvents, _upAdviseContext);
        }
        if (pszSid)
        {
            CoTaskMemFree(pszSid);
        }
    }
    LeaveCriticalSection(&_cs);
    return S_OK;
}

void FingerprintCredentialProvider::_ReleaseCredentials()
{
    EnterCriticalSection(&_cs);
    if (_pCredential)
    {
        _pCredential->Release();
        _pCredential = nullptr;
    }
    LeaveCriticalSection(&_cs);
}
