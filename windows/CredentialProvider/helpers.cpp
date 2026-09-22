#include "helpers.h"
#include <strsafe.h>

HRESULT KerbInteractiveUnlockLogonPack(
    _In_ PCWSTR domain,
    _In_ PCWSTR username,
    _In_ PCWSTR password,
    _Outptr_result_bytebuffer_(*pdwAuthPackageLength) BYTE** ppbAuthPackage,
    _Out_ DWORD* pdwAuthPackageLength
)
{
    if (!ppbAuthPackage || !pdwAuthPackageLength)
    {
        return E_INVALIDARG;
    }

    *ppbAuthPackage = nullptr;
    *pdwAuthPackageLength = 0;

    size_t cbDomain = (domain ? wcslen(domain) : 0) * sizeof(WCHAR);
    size_t cbUsername = (username ? wcslen(username) : 0) * sizeof(WCHAR);
    size_t cbPassword = (password ? wcslen(password) : 0) * sizeof(WCHAR);

    // Calculate total buffer size
    DWORD cbTotal = sizeof(KERB_INTERACTIVE_UNLOCK_LOGON) + 
                    static_cast<DWORD>(cbDomain + sizeof(WCHAR) + 
                                       cbUsername + sizeof(WCHAR) + 
                                       cbPassword + sizeof(WCHAR));

    BYTE* pBuffer = static_cast<BYTE*>(CoTaskMemAlloc(cbTotal));
    if (!pBuffer)
    {
        return E_OUTOFMEMORY;
    }

    ZeroMemory(pBuffer, cbTotal);

    KERB_INTERACTIVE_UNLOCK_LOGON* pUnlockLogon = reinterpret_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(pBuffer);
    pUnlockLogon->Logon.MessageType = KerbInteractiveLogon;

    BYTE* pCursor = pBuffer + sizeof(KERB_INTERACTIVE_UNLOCK_LOGON);

    // Copy Domain
    if (domain && cbDomain > 0)
    {
        CopyMemory(pCursor, domain, cbDomain);
        pUnlockLogon->Logon.LogonDomainName.Length = static_cast<USHORT>(cbDomain);
        pUnlockLogon->Logon.LogonDomainName.MaximumLength = static_cast<USHORT>(cbDomain + sizeof(WCHAR));
        pUnlockLogon->Logon.LogonDomainName.Buffer = reinterpret_cast<PWSTR>(pCursor - pBuffer); // Offset when packed
        pCursor += cbDomain + sizeof(WCHAR);
    }

    // Copy Username
    if (username && cbUsername > 0)
    {
        CopyMemory(pCursor, username, cbUsername);
        pUnlockLogon->Logon.UserName.Length = static_cast<USHORT>(cbUsername);
        pUnlockLogon->Logon.UserName.MaximumLength = static_cast<USHORT>(cbUsername + sizeof(WCHAR));
        pUnlockLogon->Logon.UserName.Buffer = reinterpret_cast<PWSTR>(pCursor - pBuffer);
        pCursor += cbUsername + sizeof(WCHAR);
    }

    // Copy Password
    if (password && cbPassword > 0)
    {
        CopyMemory(pCursor, password, cbPassword);
        pUnlockLogon->Logon.Password.Length = static_cast<USHORT>(cbPassword);
        pUnlockLogon->Logon.Password.MaximumLength = static_cast<USHORT>(cbPassword + sizeof(WCHAR));
        pUnlockLogon->Logon.Password.Buffer = reinterpret_cast<PWSTR>(pCursor - pBuffer);
        pCursor += cbPassword + sizeof(WCHAR);
    }

    *ppbAuthPackage = pBuffer;
    *pdwAuthPackageLength = cbTotal;
    return S_OK;
}

HRESULT QueryPipeStatus(
    _Out_writes_(cchStatus) PWSTR pszStatus,
    _In_ DWORD cchStatus
)
{
    if (!pszStatus || cchStatus == 0) return E_INVALIDARG;
    StringCchCopyW(pszStatus, cchStatus, L"Waiting for Phone Bluetooth...");

    HANDLE hPipe = CreateFileW(
        BIOMETRIC_PIPE_NAME,
        GENERIC_READ | GENERIC_WRITE,
        0,
        nullptr,
        OPEN_EXISTING,
        0,
        nullptr
    );

    if (hPipe == INVALID_HANDLE_VALUE)
    {
        StringCchCopyW(pszStatus, cchStatus, L"Unlock Service Not Running");
        return HRESULT_FROM_WIN32(GetLastError());
    }

    PipeMessage req = {};
    req.Command = PipeCommand::QueryStatus;

    DWORD bytesWritten = 0;
    if (WriteFile(hPipe, &req, sizeof(req), &bytesWritten, nullptr))
    {
        PipeMessage resp = {};
        DWORD bytesRead = 0;
        if (ReadFile(hPipe, &resp, sizeof(resp), &bytesRead, nullptr))
        {
            if (resp.StatusLength > 0)
            {
                StringCchCopyW(pszStatus, cchStatus, resp.StatusMessage);
            }
        }
    }

    CloseHandle(hPipe);
    return S_OK;
}

HRESULT WaitForPipeUnlock(
    _In_ HANDLE hCancelEvent,
    _Out_writes_(cchUsername) PWSTR pszUsername,
    _In_ DWORD cchUsername,
    _Out_writes_(cchDomain) PWSTR pszDomain,
    _In_ DWORD cchDomain,
    _Out_writes_(cchPassword) PWSTR pszPassword,
    _In_ DWORD cchPassword,
    _Out_writes_(cchStatus) PWSTR pszStatus,
    _In_ DWORD cchStatus
)
{
    HANDLE hPipe = CreateFileW(
        BIOMETRIC_PIPE_NAME,
        GENERIC_READ | GENERIC_WRITE,
        FILE_FLAG_OVERLAPPED,
        nullptr,
        OPEN_EXISTING,
        0,
        nullptr
    );

    if (hPipe == INVALID_HANDLE_VALUE)
    {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    OVERLAPPED ov = {};
    ov.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);

    PipeMessage msg = {};
    DWORD bytesRead = 0;
    BOOL bSuccess = ReadFile(hPipe, &msg, sizeof(msg), &bytesRead, &ov);

    if (!bSuccess && GetLastError() == ERROR_IO_PENDING)
    {
        HANDLE waitHandles[2] = { ov.hEvent, hCancelEvent };
        DWORD waitResult = WaitForMultipleObjects(2, waitHandles, FALSE, INFINITE);

        if (waitResult == WAIT_OBJECT_0) // Pipe message received
        {
            GetOverlappedResult(hPipe, &ov, &bytesRead, FALSE);
            bSuccess = TRUE;
        }
        else // Cancelled or error
        {
            CancelIo(hPipe);
            CloseHandle(ov.hEvent);
            CloseHandle(hPipe);
            return E_ABORT;
        }
    }

    if (bSuccess && bytesRead >= sizeof(PipeCommand))
    {
        if (msg.Command == PipeCommand::UnlockTriggered)
        {
            StringCchCopyW(pszUsername, cchUsername, msg.Username);
            StringCchCopyW(pszDomain, cchDomain, msg.Domain);
            StringCchCopyW(pszPassword, cchPassword, msg.Password);
            StringCchCopyW(pszStatus, cchStatus, L"Unlocking...");
            CloseHandle(ov.hEvent);
            CloseHandle(hPipe);
            return S_OK;
        }
        else if (msg.Command == PipeCommand::StatusUpdate)
        {
            StringCchCopyW(pszStatus, cchStatus, msg.StatusMessage);
            CloseHandle(ov.hEvent);
            CloseHandle(hPipe);
            return S_FALSE;
        }
    }

    CloseHandle(ov.hEvent);
    CloseHandle(hPipe);
    return E_FAIL;
}
