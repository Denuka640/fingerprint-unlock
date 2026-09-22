#pragma once
#include "common.h"
#include <windows.h>
#include <credentialprovider.h>

class FingerprintCredential : public ICredentialProviderCredential2
{
public:
    FingerprintCredential();
    virtual ~FingerprintCredential();

    // IUnknown
    IFACEMETHODIMP QueryInterface(_In_ REFIID riid, _COM_Outptr_ void** ppv) override;
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;

    // ICredentialProviderCredential
    IFACEMETHODIMP Advise(_In_ ICredentialProviderCredentialEvents* pcpce) override;
    IFACEMETHODIMP UnAdvise() override;

    IFACEMETHODIMP SetSelected(_Out_ BOOL* pbAutoLogon) override;
    IFACEMETHODIMP SetDeselected() override;

    IFACEMETHODIMP GetFieldState(
        _In_ DWORD dwFieldID,
        _Out_ CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs,
        _Out_ CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis) override;

    IFACEMETHODIMP GetStringValue(_In_ DWORD dwFieldID, _Outptr_result_nullonfailure_ PWSTR* ppsz) override;
    IFACEMETHODIMP GetBitmapValue(_In_ DWORD dwFieldID, _Outptr_result_nullonfailure_ HBITMAP* phbmp) override;
    IFACEMETHODIMP GetCheckboxValue(_In_ DWORD dwFieldID, _Out_ BOOL* pbChecked, _Outptr_result_nullonfailure_ PWSTR* ppszLabel) override;
    IFACEMETHODIMP GetSubmitButtonValue(_In_ DWORD dwFieldID, _Out_ DWORD* pdwAdjacentTo) override;
    IFACEMETHODIMP GetComboBoxValueCount(_In_ DWORD dwFieldID, _Out_ DWORD* pcItems, _Out_ DWORD* pdwSelectedItem) override;
    IFACEMETHODIMP GetComboBoxValueAt(_In_ DWORD dwFieldID, _In_ DWORD dwItem, _Outptr_result_nullonfailure_ PWSTR* ppszItem) override;

    IFACEMETHODIMP SetStringValue(_In_ DWORD dwFieldID, _In_ PCWSTR psz) override;
    IFACEMETHODIMP SetCheckboxValue(_In_ DWORD dwFieldID, _In_ BOOL bChecked) override;
    IFACEMETHODIMP SetComboBoxSelectedValue(_In_ DWORD dwFieldID, _In_ DWORD dwSelectedItem) override;
    IFACEMETHODIMP CommandLinkClicked(_In_ DWORD dwFieldID) override;

    IFACEMETHODIMP GetSerialization(
        _Out_ CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
        _Out_ CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs,
        _Outptr_result_maybenull_ PWSTR* ppszOptionalStatusText,
        _Out_ CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override;

    IFACEMETHODIMP ReportResult(
        _In_ NTSTATUS ntsStatus,
        _In_ NTSTATUS ntsSubstatus,
        _Outptr_result_maybenull_ PWSTR* ppszOptionalStatusText,
        _Out_ CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override;

    // ICredentialProviderCredential2
    IFACEMETHODIMP GetUserSid(_Outptr_result_nullonfailure_ PWSTR* ppszSid) override;

    // Helper to initialize credential
    HRESULT Initialize(
        _In_ CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus,
        _In_opt_ PCWSTR pszSid,
        _In_ DWORD dwAuthPackage
    );

    void TriggerUnlock(PCWSTR username, PCWSTR domain, PCWSTR password);
    void UpdateStatusText(PCWSTR status);

private:
    static DWORD WINAPI BackgroundListenerThread(LPVOID lpParam);

    LONG _cRef;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO _cpus;
    DWORD _dwAuthPackage;
    ICredentialProviderCredentialEvents* _pCredEvents;

    WCHAR _szStatus[256];
    WCHAR _szUsername[128];
    WCHAR _szDomain[128];
    WCHAR _szPassword[128];
    PWSTR _pszUserSid;

    BOOL _bAutoLogonReady;
    HANDLE _hListenerThread;
    HANDLE _hCancelEvent;
    CRITICAL_SECTION _cs;
};
