#include "AuthUtils.h"
#include <windows.h>
#include <wininet.h>
#include <ctime>

#pragma comment(lib, "wininet.lib")

std::string caesar(const std::string& text, int shift) {
    std::string r;
    r.reserve(text.size());
    for (char c : text) {
        if (c >= 'a' && c <= 'z') r.push_back((char)((c - 'a' + shift + 26) % 26 + 'a'));
        else if (c >= 'A' && c <= 'Z') r.push_back((char)((c - 'A' + shift + 26) % 26 + 'A'));
        else r.push_back(c);
    }
    return r;
}

std::string generateToken(int len) {
    const char* cset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    std::string t;
    t.reserve(len);
    srand((unsigned)time(nullptr));
    for (int i = 0; i < len; i++) t.push_back(cset[rand() % 26]);
    return t;
}

std::string httpPost(const std::string& u, const std::string& p, const std::string& token, const std::string& hwid) {
    HINTERNET hI = InternetOpenA("AuthClient", INTERNET_OPEN_TYPE_DIRECT, 0, 0, 0);
    if (!hI) return "";
    HINTERNET hC = InternetConnectA(hI, "localhost", 1000, 0, 0, INTERNET_SERVICE_HTTP, 0, 0);
    if (!hC) { InternetCloseHandle(hI); return ""; }

    const char* accept[] = { "*/*", NULL };
    HINTERNET hR = HttpOpenRequestA(hC, "POST", "/guard/login", 0, 0, accept, INTERNET_FLAG_RELOAD, 0);
    if (!hR) { InternetCloseHandle(hC); InternetCloseHandle(hI); return ""; }

    std::string header = "Content-Type: application/x-www-form-urlencoded";
    std::string body = "username=" + u + "&password=" + p + "&token=" + token + "&hwid=" + hwid;

    if (!HttpSendRequestA(hR, header.c_str(), (DWORD)header.size(), (LPVOID)body.c_str(), (DWORD)body.size())) {
        InternetCloseHandle(hR);
        InternetCloseHandle(hC);
        InternetCloseHandle(hI);
        return "";
    }

    char buf[4096];
    DWORD r;
    std::string out;

    while (InternetReadFile(hR, buf, sizeof(buf), &r) && r)
        out.append(buf, r);

    InternetCloseHandle(hR);
    InternetCloseHandle(hC);
    InternetCloseHandle(hI);
    return out;
}

std::string stripNoise(const std::string& s) {
    if (s.size() < 30) return "";
    return s.substr(12, s.size() - 30);
}

void crash() {
    *(int*)0 = 0;
}
