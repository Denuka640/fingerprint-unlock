#pragma once
#include "common.h"
#include "FingerprintCredential.h"
#include <windows.h>
#include <credentialprovider.h>

class FingerprintCredentialProvider : 
    public ICredentialProvider,
    public ICredentialProviderSetUserArray
{
public:
    FingerprintCredentialProvider();
    virtual ~FingerprintCredentialProvider();

    // IUnknown
    IFACEMETHODIMP QueryInterface(_In_ REFIID riid, _COM_Outptr_ void** ppv) override;
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;

    // ICredentialProvider
    IFACEMETHODIMP SetUsageScenario(
        _In_ CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus,
        _In_ DWORD dwFlags) override;

    IFACEMETHODIMP SetSerialization(
        _In_ const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs) override;

    IFACEMETHODIMP Advise(
        _In_ ICredentialProviderEvents* pcpe,
        _In_ UINT_PTR upAdviseContext) override;

    IFACEMETHODIMP UnAdvise() override;

    IFACEMETHODIMP GetFieldDescriptorCount(_Out_ DWORD* pdwCount) override;

    IFACEMETHODIMP GetFieldDescriptorAt(
        _In_ DWORD dwIndex,
        _Outptr_result_nullonfailure_ CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd) override;

    IFACEMETHODIMP GetCredentialCount(
        _Out_ DWORD* pdwCount,
        _Out_ DWORD* pdwDefault,
        _Out_ BOOL* pbAutoLogonWithDefault) override;

    IFACEMETHODIMP GetCredentialAt(
        _In_ DWORD dwIndex,
        _Outptr_result_nullonfailure_ ICredentialProviderCredential** ppcpc) override;

    // ICredentialProviderSetUserArray
    IFACEMETHODIMP SetUserArray(_In_ ICredentialProviderUserArray* pUsers) override;

private:
    HRESULT _CreateCredentials();
    void _ReleaseCredentials();

    LONG _cRef;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO _cpus;
    ICredentialProviderEvents* _pEvents;
    UINT_PTR _upAdviseContext;
    DWORD _dwAuthPackage;

    FingerprintCredential* _pCredential;
    ICredentialProviderUserArray* _pUserArray;
    CRITICAL_SECTION _cs;
};
