LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := smaliscope
LOCAL_SRC_FILES := module.cpp

# 刻意不链 C++ 标准库（见 Application.mk 的 APP_STL := none）：这个 .so 会被加载进
# 设备上每一个应用进程，依赖越少越好。
#
# -fno-threadsafe-statics 是随之而来的必要项：zygisk.hpp 的 entry_impl 里有个
# 函数内静态变量（static module_abi abi），它需要 __cxa_guard_acquire/release，
# 而那两个符号在 libc++ 里。Zygisk 只在模块加载时调 entry_impl 一次、且那时是单线程，
# 所以去掉线程安全守卫是安全的，比为此拖进整个 libc++ 划算。
LOCAL_CPPFLAGS  := -std=c++17 -fno-exceptions -fno-rtti -fno-threadsafe-statics \
                   -Oz -flto -fvisibility=hidden
LOCAL_LDFLAGS   := -flto
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
