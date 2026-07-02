#pragma once
#include <string>
#include <windows.h>
#include <wininet.h>
#pragma comment(lib, "wininet.lib")

class Downloader {
private:
    static const std::string DOWNLOAD_URL;
    static const std::string EXTRACT_PATH;
    static const std::string JAR_DOWNLOAD_URL;
    static const std::string JAR_PATH;
    static const std::string VERSION_CHECK_URL;

    std::string getTempPath();
    bool downloadFile(const std::string& url, const std::string& outputPath);
    bool extractZip(const std::string& zipPath, const std::string& extractPath);
    bool checkFolders();
    ULONGLONG getFolderSize(const std::string& path);
    void deleteFolders();

    int getServerVersion();
    int getLocalVersion();
    void saveVersion(int version);
    bool downloadJar();
    void checkAndUpdateJar();

public:
    void run();
};