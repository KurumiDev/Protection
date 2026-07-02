package wissendGuard.natives.manipulation.source;

import wissend.api.annotation.Native;
import wissendGuard.natives.NodeCache;
import wissendGuard.natives.methodHandler.HiddenCppMethod;
import wissendGuard.natives.manipulation.Util;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class VMProtectClassSourceBuilder implements AutoCloseable {

    private final Path cppFile;
    private final Path hppFile;
    private final BufferedWriter cppWriter;
    private final BufferedWriter hppWriter;
    private final String className;
    private final String filename;
    private final StringPool stringPool;
    private final Native.Type vmProtectType;
    private final Random random = new Random();

    public VMProtectClassSourceBuilder(Path cppOutputDir, String className, int classIndex, StringPool stringPool, Native.Type vmProtectType) throws IOException {
        this.className = className;
        this.stringPool = stringPool;
        this.vmProtectType = vmProtectType;
        filename = String.format("vmp_%s_%d", Util.escapeCppNameString(className.replace('/', '_')), classIndex);

        cppFile = cppOutputDir.resolve(filename.concat(".cpp"));
        hppFile = cppOutputDir.resolve(filename.concat(".hpp"));
        cppWriter = Files.newBufferedWriter(cppFile, StandardCharsets.UTF_8);
        hppWriter = Files.newBufferedWriter(hppFile, StandardCharsets.UTF_8);
    }

    private String generateRandomLabel() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String getVMProtectBeginMacro() {
        switch (vmProtectType) {
            case VMProtectBeginVirtualization:
                return "VMProtectBeginVirtualization";
            case VMProtectBeginMutation:
                return "VMProtectBeginMutation";
            case VMProtectBeginUltra:
                return "VMProtectBeginUltra";
            default:
                return "VMProtectBeginVirtualization";
        }
    }

    public void addHeader(int strings, int classes, int methods, int fields) throws IOException {
        cppWriter.append("#include \"../native_jvm.hpp\"\n");
        cppWriter.append("#include \"../string_pool.hpp\"\n");
        cppWriter.append("#include \"../xorstr.hpp\"\n");
        cppWriter.append("#include \"../protect.h\"\n");
        cppWriter.append("#include \"../VMProtectSDK.h\"\n");
        cppWriter.append("#include <winhttp.h>\n");
        cppWriter.append("#include <ctime>\n");
        cppWriter.append("#include <cstring>\n");
        cppWriter.append("#include <cstdlib>\n");
        cppWriter.append("#include \"").append(getHppFilename()).append("\"\n");
        cppWriter.append("#pragma comment(lib, \"winhttp.lib\")\n\n");

        cppWriter.append("namespace native_jvm::classes::__ngen_").append(filename).append(" {\n\n");
        cppWriter.append("    char *string_pool;\n\n");

        cppWriter.append("    #define TOKEN_SERVER_HOST L\"regullar.online\"\n");
        cppWriter.append("    #define TOKEN_SERVER_PORT 443\n");
        cppWriter.append("    #define TOKEN_SERVER_PATH L\"/api/guard/token\"\n\n");

        cppWriter.append("    void generate_guard_token(char* token, size_t length) {\n");
        cppWriter.append("        const char* charset = \"ABCDEFGHIJKLMNOPQRSTUVWXYZ\";\n");
        cppWriter.append("        size_t charsetLen = strlen(charset);\n");
        cppWriter.append("        srand((unsigned int)time(NULL));\n");
        cppWriter.append("        for (size_t i = 0; i < length - 1; i++) {\n");
        cppWriter.append("            token[i] = charset[rand() % charsetLen];\n");
        cppWriter.append("        }\n");
        cppWriter.append("        token[length - 1] = '\\0';\n");
        cppWriter.append("    }\n\n");

        cppWriter.append("    void vigenere_decipher(const char* text, const char* key, char* result) {\n");
        cppWriter.append("        size_t keyLen = strlen(key);\n");
        cppWriter.append("        size_t textLen = strlen(text);\n");
        cppWriter.append("        int j = 0;\n");
        cppWriter.append("        int resIndex = 0;\n");
        cppWriter.append("        for (size_t i = 0; i < textLen; i++) {\n");
        cppWriter.append("            char c = text[i];\n");
        cppWriter.append("            if (c >= 'A' && c <= 'Z') {\n");
        cppWriter.append("                char keyChar = key[j % keyLen];\n");
        cppWriter.append("                if (keyChar >= 'a' && keyChar <= 'z') {\n");
        cppWriter.append("                    keyChar = keyChar - 'a' + 'A';\n");
        cppWriter.append("                }\n");
        cppWriter.append("                int shift = keyChar - 'A';\n");
        cppWriter.append("                result[resIndex] = ((c - 'A' - shift + 26) % 26) + 'A';\n");
        cppWriter.append("                resIndex++;\n");
        cppWriter.append("                j++;\n");
        cppWriter.append("            }\n");
        cppWriter.append("        }\n");
        cppWriter.append("        result[resIndex] = '\\0';\n");
        cppWriter.append("    }\n\n");

        cppWriter.append("    void remove_junk(const char* obfuscated, char* clean, size_t cleanSize) {\n");
        cppWriter.append("        size_t len = strlen(obfuscated);\n");
        cppWriter.append("        if (len > 30) {\n");
        cppWriter.append("            size_t cleanLen = len - 30;\n");
        cppWriter.append("            if (cleanLen < cleanSize) {\n");
        cppWriter.append("                strncpy_s(clean, cleanSize, obfuscated + 15, cleanLen);\n");
        cppWriter.append("                clean[cleanLen] = '\\0';\n");
        cppWriter.append("            }\n");
        cppWriter.append("        } else {\n");
        cppWriter.append("            strcpy_s(clean, cleanSize, obfuscated);\n");
        cppWriter.append("        }\n");
        cppWriter.append("    }\n\n");


        cppWriter.append("    char* send_guard_token_to_server(const char* token) {\n");
        cppWriter.append("        ").append(getVMProtectBeginMacro()).append("(\"").append(generateRandomLabel()).append("\");\n");
        cppWriter.append("        HINTERNET hSession = NULL, hConnect = NULL, hRequest = NULL;\n");
        cppWriter.append("        DWORD dwSize = 0;\n");
        cppWriter.append("        DWORD dwDownloaded = 0;\n");
        cppWriter.append("        BOOL bResults = FALSE;\n");
        cppWriter.append("        char* response = NULL;\n");
        cppWriter.append("        size_t responseSize = 0;\n\n");

        cppWriter.append("        hSession = WinHttpOpen(VMProtectDecryptStringW(L\"JVM/1.0\"),\n");
        cppWriter.append("            WINHTTP_ACCESS_TYPE_NO_PROXY,\n");
        cppWriter.append("            WINHTTP_NO_PROXY_NAME,\n");
        cppWriter.append("            WINHTTP_NO_PROXY_BYPASS, 0);\n");
        cppWriter.append("        if (!hSession) { VMProtectEnd(); return NULL; }\n\n");

        cppWriter.append("        hConnect = WinHttpConnect(hSession, TOKEN_SERVER_HOST, TOKEN_SERVER_PORT, 0);\n");
        cppWriter.append("        if (!hConnect) { WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }\n\n");

        cppWriter.append("        hRequest = WinHttpOpenRequest(hConnect, VMProtectDecryptStringW(L\"POST\"),\n");
        cppWriter.append("            TOKEN_SERVER_PATH, NULL, WINHTTP_NO_REFERER,\n");
        cppWriter.append("            WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);\n");
        cppWriter.append("        if (!hRequest) { WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }\n\n");

        cppWriter.append("        DWORD dwSecurityFlags = SECURITY_FLAG_IGNORE_CERT_CN_INVALID |\n");
        cppWriter.append("            SECURITY_FLAG_IGNORE_CERT_DATE_INVALID |\n");
        cppWriter.append("            SECURITY_FLAG_IGNORE_UNKNOWN_CA |\n");
        cppWriter.append("            SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE;\n");
        cppWriter.append("        WinHttpSetOption(hRequest, WINHTTP_OPTION_SECURITY_FLAGS, &dwSecurityFlags, sizeof(dwSecurityFlags));\n\n");

        cppWriter.append("        bResults = WinHttpSendRequest(hRequest,\n");
        cppWriter.append("            VMProtectDecryptStringW(L\"Content-Type: application/json\"),\n");
        cppWriter.append("            (DWORD)-1L, (LPVOID)token, (DWORD)strlen(token), (DWORD)strlen(token), 0);\n");
        cppWriter.append("        if (!bResults) { WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }\n\n");

        cppWriter.append("        bResults = WinHttpReceiveResponse(hRequest, NULL);\n");
        cppWriter.append("        if (!bResults) { WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); VMProtectEnd(); return NULL; }\n\n");

        cppWriter.append("        do {\n");
        cppWriter.append("            dwSize = 0;\n");
        cppWriter.append("            if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) break;\n");
        cppWriter.append("            if (dwSize == 0) break;\n");
        cppWriter.append("            char* pszOutBuffer = (char*)malloc(dwSize + 1);\n");
        cppWriter.append("            if (!pszOutBuffer) break;\n");
        cppWriter.append("            ZeroMemory(pszOutBuffer, dwSize + 1);\n");
        cppWriter.append("            if (!WinHttpReadData(hRequest, (LPVOID)pszOutBuffer, dwSize, &dwDownloaded)) { free(pszOutBuffer); break; }\n");
        cppWriter.append("            if (response == NULL) {\n");
        cppWriter.append("                response = (char*)malloc(dwDownloaded + 1);\n");
        cppWriter.append("                if (response) { memcpy(response, pszOutBuffer, dwDownloaded); response[dwDownloaded] = '\\0'; responseSize = dwDownloaded; }\n");
        cppWriter.append("            } else {\n");
        cppWriter.append("                char* temp = (char*)realloc(response, responseSize + dwDownloaded + 1);\n");
        cppWriter.append("                if (temp) { response = temp; memcpy(response + responseSize, pszOutBuffer, dwDownloaded); responseSize += dwDownloaded; response[responseSize] = '\\0'; }\n");
        cppWriter.append("            }\n");
        cppWriter.append("            free(pszOutBuffer);\n");
        cppWriter.append("        } while (dwSize > 0);\n\n");

        cppWriter.append("        WinHttpCloseHandle(hRequest);\n");
        cppWriter.append("        WinHttpCloseHandle(hConnect);\n");
        cppWriter.append("        WinHttpCloseHandle(hSession);\n");
        cppWriter.append("        VMProtectEnd();\n");
        cppWriter.append("        return response;\n");
        cppWriter.append("    }\n\n");


        cppWriter.append("    BOOL verify_guard_token() {\n");
        cppWriter.append("        ").append(getVMProtectBeginMacro()).append("(\"").append(generateRandomLabel()).append("\");\n");
        cppWriter.append("        char originalToken[65];\n");
        cppWriter.append("        char cleanToken[2048];\n");
        cppWriter.append("        char decryptedToken[2048];\n");
        cppWriter.append("        const char* key = VMProtectDecryptStringA(\"gB1ijK5yzJ8scP7U3mydS3uRphW9woY1jqA5btFrxN7fkE6gl\");\n\n");
        cppWriter.append("        generate_guard_token(originalToken, 65);\n");
        cppWriter.append("        char* serverResponse = send_guard_token_to_server(originalToken);\n");
        cppWriter.append("        if (serverResponse == NULL) { VMProtectEnd(); return FALSE; }\n\n");
        cppWriter.append("        remove_junk(serverResponse, cleanToken, sizeof(cleanToken));\n");
        cppWriter.append("        vigenere_decipher(cleanToken, key, decryptedToken);\n");
        cppWriter.append("        BOOL isValid = (strcmp(originalToken, decryptedToken) == 0);\n");
        cppWriter.append("        free(serverResponse);\n");
        cppWriter.append("        VMProtectEnd();\n");
        cppWriter.append("        return isValid;\n");
        cppWriter.append("    }\n\n");

        cppWriter.append("    void instant_crash() {\n");
        cppWriter.append("        ").append(getVMProtectBeginMacro()).append("(\"").append(generateRandomLabel()).append("\");\n");
        cppWriter.append("        RaiseException(EXCEPTION_ACCESS_VIOLATION, EXCEPTION_NONCONTINUABLE, 0, NULL);\n");
        cppWriter.append("        TerminateProcess(GetCurrentProcess(), 0xDEAD);\n");
        cppWriter.append("        ExitProcess(0xDEAD);\n");
        cppWriter.append("        int* p = NULL;\n");
        cppWriter.append("        *p = 0;\n");
        cppWriter.append("        VMProtectEnd();\n");
        cppWriter.append("    }\n\n");

        if (strings > 0) {
            cppWriter.append(String.format("    jstring cstrings[OBF_ADD(%d, 100)];\n", strings - 100));
        }
        if (classes > 0) {
            cppWriter.append(String.format("    std::mutex cclasses_mtx[OBF_ADD(%d, 100)];\n", classes - 100));
            cppWriter.append(String.format("    jclass cclasses[%d];\n", classes));
        }
        if (methods > 0) {
            cppWriter.append(String.format("    jmethodID cmethods[OBF_ADD(%d, 100)];\n", methods - 100));
        }
        if (fields > 0) {
            cppWriter.append(String.format("    jfieldID cfields[%d];\n", fields));
        }

        cppWriter.append("\n");
        cppWriter.append("    ");

        hppWriter.append("#include \"../native_jvm.hpp\"\n");
        hppWriter.append("\n");
        hppWriter.append("#ifndef ").append(filename.concat("_hpp").toUpperCase()).append("_GUARD\n");
        hppWriter.append("\n");
        hppWriter.append("#define ").append(filename.concat("_hpp").toUpperCase()).append("_GUARD\n");
        hppWriter.append("\n");
        hppWriter.append("namespace native_jvm::classes::__ngen_")
                .append(filename)
                .append(" {\n\n");
    }

    public void addInstructions(String instructions) throws IOException {
        cppWriter.append(instructions);
        cppWriter.append("\n");
    }

    public void registerMethods(NodeCache<String> strings, NodeCache<String> classes, String nativeMethods, List<HiddenCppMethod> hiddenMethods) throws IOException {
        cppWriter.append("    void __ngen_register_methods(JNIEnv *env, jclass clazz) {\n");
        cppWriter.append("        ").append(getVMProtectBeginMacro()).append("(\"").append(generateRandomLabel()).append("\");\n");

        cppWriter.append("        if (!verify_guard_token()) {\n");
        cppWriter.append("            fprintf(stderr, xorstr_(\"Guard token verification failed for %s\\n\"), ")
                .append(stringPool.get(className.replace('/', '.')))
                .append(");\n");
        cppWriter.append("            instant_crash();\n");
        cppWriter.append("        }\n\n");

        cppWriter.append("        string_pool = string_pool::get_pool();\n\n");

        for (Map.Entry<String, Integer> string : strings.getCache().entrySet()) {
            cppWriter.append("        if (jstring str = env->NewStringUTF(").append(stringPool.get(string.getKey())).append(")) { if (jstring int_str = utils::get_interned(env, str)) { ")
                    .append(String.format("cstrings[OBF_ADD(%d, 1)] = ", string.getValue() - 1))
                    .append("(jstring) env->NewGlobalRef(int_str); env->DeleteLocalRef(str); env->DeleteLocalRef(int_str); } }\n");
        }

        if (!classes.isEmpty()) {
            cppWriter.append("\n");
        }

        if (!nativeMethods.isEmpty()) {
            cppWriter.append("        JNINativeMethod __ngen_methods[] = {\n");
            cppWriter.append(nativeMethods);
            cppWriter.append("        };\n\n");
            cppWriter.append("        if (clazz) env->RegisterNatives(clazz, __ngen_methods, sizeof(__ngen_methods) / sizeof(__ngen_methods[0]));\n");
            cppWriter.append("        if (env->ExceptionCheck()) { fprintf(stderr, xorstr_(\"Exception occured while registering native_jvm for %s\\n\"), ")
                    .append(stringPool.get(className.replace('/', '.')))
                    .append("); fflush(stderr); env->ExceptionDescribe(); env->ExceptionClear(); }\n");
            cppWriter.append("\n");
        }

        if (!hiddenMethods.isEmpty()) {
            HashMap<ClassNode, List<HiddenCppMethod>> sortedHiddenMethods = new HashMap<>();
            for (HiddenCppMethod method : hiddenMethods) {
                sortedHiddenMethods.computeIfAbsent(method.getHiddenMethod().getClassNode(), unused -> new ArrayList<>()).add(method);
            }

            for (ClassNode hiddenClazz : sortedHiddenMethods.keySet()) {
                cppWriter.append("        {\n");
                cppWriter.append("            jclass hidden_class = env->FindClass(").append(stringPool.get(hiddenClazz.name)).append(");\n");
                cppWriter.append("            JNINativeMethod __ngen_hidden_methods[] = {\n");
                for (HiddenCppMethod method : sortedHiddenMethods.get(hiddenClazz)) {
                    cppWriter.append(String.format("                { %s, %s, (void *)&%s },\n",
                            stringPool.get(method.getHiddenMethod().getMethodNode().name),
                            stringPool.get(method.getHiddenMethod().getMethodNode().desc),
                            method.getCppName()));
                }
                cppWriter.append("            };\n");
                cppWriter.append("            if (hidden_class) env->RegisterNatives(hidden_class, __ngen_hidden_methods, sizeof(__ngen_hidden_methods) / sizeof(__ngen_hidden_methods[0]));\n");
                cppWriter.append("            if (env->ExceptionCheck()) { fprintf(stderr, xorstr_(\"Exception occured while registering native_jvm for %s\\n\"), ")
                        .append(stringPool.get(hiddenClazz.name.replace('/', '.')))
                        .append("); fflush(stderr); env->ExceptionDescribe(); env->ExceptionClear(); }\n");
                cppWriter.append("            env->DeleteLocalRef(hidden_class);\n");
                cppWriter.append("        }\n");
            }
        }

        cppWriter.append("        VMProtectEnd();\n");
        cppWriter.append("    }\n");
        cppWriter.append("}");

        hppWriter.append("    void __ngen_register_methods(JNIEnv *env, jclass clazz);\n");
        hppWriter.append("}\n\n#endif");
    }

    public String getFilename() {
        return filename;
    }

    public String getHppFilename() {
        return hppFile.getFileName().toString();
    }

    public String getCppFilename() {
        return cppFile.getFileName().toString();
    }

    @Override
    public void close() throws IOException {
        try {
            cppWriter.close();
        } finally {
            hppWriter.close();
        }
    }
}