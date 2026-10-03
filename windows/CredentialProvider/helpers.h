#pragma once
#include "common.h"
#include <ntsecapi.h>

// Callback for real-time status updates while pipe stays connected
typedef void (*PipeStatusCallback)(void* pContext, PCWSTR statusText);

HRESULT KerbInteractiveUnlockLogonPack(
    _In_ PCWSTR domain,
    _In_ PCWSTR username,
    _In_ PCWSTR password,
    _Outptr_result_bytebuffer_(*pdwAuthPackageLength) BYTE** ppbAuthPackage,
    _Out_ DWORD* pdwAuthPackageLength
);

HRESULT QueryPipeStatus(
    _Out_writes_(cchStatus) PWSTR pszStatus,
    _In_ DWORD cchStatus
);

HRESULT WaitForPipeUnlock(
    _In_ HANDLE hCancelEvent,
    _Out_writes_(cchUsername) PWSTR pszUsername,
    _In_ DWORD cchUsername,
    _Out_writes_(cchDomain) PWSTR pszDomain,
    _In_ DWORD cchDomain,
    _Out_writes_(cchPassword) PWSTR pszPassword,
    _In_ DWORD cchPassword,
    _Out_writes_(cchStatus) PWSTR pszStatus,
    _In_ DWORD cchStatus,
    _In_opt_ PipeStatusCallback pfnStatusCallback = nullptr,
    _In_opt_ void* pCallbackContext = nullptr
);

