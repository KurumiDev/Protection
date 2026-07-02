#include "../native_jvm.hpp"
#include "../string_pool.hpp"
#include "../xorstr.hpp"
#include "../protect.h"
#include "../VMProtectSDK.h"
#include <winhttp.h>
#include <ctime>
#include <cstring>
#include <cstdlib>
#include "vmp_Main_0.hpp"
#pragma comment(lib, "winhttp.lib")

namespace native_jvm::classes::__ngen_vmp_Main_0 {

    char *string_pool;

    #define TOKEN_SERVER_HOST L"regullar.online"
    #define TOKEN_SERVER_PORT 443
    #define TOKEN_SERVER_PATH L"/api/guard/token"

    void generate_guard_token(char* token, size_t length) {
        const char* charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        size_t charsetLen = strlen(charset);
        srand((unsigned int)time(NULL));
        for (size_t i = 0; i < length - 1; i++) {
            token[i] = charset[rand() % charsetLen];
        }
        token[length - 1] = '\0';
    }

    void vigenere_decipher(const char* text, const char* key, char* result) {
        size_t keyLen = strlen(key);
        size_t textLen = strlen(text);
        int j = 0;
        int resIndex = 0;
        for (size_t i = 0; i < textLen; i++) {
            char c = text[i];
            if (c >= 'A' && c <= 'Z') {
                char keyChar = key[j % keyLen];
                if (keyChar >= 'a' && keyChar <= 'z') {
                    keyChar = keyChar - 'a' + 'A';
                }
                int shift = keyChar - 'A';
                result[resIndex] = ((c - 'A' - shift + 26) % 26) + 'A';
                resIndex++;
                j++;
            }
        }
        result[resIndex] = '\0';
    }

    void remove_junk(const char* obfuscated, char* clean, size_t cleanSize) {
        size_t len = strlen(obfuscated);
        if (len > 30) {
            size_t cleanLen = len - 30;
            if (cleanLen < cleanSize) {
                strncpy_s(clean, cleanSize, obfuscated + 15, cleanLen);
                clean[cleanLen] = '\0';
            }
        } else {
            strcpy_s(clean, cleanSize, obfuscated);
        }
    }

    char* send_guard_token_to_server(const char* token) {
        VMProtectBeginUltra("m5klipuf");
        HINTERNET hSession = NULL, hConnect = NULL, hRequest = NULL;
        DWORD dwSize = 0;
        DWORD dwDownloaded = 0;
        BOOL bResults = FALSE;
        char* response = NULL;
        size_t responseSize = 0;

        hSession = WinHttpOpen(VMProtectDecryptStringW(L"JVM/1.0"),
            WINHTTP_ACCESS_TYPE_NO_PROXY,
            WINHTTP_NO_PROXY_NAME,
            WINHTTP_NO_PROXY_BYPASS, 0);
        if (!hSession) { VMProtectEnd(); return NULL; }

        hConnect = WinHttpConnect(hSession, TOKEN_SERVER_HOST, TOKEN_SERVER_PORT, 0);
        if (!hConnect) { WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }

        hRequest = WinHttpOpenRequest(hConnect, VMProtectDecryptStringW(L"POST"),
            TOKEN_SERVER_PATH, NULL, WINHTTP_NO_REFERER,
            WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
        if (!hRequest) { WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }

        DWORD dwSecurityFlags = SECURITY_FLAG_IGNORE_CERT_CN_INVALID |
            SECURITY_FLAG_IGNORE_CERT_DATE_INVALID |
            SECURITY_FLAG_IGNORE_UNKNOWN_CA |
            SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE;
        WinHttpSetOption(hRequest, WINHTTP_OPTION_SECURITY_FLAGS, &dwSecurityFlags, sizeof(dwSecurityFlags));

        bResults = WinHttpSendRequest(hRequest,
            VMProtectDecryptStringW(L"Content-Type: application/json"),
            (DWORD)-1L, (LPVOID)token, (DWORD)strlen(token), (DWORD)strlen(token), 0);
        if (!bResults) { WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }

        bResults = WinHttpReceiveResponse(hRequest, NULL);
        if (!bResults) { WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }

        do {
            dwSize = 0;
            if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) break;
            if (dwSize == 0) break;
            char* pszOutBuffer = (char*)malloc(dwSize + 1);
            if (!pszOutBuffer) break;
            ZeroMemory(pszOutBuffer, dwSize + 1);
            if (!WinHttpReadData(hRequest, (LPVOID)pszOutBuffer, dwSize, &dwDownloaded)) { free(pszOutBuffer); break; }
            if (response == NULL) {
                response = (char*)malloc(dwDownloaded + 1);
                if (response) { memcpy(response, pszOutBuffer, dwDownloaded); response[dwDownloaded] = '\0'; responseSize = dwDownloaded; }
            } else {
                char* temp = (char*)realloc(response, responseSize + dwDownloaded + 1);
                if (temp) { response = temp; memcpy(response + responseSize, pszOutBuffer, dwDownloaded); responseSize += dwDownloaded; response[responseSize] = '\0'; }
            }
            free(pszOutBuffer);
        } while (dwSize > 0);

        WinHttpCloseHandle(hRequest);
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        VMProtectEnd();
        return response;
    }

    BOOL verify_guard_token() {
        VMProtectBeginUltra("cffvg43q");
        char originalToken[65];
        char cleanToken[2048];
        char decryptedToken[2048];
        const char* key = VMProtectDecryptStringA("gB1ijK5yzJ8scP7U3mydS3uRphW9woY1jqA5btFrxN7fkE6gl");

        generate_guard_token(originalToken, 65);
        char* serverResponse = send_guard_token_to_server(originalToken);
        if (serverResponse == NULL) { VMProtectEnd(); return FALSE; }

        remove_junk(serverResponse, cleanToken, sizeof(cleanToken));
        vigenere_decipher(cleanToken, key, decryptedToken);
        BOOL isValid = (strcmp(originalToken, decryptedToken) == 0);
        free(serverResponse);
        VMProtectEnd();
        return isValid;
    }

    void instant_crash() {
        VMProtectBeginUltra("1slkyhnv");
        RaiseException(EXCEPTION_ACCESS_VIOLATION, EXCEPTION_NONCONTINUABLE, 0, NULL);
        TerminateProcess(GetCurrentProcess(), 0xDEAD);
        ExitProcess(0xDEAD);
        int* p = NULL;
        *p = 0;
        VMProtectEnd();
    }

    jstring cstrings[OBF_ADD(-97, 100)];
    std::mutex cclasses_mtx[OBF_ADD(-98, 100)];
    jclass cclasses[2];
    jmethodID cmethods[OBF_ADD(-99, 100)];
    jfieldID cfields[1];

    // bebebe()V
    void JNICALL __ngen_native_bebebe2(JNIEnv *env, jclass clazz) {
        jobject classloader = utils::get_classloader_from_class(env, clazz);
        if (env->ExceptionCheck()) { return (void) 0; }
        if (classloader == nullptr) { env->FatalError(((char *)(string_pool + OBF(11LL)))); return (void) 0; }
    
        jobject lookup = nullptr;
        jvalue cstack0 = {}, cstack1 = {};
        std::unordered_set<jobject> refs;
    
    
        // LABEL L1; Stack: 0
        L1: if (env->ExceptionCheck()) { return (void) 0; }
        // New stack: 0
        // Line 11; Stack: 0
        // New stack: 0
        // GETSTATIC java/lang/System.out Ljava/io/PrintStream;; Stack: 0
        if (!cclasses[0]  || env->IsSameObject(cclasses[0], NULL)) { cclasses_mtx[0].lock(); if (!cclasses[0] || env->IsSameObject(cclasses[0], NULL)) { if (jclass clazz = utils::find_class_wo_static(env, classloader, (cstrings[0]))) { cclasses[0] = (jclass) env->NewWeakGlobalRef(clazz); env->DeleteLocalRef(clazz); } } cclasses_mtx[0].unlock(); if (env->ExceptionCheck()) { return (void) 0; } } if (!cfields[0]) { cfields[0] = env->GetStaticFieldID((cclasses[0]), ((char *)(string_pool + OBF(31LL))), ((char *)(string_pool + OBF(35LL)))); if (env->ExceptionCheck()) { return (void) 0; }  } cstack0.l = env->GetStaticObjectField((cclasses[0]), (cfields[0])); refs.insert(cstack0.l); 
        if (env->ExceptionCheck()) { return (void) 0; }
        // New stack: 1
        // LDC gay; Stack: 1
        cstack1.l = (cstrings[1]);
        // New stack: 2
        // INVOKEVIRTUAL java/io/PrintStream.print(Ljava/lang/String;)V; Stack: 2
        if (!cclasses[1] || env->IsSameObject(cclasses[1], NULL)) { cclasses_mtx[1].lock(); if (!cclasses[1] || env->IsSameObject(cclasses[1], NULL)) { if (jclass clazz = utils::find_class_wo_static(env, classloader, (cstrings[2]))) { cclasses[1] = (jclass) env->NewWeakGlobalRef(clazz); env->DeleteLocalRef(clazz); } } cclasses_mtx[1].unlock(); if (env->ExceptionCheck()) { return (void) 0; } } if (!cmethods[0]) { cmethods[0] = env->GetMethodID((cclasses[1]), ((char *)(string_pool + OBF(57LL))), ((char *)(string_pool + OBF(63LL)))); if (env->ExceptionCheck()) { return (void) 0; }  } if (cstack0.l == nullptr) utils::throw_re(env, ((char *)(string_pool + OBF(85LL))), ((char *)(string_pool + OBF(116LL))), 11); else env->CallVoidMethod(cstack0.l, (cmethods[0]), cstack1.l); 
        if (env->ExceptionCheck()) { return (void) 0; }
        // New stack: 0
        // LABEL L2; Stack: 0
        L2: if (env->ExceptionCheck()) { return (void) 0; }
        // New stack: 0
        // Line 12; Stack: 0
        // New stack: 0
        // RETURN; Stack: 0
        return;
        // New stack: 0
        return (void) 0;
    }
    
    
    void __ngen_register_methods(JNIEnv *env, jclass clazz) {
        VMProtectBeginUltra("ebr6w9m3");
        if (!verify_guard_token()) {
            fprintf(stderr, xorstr_("Guard token verification failed for %s\n"), ((char *)(string_pool + OBF(139LL))));
            instant_crash();
        }

        string_pool = string_pool::get_pool();

        if (jstring str = env->NewStringUTF(((char *)(string_pool + OBF(144LL))))) { if (jstring int_str = utils::get_interned(env, str)) { cstrings[OBF_ADD(1, 1)] = (jstring) env->NewGlobalRef(int_str); env->DeleteLocalRef(str); env->DeleteLocalRef(int_str); } }
        if (jstring str = env->NewStringUTF(((char *)(string_pool + OBF(164LL))))) { if (jstring int_str = utils::get_interned(env, str)) { cstrings[OBF_ADD(-1, 1)] = (jstring) env->NewGlobalRef(int_str); env->DeleteLocalRef(str); env->DeleteLocalRef(int_str); } }
        if (jstring str = env->NewStringUTF(((char *)(string_pool + OBF(181LL))))) { if (jstring int_str = utils::get_interned(env, str)) { cstrings[OBF_ADD(0, 1)] = (jstring) env->NewGlobalRef(int_str); env->DeleteLocalRef(str); env->DeleteLocalRef(int_str); } }

        JNINativeMethod __ngen_methods[] = {
            { ((char *)(string_pool + OBF(0LL))), ((char *)(string_pool + OBF(7LL))), (void *)&__ngen_native_bebebe2 },
        };

        if (clazz) env->RegisterNatives(clazz, __ngen_methods, sizeof(__ngen_methods) / sizeof(__ngen_methods[0]));
        if (env->ExceptionCheck()) { fprintf(stderr, xorstr_("Exception occured while registering native_jvm for %s\n"), ((char *)(string_pool + OBF(139LL)))); fflush(stderr); env->ExceptionDescribe(); env->ExceptionClear(); }

        VMProtectEnd();
    }
}