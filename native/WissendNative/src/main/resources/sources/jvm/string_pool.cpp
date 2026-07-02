#include "string_pool.hpp"
#include "protect.h"
namespace native_jvm::string_pool {
    static char pool[$size] = $value;

    char *get_pool() {
        return pool;
    }
}