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
    _In_ DWORD cchStatus,
    _In_opt_ PipeStatusCallback pfnStatusCallback,
    _In_opt_ void* pCallbackContext
)
{
    // Connect to the named pipe. Keep this connection PERSISTENT so we never
    // miss the UnlockTriggered broadcast from the service.
    HANDLE hPipe = CreateFileW(
        BIOMETRIC_PIPE_NAME,
        GENERIC_READ | GENERIC_WRITE,
        0, // dwShareMode
        nullptr, // lpSecurityAttributes
        OPEN_EXISTING,
        FILE_FLAG_OVERLAPPED, // dwFlagsAndAttributes
        nullptr
    );

    if (hPipe == INVALID_HANDLE_VALUE)
    {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    OVERLAPPED ov = {};
    ov.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!ov.hEvent)
    {
        CloseHandle(hPipe);
        return HRESULT_FROM_WIN32(GetLastError());
    }

    // Stay connected and keep reading messages in a loop until we get
    // an UnlockTriggered command or are cancelled.
    HRESULT hrResult = E_FAIL;

    while (true)
    {
        ResetEvent(ov.hEvent);

        PipeMessage msg = {};
        DWORD bytesRead = 0;
        BOOL bSuccess = ReadFile(hPipe, &msg, sizeof(msg), &bytesRead, &ov);

        if (!bSuccess)
        {
            DWORD dwErr = GetLastError();
            if (dwErr == ERROR_IO_PENDING)
            {
                HANDLE waitHandles[2] = { ov.hEvent, hCancelEvent };
                DWORD waitResult = WaitForMultipleObjects(2, waitHandles, FALSE, INFINITE);

                if (waitResult == WAIT_OBJECT_0) // Pipe data arrived
                {
                    if (!GetOverlappedResult(hPipe, &ov, &bytesRead, FALSE))
                    {
                        hrResult = E_FAIL;
                        break;
                    }
                    bSuccess = TRUE;
                }
                else // Cancel event fired or error
                {
                    CancelIo(hPipe);
                    hrResult = E_ABORT;
                    break;
                }
            }
            else
            {
                hrResult = E_FAIL;
                break;
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
                hrResult = S_OK;
                break;
            }
            else if (msg.Command == PipeCommand::StatusUpdate)
            {
                StringCchCopyW(pszStatus, cchStatus, msg.StatusMessage);
                // Push real-time update to lock screen UI via callback
                if (pfnStatusCallback)
                {
                    pfnStatusCallback(pCallbackContext, msg.StatusMessage);
                }
                continue;
            }
        }
        else if (bytesRead == 0)
        {
            hrResult = E_FAIL;
            break;
        }
    }

    CloseHandle(ov.hEvent);
    CloseHandle(hPipe);
    return hrResult;
}

