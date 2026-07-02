#include "native_jvm.hpp"
#include "handler.hpp"
#include "string_pool.hpp"
#include <iostream>
#include <conio.h>
#include "intrin.h"
#include "sstream"
#include <string>
#include <vector>
#include <ctime>

#ifdef _WIN32
#include <windows.h>
#include <iphlpapi.h>
#include <winioctl.h>
#include <tchar.h>
#include <winhttp.h>
#pragma comment(lib, "Crypt32.lib")
#pragma comment(lib, "IPHLPAPI.lib")
#pragma comment(lib, "VMProtectSDK64.lib")
#pragma comment(lib, "winhttp.lib")
#include "VMProtectSDK.h"
#else
#include <sys/sysinfo.h>
#endif
#include <wininet.h>
#pragma comment(lib,"wininet")
#pragma comment(lib, "ntdll.lib")
#include <winternl.h>
#include <ntstatus.h>
#include <wincrypt.h>

$includes
#include <random>
#include <stdexcept>
#include <vector>
#include <algorithm>
#include "md5.hpp"
#include <tlhelp32.h>


bool detect_blacklisted_processes(JavaVM* vm, const std::string& username, const std::string& password, const std::string& hwid) {
    const std::vector<std::string> blacklisted_processes = {
        "HTTP Debugger",
        "idea64.exe", // тута кароче детекты нах
        "dbg64.exe"
    };

    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot == INVALID_HANDLE_VALUE) {
        return false;
    }

    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(PROCESSENTRY32);

    if (!Process32First(hSnapshot, &pe)) {
        CloseHandle(hSnapshot);
        return false;
    }

    do {
        // Convert process name to string for comparison
        std::wstring wProcessName = pe.szExeFile;
        std::string processName(wProcessName.begin(), wProcessName.end());

        for (const std::string& blacklisted_process : blacklisted_processes) {
            if (processName.find(blacklisted_process) != std::string::npos) {
                CloseHandle(hSnapshot);
                sendBannedHwidRequest(username, password, hwid);
                vm->DestroyJavaVM();
                ExitProcess(0);
            }
        }
    } while (Process32Next(hSnapshot, &pe));

    CloseHandle(hSnapshot);
    return false;
}

using namespace std;

#ifndef ProcessDebugFlags
#define ProcessDebugFlags 0x1f
#endif

extern "C" NTSTATUS NTAPI NtQueryInformationProcess(
    HANDLE ProcessHandle,
    PROCESSINFOCLASS ProcessInformationClass,
    PVOID ProcessInformation,
    ULONG ProcessInformationLength,
    PULONG ReturnLength
);


static bool user_data_loaded = false;

string obfNew(const string& input) {
    string result = input;
    for (size_t i = 0; i < result.size(); ++i) {
        result[i] = result[i] ^ 0xFF;
    }
    return result;
}

__forceinline string getDriveSerialNumber() {
    VMProtectBeginUltra("getDriveSerialNumber");
    string serialNumber;
    HANDLE hDevice = CreateFileA(("\\.\PhysicalDrive0"), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (hDevice != INVALID_HANDLE_VALUE) {
        STORAGE_PROPERTY_QUERY storagePropertyQuery;
        ZeroMemory(&storagePropertyQuery, sizeof(STORAGE_PROPERTY_QUERY));
        storagePropertyQuery.PropertyId = StorageDeviceProperty;
        storagePropertyQuery.QueryType = PropertyStandardQuery;

        STORAGE_DESCRIPTOR_HEADER storageDescriptorHeader;
        ZeroMemory(&storageDescriptorHeader, sizeof(STORAGE_DESCRIPTOR_HEADER));
        DWORD dwBytesReturned = 0;

        DeviceIoControl(hDevice, IOCTL_STORAGE_QUERY_PROPERTY, &storagePropertyQuery, sizeof(STORAGE_PROPERTY_QUERY),
            &storageDescriptorHeader, sizeof(STORAGE_DESCRIPTOR_HEADER), &dwBytesReturned, nullptr);

        const DWORD dwOutBufferSize = storageDescriptorHeader.Size;
        BYTE* pOutBuffer = new BYTE[dwOutBufferSize];
        ZeroMemory(pOutBuffer, dwOutBufferSize);

        DeviceIoControl(hDevice, IOCTL_STORAGE_QUERY_PROPERTY, &storagePropertyQuery, sizeof(STORAGE_PROPERTY_QUERY),
            pOutBuffer, dwOutBufferSize, &dwBytesReturned, nullptr);

        STORAGE_DEVICE_DESCRIPTOR* pDeviceDescriptor = (STORAGE_DEVICE_DESCRIPTOR*)pOutBuffer;

        if (pDeviceDescriptor->SerialNumberOffset) {
            serialNumber = string((char*)(pOutBuffer + pDeviceDescriptor->SerialNumberOffset));
        }

        delete[] pOutBuffer;
        CloseHandle(hDevice);
    }
    VMProtectEnd();
    return serialNumber;
}

__forceinline string getCPUID() {
    int cpuInfo[4];
    __cpuid(cpuInfo, 0);
    stringstream ss;
    ss << hex << cpuInfo[1] << cpuInfo[3] << cpuInfo[2];
    return ss.str();
}

__forceinline string get_hwid() {
    string hardwareID;
    string driveSerial = getDriveSerialNumber();
    string cpuId = getCPUID();
    string ramSize;
    MEMORYSTATUSEX memInfo;
    memInfo.dwLength = sizeof(MEMORYSTATUSEX);
    GlobalMemoryStatusEx(&memInfo);
    ramSize = to_string(memInfo.ullTotalPhys / (1024 * 1024)) + "MB";

    hardwareID += obfNew("Serial:") + driveSerial + ";";
    hardwareID += obfNew("CPU:") + cpuId + ";";
    hardwareID += obfNew("RAM:") + ramSize + ";";

    string finalHwid = md5::create_from_string(hardwareID);
    return finalHwid;
}

string generateRandomToken(int length) {
    const char* charMap = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    string token;
    random_device rd;
    mt19937 generator(rd());
    uniform_int_distribution<> distrib(0, strlen(charMap) - 1);
    for (int i = 0; i < length; ++i) {
        token += charMap[distrib(generator)];
    }
    return token;
}

string caesarDecrypt(const string& text, int shift) {
    string result = text;
    for (char& c : result) {
        if (c >= 'a' && c <= 'z') {
            c = (char)(((c - 'a' - shift + 26) % 26) + 'a');
        } else if (c >= 'A' && c <= 'Z') {
            c = (char)(((c - 'A' - shift + 26) % 26) + 'A');
        }
    }
    return result;
}

string caesarEncrypt(const string& text, int shift) {
    string result = text;
    for (char& c : result) {
        if (c >= 'a' && c <= 'z') {
            c = (char)(((c - 'a' + shift) % 26) + 'a');
        } else if (c >= 'A' && c <= 'Z') {
            c = (char)(((c - 'A' + shift) % 26) + 'A');
        }
    }
    return result;
}

string performHttpRequest(const string& username, const string& password, const string& token, const string& hwid) {
    DWORD dwSize = 0;
    DWORD dwDownloaded = 0;
    LPSTR pszOutBuffer;
    BOOL  bResults = FALSE;
    HINTERNET hSession = NULL,
              hConnect = NULL,
              hRequest = NULL;

    hSession = WinHttpOpen(L"WissendGuard/1.0",
                           WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                           WINHTTP_NO_PROXY_NAME,
                           WINHTTP_NO_PROXY_BYPASS, 0);

    if (!hSession) {
        return "";
    }

    hConnect = WinHttpConnect(hSession, L"localhost",
                              1000, 0);
    if (!hConnect) {
        WinHttpCloseHandle(hSession);
        return "";
    }

    hRequest = WinHttpOpenRequest(hConnect, L"POST", L"/guard/nativeAuth",
                                  NULL, WINHTTP_NO_REFERER,
                                  WINHTTP_DEFAULT_ACCEPT_TYPES,
                                  0);
    if (!hRequest) {
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    const wchar_t* headers = L"Content-Type: application/x-www-form-urlencoded";
    if (!WinHttpAddRequestHeaders(hRequest, headers, -1L, WINHTTP_ADDREQ_FLAG_REPLACE | WINHTTP_ADDREQ_FLAG_ADD)) {
        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    string postData = "username=" + username + "&password=" + password + "&token=" + token + "&hwid=" + hwid;

    bResults = WinHttpSendRequest(hRequest,
                                  WINHTTP_NO_ADDITIONAL_HEADERS,
                                  0,
                                  (LPVOID)postData.c_str(),
                                  postData.length(),
                                  postData.length(),
                                  0);
    if (!bResults) {
        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    // End the request.
    bResults = WinHttpReceiveResponse(hRequest, NULL);
    if (!bResults) {
        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    string responseBody = "";

    do {
        dwSize = 0;
        if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) {
            break;
        }
        if (dwSize == 0) {
            break;
        }

        pszOutBuffer = new char[dwSize + 1];
        if (!pszOutBuffer) {
            break;
        }
        ZeroMemory(pszOutBuffer, dwSize + 1);

        if (!WinHttpReadData(hRequest, (LPVOID)pszOutBuffer, dwSize, &dwDownloaded)) {
            delete[] pszOutBuffer;
            break;
        }
        delete[] pszOutBuffer;

    } while (dwSize > 0);

    if (hRequest) WinHttpCloseHandle(hRequest);
    if (hConnect) WinHttpCloseHandle(hConnect);
    if (hSession) WinHttpCloseHandle(hSession);


    return responseBody;
}

string sendBannedHwidRequest(const string& username, const string& password, const string& hwid) {
    DWORD dwSize = 0;
    DWORD dwDownloaded = 0;
    LPSTR pszOutBuffer;
    BOOL  bResults = FALSE;
    HINTERNET hSession = NULL,
              hConnect = NULL,
              hRequest = NULL;

    hSession = WinHttpOpen(L"WissendGuard/1.0",
                           WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                           WINHTTP_NO_PROXY_NAME,
                           WINHTTP_NO_PROXY_BYPASS, 0);

    if (!hSession) {
        return "";
    }

    hConnect = WinHttpConnect(hSession, L"localhost",
                              1000, 0);
    if (!hConnect) {
        WinHttpCloseHandle(hSession);
        return "";
    }

    hRequest = WinHttpOpenRequest(hConnect, L"POST", L"/user/bannedHwid",
                                  NULL, WINHTTP_NO_REFERER,
                                  WINHTTP_DEFAULT_ACCEPT_TYPES,
                                  0);
    if (!hRequest) {
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    const wchar_t* headers = L"Content-Type: application/x-www-form-urlencoded";
    if (!WinHttpAddRequestHeaders(hRequest, headers, -1L, WINHTTP_ADDREQ_FLAG_REPLACE | WINHTTP_ADDREQ_FLAG_ADD)) {
        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    string postData = "username=" + username + "&password=" + password + "&hwid=" + hwid;

    bResults = WinHttpSendRequest(hRequest,
                                  WINHTTP_NO_ADDITIONAL_HEADERS,
                                  0,
                                  (LPVOID)postData.c_str(),
                                  postData.length(),
                                  postData.length(),
                                  0);
    if (!bResults) {
        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    bResults = WinHttpReceiveResponse(hRequest, NULL);
    if (!bResults) {
        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        return "";
    }

    string responseBody = "";

    do {
        dwSize = 0;
        if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) {
            break;
        }
        if (dwSize == 0) {
            break;
        }

        pszOutBuffer = new char[dwSize + 1];
        if (!pszOutBuffer) {
            break;
        }
        ZeroMemory(pszOutBuffer, dwSize + 1);

        if (!WinHttpReadData(hRequest, (LPVOID)pszOutBuffer, dwSize, &dwDownloaded)) {
            delete[] pszOutBuffer;
            break;
        }
        delete[] pszOutBuffer;

    } while (dwSize > 0);

    if (hRequest) WinHttpCloseHandle(hRequest);
    if (hConnect) WinHttpCloseHandle(hConnect);
    if (hSession) WinHttpCloseHandle(hSession);

    return responseBody;
}

bool loadFromRegistry(string& username, string& password) { 
	HKEY hKey;
	LONG result = RegOpenKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Wissend", 0, KEY_READ, &hKey);
	if (result != ERROR_SUCCESS) {
		return false;
	}
	char buffer[256];
	DWORD bufferSize = sizeof(buffer);
	if (RegQueryValueExA(hKey, "usr", NULL, NULL, (LPBYTE)buffer, &bufferSize) == ERROR_SUCCESS) {
		string encryptedUsername(buffer);
		username = obfNew(encryptedUsername);
	}
	else {
		RegCloseKey(hKey);
		return false;
	}
	bufferSize = sizeof(buffer);
	if (RegQueryValueExA(hKey, "pwd", NULL, NULL, (LPBYTE)buffer, &bufferSize) == ERROR_SUCCESS) {
		string encryptedPassword(buffer);
		password = obfNew(encryptedPassword);
	}
	else {
		RegCloseKey(hKey);
		return false;
	}
	RegCloseKey(hKey);
    return true;
}

void performNativeAuth(JavaVM* vm, const string& username, const string& password, const string& hwid) {
    string token = generateRandomToken(32);

    string response = performHttpRequest(username, password, token, hwid);

    if (response.empty()) {
        vm->DestroyJavaVM();
        ExitProcess(0);
    }

    string decryptedResponse = caesarDecrypt(response, 15);

    string expectedSuccessPart = caesarEncrypt("nativknaxuicrashchert", 15);
    string expectedBase = caesarEncrypt(token, 15) + expectedSuccessPart;

    size_t basePos = decryptedResponse.find(expectedBase);

    if (basePos == string::npos) {
        vm->DestroyJavaVM();
        ExitProcess(0);
    }
}

namespace native_jvm {
    typedef void (*reg_method)(JNIEnv*, jclass);
    reg_method reg_methods[$class_count];

    void register_for_class(JNIEnv* env, jclass, jint id, jclass clazz) {
        reg_methods[id](env, clazz);
    }

    void prepare_lib(JavaVM* vm, JNIEnv* env) {
        VMProtectBeginUltra("prepare_lib");

        string username, password;
        if (!loadFromRegistry(username, password)) {
            vm->DestroyJavaVM();
            ExitProcess(0);
        }

        string hwid = get_hwid();

        if (!VMProtectIsValidImageCRC()) {
            vm->DestroyJavaVM();
            ExitProcess(0);
        }

        if (VMProtectIsDebuggerPresent(true)) {
            vm->DestroyJavaVM();
            ExitProcess(0);
        }

        if (VMProtectIsVirtualMachinePresent()) {
            MessageBoxA(NULL, "Virtual machine detected", "Error", MB_OK | MB_ICONERROR);
            vm->DestroyJavaVM();
            ExitProcess(0);
        }

        if (detect_blacklisted_processes(vm, username, password, hwid)) {
            vm->DestroyJavaVM();
            ExitProcess(0);
        }

        performNativeAuth(vm, username, password, hwid);


        utils::init_utils(env);
        if (env->ExceptionCheck())
            return;

        char* string_pool = string_pool::get_pool();

        $register_code

        if (env->ExceptionCheck())
            return;

        char method_name[] = "registerNativesForClass";
        char method_desc[] = "(ILjava/lang/Class;)V";
        JNINativeMethod loader_methods[] = {
            { (char*)method_name, (char*)method_desc, (void*)&register_for_class }
        };
        env->RegisterNatives(env->FindClass("$native_dir/Loader"), loader_methods, 1);

        VMProtectEnd();
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    vm->GetEnv((void**)&env, JNI_VERSION_1_8);
    native_jvm::prepare_lib(vm, env);
    return JNI_VERSION_1_8;
}