#include "Download.h"
#include "unzip.hpp"
#include <iostream>
#include <fstream>
#include <sstream>

const std::string Downloader::DOWNLOAD_URL = "http://lightclient.tech/files/client.zip";
const std::string Downloader::EXTRACT_PATH = "C:\\Wissend\\";
const std::string Downloader::JAR_DOWNLOAD_URL = "http://lightclient.tech/files/client.jar";
const std::string Downloader::JAR_PATH = "C:\\Wissend\\client.jar";
const std::string Downloader::VERSION_CHECK_URL = "http://localhost:1000/main/clientVersion";

std::string Downloader::getTempPath() {
    char tempPath[MAX_PATH];
    GetTempPathA(MAX_PATH, tempPath);
    return std::string(tempPath) + "client.zip";
}

ULONGLONG Downloader::getFolderSize(const std::string& path) {
    ULONGLONG size = 0;
    WIN32_FIND_DATAA findData;
    std::string searchPath = path + "\\*";

    HANDLE hFind = FindFirstFileA(searchPath.c_str(), &findData);
    if (hFind == INVALID_HANDLE_VALUE) return 0;

    do {
        if (strcmp(findData.cFileName, ".") == 0 || strcmp(findData.cFileName, "..") == 0) {
            continue;
        }

        std::string fullPath = path + "\\" + findData.cFileName;

        if (findData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            size += getFolderSize(fullPath);
        }
        else {
            LARGE_INTEGER fileSize;
            fileSize.LowPart = findData.nFileSizeLow;
            fileSize.HighPart = findData.nFileSizeHigh;
            size += fileSize.QuadPart;
        }
    } while (FindNextFileA(hFind, &findData));

    FindClose(hFind);
    return size;
}

bool Downloader::checkFolders() {
    std::string assets = EXTRACT_PATH + "assets";
    std::string jvm = EXTRACT_PATH + "jvm";
    std::string libs = EXTRACT_PATH + "libs";

    DWORD attr1 = GetFileAttributesA(assets.c_str());
    DWORD attr2 = GetFileAttributesA(jvm.c_str());
    DWORD attr3 = GetFileAttributesA(libs.c_str());

    if (attr1 == INVALID_FILE_ATTRIBUTES || attr2 == INVALID_FILE_ATTRIBUTES || attr3 == INVALID_FILE_ATTRIBUTES) {
        return false;
    }

    ULONGLONG minSize = 5 * 1024 * 1024;

    if (getFolderSize(assets) < minSize ||
        getFolderSize(jvm) < minSize ||
        getFolderSize(libs) < minSize) {
        return false;
    }

    return true;
}

void deleteFolder(const std::string& path) {
    WIN32_FIND_DATAA findData;
    std::string searchPath = path + "\\*";

    HANDLE hFind = FindFirstFileA(searchPath.c_str(), &findData);
    if (hFind == INVALID_HANDLE_VALUE) return;

    do {
        if (strcmp(findData.cFileName, ".") == 0 || strcmp(findData.cFileName, "..") == 0) {
            continue;
        }

        std::string fullPath = path + "\\" + findData.cFileName;

        if (findData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            deleteFolder(fullPath);
        }
        else {
            DeleteFileA(fullPath.c_str());
        }
    } while (FindNextFileA(hFind, &findData));

    FindClose(hFind);
    RemoveDirectoryA(path.c_str());
}

void Downloader::deleteFolders() {
    std::string assets = EXTRACT_PATH + "assets";
    std::string jvm = EXTRACT_PATH + "jvm";
    std::string libs = EXTRACT_PATH + "libs";

    if (GetFileAttributesA(assets.c_str()) != INVALID_FILE_ATTRIBUTES) {
        deleteFolder(assets);
    }
    if (GetFileAttributesA(jvm.c_str()) != INVALID_FILE_ATTRIBUTES) {
        deleteFolder(jvm);
    }
    if (GetFileAttributesA(libs.c_str()) != INVALID_FILE_ATTRIBUTES) {
        deleteFolder(libs);
    }
}

bool Downloader::downloadFile(const std::string& url, const std::string& outputPath) {
    HINTERNET hInternet = InternetOpenA("Downloader", INTERNET_OPEN_TYPE_DIRECT, NULL, NULL, 0);
    if (!hInternet) return false;

    HINTERNET hUrl = InternetOpenUrlA(hInternet, url.c_str(), NULL, 0, INTERNET_FLAG_RELOAD, 0);
    if (!hUrl) {
        InternetCloseHandle(hInternet);
        return false;
    }

    DWORD fileSize = 0;
    DWORD bufferSize = sizeof(fileSize);
    HttpQueryInfoA(hUrl, HTTP_QUERY_CONTENT_LENGTH | HTTP_QUERY_FLAG_NUMBER, &fileSize, &bufferSize, NULL);

    std::ofstream outFile(outputPath, std::ios::binary);
    if (!outFile.is_open()) {
        InternetCloseHandle(hUrl);
        InternetCloseHandle(hInternet);
        return false;
    }

    char buffer[4096];
    DWORD bytesRead;
    DWORD totalDownloaded = 0;

    while (InternetReadFile(hUrl, buffer, sizeof(buffer), &bytesRead) && bytesRead > 0) {
        outFile.write(buffer, bytesRead);
        totalDownloaded += bytesRead;

        if (fileSize > 0) {
            int percentage = (int)((double)totalDownloaded / fileSize * 100.0);
            std::cout << "\rDownloading: " << percentage << "%";
        }
    }

    std::cout << std::endl;
    outFile.close();
    InternetCloseHandle(hUrl);
    InternetCloseHandle(hInternet);

    return true;
}

bool Downloader::extractZip(const std::string& zipPath, const std::string& extractPath) {
    CreateDirectoryA(extractPath.c_str(), NULL);

#ifdef UNICODE
    int wlen = MultiByteToWideChar(CP_ACP, 0, zipPath.c_str(), -1, nullptr, 0);
    if (wlen <= 0) return false;
    TCHAR* wzipPath = new TCHAR[wlen];
    MultiByteToWideChar(CP_ACP, 0, zipPath.c_str(), -1, wzipPath, wlen);
    HZIP hz = OpenZip(wzipPath, nullptr);
    delete[] wzipPath;
#else
    HZIP hz = OpenZip(zipPath.c_str(), nullptr);
#endif

    if (hz == 0) return false;

    ZIPENTRY ze;
    ZRESULT zr = GetZipItem(hz, -1, &ze);
    if (zr != ZR_OK) {
        CloseZip(hz);
        return false;
    }

    int numItems = ze.index;

    for (int i = 0; i < numItems; i++) {
        zr = GetZipItem(hz, i, &ze);
        if (zr != ZR_OK) continue;

        std::string fileName;
#ifdef UNICODE
        int len = WideCharToMultiByte(CP_ACP, 0, ze.name, -1, nullptr, 0, nullptr, nullptr);
        if (len > 0) {
            char* buffer = new char[len];
            WideCharToMultiByte(CP_ACP, 0, ze.name, -1, buffer, len, nullptr, nullptr);
            fileName = std::string(buffer);
            delete[] buffer;
        }
#else
        fileName = std::string(ze.name);
#endif

        if (fileName.empty()) continue;

        std::string fullPath = extractPath + fileName;

        int percentage = (int)((double)(i + 1) / numItems * 100.0);
        std::cout << "\rExtracting: " << percentage << "%";

        if (ze.attr & FILE_ATTRIBUTE_DIRECTORY) {
            CreateDirectoryA(fullPath.c_str(), NULL);
        }
        else {
            size_t pos = fullPath.find_last_of("\\");
            if (pos != std::string::npos) {
                std::string dir = fullPath.substr(0, pos);
                CreateDirectoryA(dir.c_str(), NULL);
            }

#ifdef UNICODE
            int wlen2 = MultiByteToWideChar(CP_ACP, 0, fullPath.c_str(), -1, nullptr, 0);
            if (wlen2 > 0) {
                TCHAR* wBuffer = new TCHAR[wlen2];
                MultiByteToWideChar(CP_ACP, 0, fullPath.c_str(), -1, wBuffer, wlen2);
                zr = UnzipItem(hz, i, wBuffer);
                delete[] wBuffer;
            }
#else
            zr = UnzipItem(hz, i, fullPath.c_str());
#endif
        }
    }

    std::cout << std::endl;
    CloseZip(hz);
    return true;
}

int Downloader::getServerVersion() {
    HINTERNET hInternet = InternetOpenA("VersionChecker", INTERNET_OPEN_TYPE_DIRECT, NULL, NULL, 0);
    if (!hInternet) return -1;

    URL_COMPONENTSA urlComp;
    ZeroMemory(&urlComp, sizeof(urlComp));
    urlComp.dwStructSize = sizeof(urlComp);

    char szHostName[256];
    char szUrlPath[256];

    urlComp.lpszHostName = szHostName;
    urlComp.dwHostNameLength = sizeof(szHostName);
    urlComp.lpszUrlPath = szUrlPath;
    urlComp.dwUrlPathLength = sizeof(szUrlPath);

    if (!InternetCrackUrlA(VERSION_CHECK_URL.c_str(), 0, 0, &urlComp)) {
        InternetCloseHandle(hInternet);
        return -1;
    }

    HINTERNET hConnect = InternetConnectA(hInternet, szHostName, urlComp.nPort, NULL, NULL, INTERNET_SERVICE_HTTP, 0, 0);
    if (!hConnect) {
        InternetCloseHandle(hInternet);
        return -1;
    }

    HINTERNET hRequest = HttpOpenRequestA(hConnect, "POST", szUrlPath, NULL, NULL, NULL,
        INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE, 0);

    if (!hRequest) {
        InternetCloseHandle(hConnect);
        InternetCloseHandle(hInternet);
        return -1;
    }

    const char* headers = "Content-Type: application/x-www-form-urlencoded\r\n";

    if (!HttpSendRequestA(hRequest, headers, strlen(headers), NULL, 0)) {
        InternetCloseHandle(hRequest);
        InternetCloseHandle(hConnect);
        InternetCloseHandle(hInternet);
        return -1;
    }

    char buffer[1024];
    DWORD bytesRead;
    std::string response;

    while (InternetReadFile(hRequest, buffer, sizeof(buffer) - 1, &bytesRead) && bytesRead > 0) {
        buffer[bytesRead] = 0;
        response += buffer;
    }

    InternetCloseHandle(hRequest);
    InternetCloseHandle(hConnect);
    InternetCloseHandle(hInternet);

    size_t pos = response.find("\"version\":");
    if (pos != std::string::npos) {
        pos += 10;
        while (pos < response.size() && (response[pos] == ' ' || response[pos] == ':')) pos++;

        std::string versionStr;
        while (pos < response.size() && isdigit(response[pos])) {
            versionStr += response[pos];
            pos++;
        }

        if (!versionStr.empty()) {
            return std::stoi(versionStr);
        }
    }

    return -1;
}

int Downloader::getLocalVersion() {
    HKEY hKey;
    if (RegOpenKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Wissend", 0, KEY_READ, &hKey) != ERROR_SUCCESS) {
        return -1;
    }

    DWORD version = 0;
    DWORD dataSize = sizeof(DWORD);

    if (RegQueryValueExA(hKey, "ClientVersion", NULL, NULL, (LPBYTE)&version, &dataSize) != ERROR_SUCCESS) {
        RegCloseKey(hKey);
        return -1;
    }

    RegCloseKey(hKey);
    return version;
}

void Downloader::saveVersion(int version) {
    HKEY hKey;
    if (RegCreateKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Wissend", 0, NULL,
        REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &hKey, NULL) == ERROR_SUCCESS) {

        DWORD versionDword = version;
        RegSetValueExA(hKey, "ClientVersion", 0, REG_DWORD, (const BYTE*)&versionDword, sizeof(DWORD));
        RegCloseKey(hKey);
    }
}

bool Downloader::downloadJar() {
    std::cout << "Downloading client.jar..." << std::endl;

    if (!downloadFile(JAR_DOWNLOAD_URL, JAR_PATH)) {
        std::cout << "Failed to download client.jar!" << std::endl;
        return false;
    }

    std::cout << "client.jar downloaded successfully!" << std::endl;
    return true;
}

void Downloader::checkAndUpdateJar() {
    int serverVersion = getServerVersion();
    if (serverVersion == -1) {
        std::cout << "Failed to get server version!" << std::endl;
        return;
    }

    int localVersion = getLocalVersion();

    if (localVersion == -1) {
        std::cout << "No local version found, downloading client.jar..." << std::endl;

        if (downloadJar()) {
            saveVersion(serverVersion);
        }
    }
    else if (serverVersion != localVersion) {
        std::cout << "Version mismatch (Local: " << localVersion << ", Server: " << serverVersion << ")" << std::endl;
        std::cout << "Updating client.jar..." << std::endl;

        DeleteFileA(JAR_PATH.c_str());

        if (downloadJar()) {
            saveVersion(serverVersion);
        }
    }
    else {
        std::cout << "client.jar is up to date (Version: " << localVersion << ")" << std::endl;
    }
}

void Downloader::run() {
    if (checkFolders()) {
        std::cout << "All folders exist and are valid. Skipping download." << std::endl;
    }
    else {
        std::cout << "Folders missing or invalid. Cleaning up..." << std::endl;
        deleteFolders();

        std::string tempZip = getTempPath();

        std::cout << "Starting download..." << std::endl;
        if (!downloadFile(DOWNLOAD_URL, tempZip)) {
            std::cout << "Download failed!" << std::endl;
            return;
        }

        std::cout << "Starting extraction..." << std::endl;
        if (!extractZip(tempZip, EXTRACT_PATH)) {
            std::cout << "Extraction failed!" << std::endl;
            DeleteFileA(tempZip.c_str());
            return;
        }

        DeleteFileA(tempZip.c_str());
        std::cout << "Done!" << std::endl;
    }

    checkAndUpdateJar();
}