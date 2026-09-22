#pragma once
#define SECURITY_WIN32
#include <windows.h>
#include <credentialprovider.h>
#include <ntsecapi.h>
#include <security.h>
#include <shlwapi.h>
#include <string>
#include <vector>

// Credential field identifiers
enum FIELD_ID
{
    FID_TILE_IMAGE = 0,
    FID_LARGE_TEXT = 1,
    FID_STATUS_TEXT = 2,
    FID_SUBMIT_BUTTON = 3,
    FID_NUM_FIELDS = 4
};

// Named pipe message contract
#define BIOMETRIC_PIPE_NAME L"\\\\.\\pipe\\BiometricUnlockPipe"

enum class PipeCommand : DWORD
{
    QueryStatus = 1,
    StatusUpdate = 2,
    UnlockTriggered = 3,
    Acknowledge = 4
};

#pragma pack(push, 1)
struct PipeMessage
{
    PipeCommand Command;
    DWORD StatusLength;
    WCHAR StatusMessage[256];
    DWORD UsernameLength;
    WCHAR Username[128];
    DWORD DomainLength;
    WCHAR Domain[128];
    DWORD PasswordLength;
    WCHAR Password[128];
};
#pragma pack(pop)
