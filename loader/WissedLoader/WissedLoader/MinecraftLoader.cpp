#define _CRT_SECURE_NO_WARNINGS
#include "MinecraftLoader.h"
#include <windows.h>
#include <iostream>
#include <sstream>
#include <dxgi.h>
#include <comdef.h>
#include <atomic>
#pragma comment(lib, "dxgi.lib")

extern "C" {
    __declspec(dllexport) ULONG NvOptimusEnablement = 0x00000001;
    __declspec(dllexport) ULONG AmdPowerXpressRequestHighPerformance = 0x00000001;
}

static std::atomic<bool> g_hasLaunched(false);

MinecraftLoader::MinecraftLoader() {
    isVMRunning = false;
    jvm = nullptr;
    env = nullptr;
}

MinecraftLoader::~MinecraftLoader() {
    cleanupVM();
}

std::string MinecraftLoader::getAllJarFiles(const std::string& libsPath) {
    std::string jarFiles = "";
    WIN32_FIND_DATAA findData;
    std::string searchPath = libsPath + "\\*.jar";

    HANDLE hFind = FindFirstFileA(searchPath.c_str(), &findData);
    if (hFind == INVALID_HANDLE_VALUE) {
        return jarFiles;
    }

    do {
        if (!(findData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
            if (!jarFiles.empty()) {
                jarFiles += ";";
            }
            jarFiles += libsPath + "\\" + findData.cFileName;
        }
    } while (FindNextFileA(hFind, &findData));

    FindClose(hFind);
    return jarFiles;
}
bool MinecraftLoader::setDiscreteGPU() {
    HRESULT hr;
    IDXGIFactory* factory = nullptr;
    IDXGIAdapter* adapter = nullptr;
    bool discreteGPUFound = false;

    hr = CreateDXGIFactory(__uuidof(IDXGIFactory), (void**)&factory);
    if (FAILED(hr)) {
        return false;
    }

    for (UINT i = 0; factory->EnumAdapters(i, &adapter) != DXGI_ERROR_NOT_FOUND; ++i) {
        DXGI_ADAPTER_DESC desc;
        adapter->GetDesc(&desc);
        if (wcsstr(desc.Description, L"NVIDIA") || wcscmp(desc.Description, L"Microsoft Basic Render Driver") != 0) {
            discreteGPUFound = true;
            break;
        }
        adapter->Release();
        adapter = nullptr;
    }

    if (factory) {
        factory->Release();
    }
    if (adapter) {
        adapter->Release();
    }

    HMODULE d3d11 = LoadLibraryA("d3d11.dll");
    if (d3d11) {
        FreeLibrary(d3d11);
    }

    SetEnvironmentVariableA("SHIM_MCCOMPAT", "0x800000001");
    SetEnvironmentVariableA("__COMPAT_LAYER", "HIGHDPIAWARE");
    SetEnvironmentVariableA("__GL_SHADER_DISK_CACHE", "1");
    SetEnvironmentVariableA("__GL_THREADED_OPTIMIZATIONS", discreteGPUFound ? "1" : "0");
    SetEnvironmentVariableA("__GL_SYNC_TO_VBLANK", "0");
    SetEnvironmentVariableA("CUDA_CACHE_DISABLE", "0");
    SetEnvironmentVariableA("GPU_MAX_ALLOC_PERCENT", "100");
    SetEnvironmentVariableA("GPU_SINGLE_ALLOC_PERCENT", "100");
    SetEnvironmentVariableA("GPU_MAX_HEAP_SIZE", "100");
    SetEnvironmentVariableA("GPU_USE_SYNC_OBJECTS", discreteGPUFound ? "1" : "0");
    SetEnvironmentVariableA("NVIDIA_FORCE_PROFILE", "1");

    return true;
}

bool MinecraftLoader::initializeJVM(const std::string& ram) {
    if (jvm != nullptr) {
        return true;
    }

    std::string jvmDllPath = "C:\\Wissend\\jvm\\bin\\server\\jvm.dll";

    HMODULE jvmLib = LoadLibraryA(jvmDllPath.c_str());
    if (!jvmLib) {
        MessageBoxA(NULL, "Failed to load jvm.dll.", "Error", MB_ICONERROR | MB_OK);
        return false;
    }

    typedef jint(JNICALL* CreateJavaVMFunc)(JavaVM**, void**, void*);
    CreateJavaVMFunc createJavaVM = (CreateJavaVMFunc)GetProcAddress(jvmLib, "JNI_CreateJavaVM");

    if (!createJavaVM) {
        MessageBoxA(NULL, "Failed to get JNI_CreateJavaVM function.", "Error", MB_ICONERROR | MB_OK);
        FreeLibrary(jvmLib);
        return false;
    }

    std::string clientJarPath = "C:\\Wissend\\client.jar";
    std::string libsPath = "C:\\Wissend\\libs";
    std::string nativesPath = "C:\\Wissend\\natives";
    std::string allLibs = getAllJarFiles(libsPath);

    std::string classpath = "-Djava.class.path=" + clientJarPath;
    if (!allLibs.empty()) {
        classpath += ";" + allLibs;
    }

    std::string libraryPath = "-Djava.library.path=" + nativesPath;

    std::string xmsParam = "-Xms" + ram + "M";
    std::string xmxParam = "-Xmx" + ram + "M";

    JavaVMOption options[10];
    options[0].optionString = const_cast<char*>(classpath.c_str());
    options[1].optionString = const_cast<char*>(libraryPath.c_str());
    options[2].optionString = const_cast<char*>(xmsParam.c_str());
    options[3].optionString = const_cast<char*>(xmxParam.c_str());
    options[4].optionString = const_cast<char*>("-Xverify:none");
    options[5].optionString = const_cast<char*>("-Djava.awt.headless=false");
    options[6].optionString = const_cast<char*>("-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=false");
    options[7].optionString = const_cast<char*>("-Dorg.lwjgl.util.Debug=true");
    options[8].optionString = const_cast<char*>("-XX:+UseG1GC");
    options[9].optionString = const_cast<char*>("-Dsun.java2d.d3d=true");

    JavaVMInitArgs vmArgs;
    vmArgs.version = JNI_VERSION_1_8;
    vmArgs.nOptions = 10;
    vmArgs.options = options;
    vmArgs.ignoreUnrecognized = JNI_FALSE;

    jint result = createJavaVM(&jvm, (void**)&env, &vmArgs);

    if (result != JNI_OK) {
        std::cerr << "Failed to create JVM: " << result << std::endl;
        FreeLibrary(jvmLib);
        return false;
    }

    return true;
}

void LogToFile(const std::string& msg) {
    FILE* f = fopen("C:\\Wissend\\launch_log.txt", "a");
    if (f) {
        fprintf(f, "%s\n", msg.c_str());
        fclose(f);
    }
}

bool MinecraftLoader::launchMinecraft(const std::string& username, const std::string& ram) {
    HANDLE hMutex = CreateMutexA(NULL, FALSE, "Global\\WissendMinecraftLaunchMutex");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        CloseHandle(hMutex);
        return false;
    }

    system("cls");
    std::cout << "Starting Minecraft..." << std::endl;
    Sleep(2000);

    if (!setDiscreteGPU()) {
        CloseHandle(hMutex);
        return false;
    }

    if (!initializeJVM(ram)) {
        CloseHandle(hMutex);
        return false;
    }

    jclass mainClass = env->FindClass("net/fabricmc/loader/impl/launch/knot/KnotClient");
    if (!mainClass) {
        cleanupVM();
        CloseHandle(hMutex);
        return false;
    }

    jmethodID mainMethod = env->GetStaticMethodID(mainClass, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        cleanupVM();
        CloseHandle(hMutex);
        return false;
    }

    jobjectArray argsArray = env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);

    isVMRunning = true;

    std::thread minecraftThread([this, mainClass, mainMethod, argsArray]() {
        JNIEnv* threadEnv;
        jvm->AttachCurrentThread((void**)&threadEnv, nullptr);

        threadEnv->CallStaticVoidMethod(mainClass, mainMethod, argsArray);

        threadEnv->DeleteLocalRef(argsArray);
        threadEnv->DeleteLocalRef(mainClass);
        jvm->DetachCurrentThread();
        });

    minecraftThread.detach();

    HWND hwnd = GetConsoleWindow();
    ShowWindow(hwnd, SW_HIDE);

    return true;
}

bool MinecraftLoader::isMinecraftRunning() {
    if (!isVMRunning || !jvm) {
        return false;
    }
    return true;
}

void MinecraftLoader::terminateMinecraft() {
    if (isVMRunning && jvm) {
        std::cout << "Terminating JVM..." << std::endl;
        cleanupVM();
        std::cout << "JVM terminated." << std::endl;
    }
    else {
        MessageBoxA(NULL, "VM is not running.", "Info", MB_ICONINFORMATION | MB_OK);
    }
}

std::string MinecraftLoader::getVMStatus() {
    if (isMinecraftRunning()) {
        return "VM Running";
    }
    else {
        return "VM Stopped";
    }
}

void MinecraftLoader::cleanupVM() {
    if (jvm) {
        jvm->DestroyJavaVM();
        jvm = nullptr;
        env = nullptr;
    }
    isVMRunning = false;
}