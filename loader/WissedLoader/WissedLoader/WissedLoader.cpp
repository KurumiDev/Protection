#include <iostream>
#include <string>
#include <sstream>
#include <windows.h>
#include <winioctl.h>
#include <iomanip>
#include "AuthUtils.h"
#include "MinecraftLoader.h"
#include "md5.h"
#include "Download.h"

using namespace std;

void resizeConsole(int width, int height) {
	HWND console = GetConsoleWindow();
	RECT r;
	GetWindowRect(console, &r);

	MoveWindow(console, r.left, r.top, width, height, TRUE);
}

__forceinline string obfNew(const string& input) {
	string result = input;
	for (size_t i = 0; i < result.size(); ++i) {
		result[i] = result[i] ^ 0xFF;
	}
	return result;
}

__forceinline string getDriveSerialNumber() {
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

		if (DeviceIoControl(hDevice, IOCTL_STORAGE_QUERY_PROPERTY, &storagePropertyQuery, sizeof(STORAGE_PROPERTY_QUERY),
			&storageDescriptorHeader, sizeof(STORAGE_DESCRIPTOR_HEADER), &dwBytesReturned, nullptr)) {

			const DWORD dwOutBufferSize = storageDescriptorHeader.Size;
			BYTE* pOutBuffer = new BYTE[dwOutBufferSize];
			ZeroMemory(pOutBuffer, dwOutBufferSize);

			if (DeviceIoControl(hDevice, IOCTL_STORAGE_QUERY_PROPERTY, &storagePropertyQuery, sizeof(STORAGE_PROPERTY_QUERY),
				pOutBuffer, dwOutBufferSize, &dwBytesReturned, nullptr)) {

				STORAGE_DEVICE_DESCRIPTOR* pDeviceDescriptor = (STORAGE_DEVICE_DESCRIPTOR*)pOutBuffer;

				if (pDeviceDescriptor->SerialNumberOffset) {
					serialNumber = string((char*)(pOutBuffer + pDeviceDescriptor->SerialNumberOffset));
				}
			}

			delete[] pOutBuffer;
		}
		CloseHandle(hDevice);
	}

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

	return md5::create_from_string(hardwareID);
}

void disableSelection() {
	HANDLE hInput = GetStdHandle(STD_INPUT_HANDLE);
	DWORD prevMode;
	GetConsoleMode(hInput, &prevMode);
	DWORD newMode = prevMode & ~(ENABLE_QUICK_EDIT_MODE | ENABLE_INSERT_MODE);
	SetConsoleMode(hInput, newMode);
}

bool saveToRegistry(const string& username, const string& password) {
	HKEY hKey;
	LONG result = RegCreateKeyExA(HKEY_CURRENT_USER, "SOFTWARE\\Wissend", 0, NULL,
		REG_OPTION_NON_VOLATILE, KEY_WRITE, NULL, &hKey, NULL);

	if (result != ERROR_SUCCESS) {
		return false;
	}

	string encryptedUsername = obfNew(username);
	string encryptedPassword = obfNew(password);

	RegSetValueExA(hKey, "usr", 0, REG_SZ, (const BYTE*)encryptedUsername.c_str(), encryptedUsername.length() + 1);
	RegSetValueExA(hKey, "pwd", 0, REG_SZ, (const BYTE*)encryptedPassword.c_str(), encryptedPassword.length() + 1);

	RegCloseKey(hKey);
	return true;
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

void clearRegistry() {
	RegDeleteKeyA(HKEY_CURRENT_USER, "SOFTWARE\\Wissend");
}

int main() {

	HANDLE hMutex = CreateMutexA(NULL, FALSE, "Global\\WissendLoaderSingleInstance");
	if (GetLastError() == ERROR_ALREADY_EXISTS) {
		MessageBoxA(NULL, "Loader is already running!", "Error", MB_ICONERROR | MB_OK);
		CloseHandle(hMutex);
		return 1;
	}

	SetConsoleTitle(L"Wissend | v1.0");
	disableSelection();
	resizeConsole(600, 300);
	std::string username, password;

	system("chcp 65001 >nul");

	std::cout << "\n";
	std::cout << " ##   ## ##  ####   ####  ##### ##  ##  ####\n";
	std::cout << " ##   ## ##  ##    ##     ##    ###  ## ##  ##\n";
	std::cout << " ## # ## ##   ##    ##    ##### ## # ## ##  ##\n";
	std::cout << " ####### ##     ##    ##  ##    ##  ### ##  ##\n";
	std::cout << "  ## ##  ##  ####  ####   ##### ##   ## ####\n";
	std::cout << "\n";

	bool autoLogin = loadFromRegistry(username, password);

	if (autoLogin) {
		std::cout << "Logging in...\n";
	}
	else {
		std::cout << "Username: ";
		std::cin >> username;
		std::cout << "Password: ";
		std::cin >> password;
	}

	std::string token = generateToken(16);
	std::string enc = httpPost(username, password, token, get_hwid());

	if (enc.empty()) {
		if (autoLogin) {
			clearRegistry();
			system("cls");
			std::cout << "\n";
			std::cout << " ##   ## ##  ####   ####  ##### ##  ##  ####\n";
			std::cout << " ##   ## ##  ##    ##     ##    ###  ## ##  ##\n";
			std::cout << " ## # ## ##   ##    ##    ##### ## # ## ##  ##\n";
			std::cout << " ####### ##     ##    ##  ##    ##  ### ##  ##\n";
			std::cout << "  ## ##  ##  ####  ####   ##### ##   ## ####\n";
			std::cout << "\n";
			std::cout << "Username: ";
			std::cin >> username;
			std::cout << "Password: ";
			std::cin >> password;

			token = generateToken(16);
			enc = httpPost(username, password, token, get_hwid());
			if (enc.empty()) crash();
		}
		else {
			crash();
		}
	}

	std::string s1 = caesar(enc, -15);
	std::string s2 = stripNoise(s1);
	std::string finalData = caesar(s2, -15);

	std::string flag = "successnewnaasuka";

	if (finalData.find(flag) == std::string::npos) {
		if (autoLogin) {
			clearRegistry();
			system("cls");
			std::cout << "\n";
			std::cout << " ##   ## ##  ####   ####  ##### ##  ##  ####\n";
			std::cout << " ##   ## ##  ##    ##     ##    ###  ## ##  ##\n";
			std::cout << " ## # ## ##   ##    ##    ##### ## # ## ##  ##\n";
			std::cout << " ####### ##     ##    ##  ##    ##  ### ##  ##\n";
			std::cout << "  ## ##  ##  ####  ####   ##### ##   ## ####\n";
			std::cout << "\n";
			std::cout << "Username: ";
			std::cin >> username;
			std::cout << "Password: ";
			std::cin >> password;

			token = generateToken(16);
			enc = httpPost(username, password, token, get_hwid());
			if (enc.empty()) crash();

			s1 = caesar(enc, -15);
			s2 = stripNoise(s1);
			finalData = caesar(s2, -15);

			if (finalData.find(flag) == std::string::npos) crash();
		}
		else {
			crash();
		}
	}

	std::string serverToken = finalData.substr(0, finalData.size() - flag.size());
	if (serverToken != token) {
		if (autoLogin) {
			clearRegistry();
			system("cls");
			std::cout << "\n";
			std::cout << " ##   ## ##  ####   ####  ##### ##  ##  ####\n";
			std::cout << " ##   ## ##  ##    ##     ##    ###  ## ##  ##\n";
			std::cout << " ## # ## ##   ##    ##    ##### ## # ## ##  ##\n";
			std::cout << " ####### ##     ##    ##  ##    ##  ### ##  ##\n";
			std::cout << "  ## ##  ##  ####  ####   ##### ##   ## ####\n";
			std::cout << "\n";
			std::cout << "Username: ";
			std::cin >> username;
			std::cout << "Password: ";
			std::cin >> password;

			token = generateToken(16);
			enc = httpPost(username, password, token, get_hwid());
			if (enc.empty()) crash();

			s1 = caesar(enc, -15);
			s2 = stripNoise(s1);
			finalData = caesar(s2, -15);

			if (finalData.find(flag) == std::string::npos) crash();

			serverToken = finalData.substr(0, finalData.size() - flag.size());
			if (serverToken != token) crash();
		}
		else {
			crash();
		}
	}

	std::cout << "\nAUTH SUCCESS!\n";

	saveToRegistry(username, password);

	Sleep(2000);
	system("cls");

	Downloader downloader;
	downloader.run();

	MinecraftLoader loader;
	loader.launchMinecraft(username, "2048");

}