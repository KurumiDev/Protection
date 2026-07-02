#pragma once
#include <string>

std::string caesar(const std::string& text, int shift);
std::string generateToken(int len);
std::string httpPost(const std::string& u, const std::string& p, const std::string& token, const std::string& hwid);
std::string stripNoise(const std::string& s);
void crash();
