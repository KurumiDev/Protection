#pragma once
#include <string>
#include <vector>
#include <thread>
#include <windows.h>

#include <jni.h>

class MinecraftLoader {
private:
    JavaVM* jvm;
    JNIEnv* env;
    bool isVMRunning;
    std::string getAllJarFiles(const std::string& libsPath);
    bool setDiscreteGPU();
    bool initializeJVM(const std::string& ram);
    void cleanupVM();
public:
    MinecraftLoader();
    ~MinecraftLoader();
    bool launchMinecraft(const std::string& username, const std::string& ram);
    bool isMinecraftRunning();
    void terminateMinecraft();
    std::string getVMStatus();
};