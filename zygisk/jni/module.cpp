// SmaliScope Zygisk 模块：只给名单里的应用打上「可调试」标记。
//
// 为什么需要它：实测证明（docs/p0-path-findings.md）现代 Android 上
// `ro.debuggable` / `ro.force.debuggable` / `am set-debug-app -w` 都无法让一个未带
// android:debuggable="true" 的 release 包变成可 JDWP 调试。真正起作用的是
// zygote fork 时传给 ART 的 runtime flags，而 Zygisk 的 preAppSpecialize
// 恰好能改它。这样原 APK 一字不动——签名、数据、更新链路全部保留。
//
// ⚠️ 这段代码会在设备上**每一个**应用进程里执行。任何异常都必须退化成
// 「什么都不做」，绝不能让宿主进程崩溃——那等于把用户的手机搞坏。
// 因此：不抛异常、不做动态分配失败后的解引用、companion 通信全程带长度校验，
// 任何一步不如预期就直接返回。

// 刻意不链 C++ 标准库（Application.mk 里 APP_STL := none）：
// 这段代码在每个应用进程里都会被加载，少一个依赖少一分出错与体积。
// 因此用 C 头文件而非 <cstdio> 这类 C++ 包装。
#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>

#include "zygisk.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

#define LOG_TAG "SmaliScope"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// com.android.internal.os.Zygote 里的常量。
//   DEBUG_ENABLE_JDWP     让 adbconnection 起来，进程才会出现在 adb jdwp 列表里
//   DEBUG_JAVA_DEBUGGABLE 让 ART 以可调试模式运行；断点所需的 deoptimize 靠它，
//                         只给前者的话进程能被 attach 但下不了指令级断点
static constexpr int DEBUG_ENABLE_JDWP = 1;
static constexpr int DEBUG_JAVA_DEBUGGABLE = 1 << 8;

// 目标名单。一行一个进程名（通常等于包名），`#` 起头为注释。
// 放在 /data/adb 下：只有 root 能写，普通应用改不了它来给自己开调试。
static constexpr const char *TARGET_LIST = "/data/adb/smaliscope/targets";

// 进程名最长 128 足够（包名上限远小于此），固定缓冲避免在 fork 后的敏感期做堆分配。
static constexpr size_t MAX_NAME = 128;

namespace {

// companion 在 root 进程里回答「这个进程名在名单里吗」。
// 之所以要走 companion：preAppSpecialize 阶段的进程读不到 /data/adb，
// 直接 fopen 会失败，从而永远返回「不在名单里」。
bool ask_companion(Api *api, const char *name) {
    int fd = api->connectCompanion();
    if (fd < 0) return false;

    bool result = false;
    uint32_t len = static_cast<uint32_t>(strlen(name));
    if (len > 0 && len <= MAX_NAME &&
        write(fd, &len, sizeof(len)) == sizeof(len) &&
        write(fd, name, len) == static_cast<ssize_t>(len)) {
        uint8_t reply = 0;
        if (read(fd, &reply, sizeof(reply)) == sizeof(reply)) {
            result = reply == 1;
        }
    }
    close(fd);
    return result;
}

class SmaliScopeModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        // 我们不 hook 任何函数，做完就可以卸载，省下常驻内存。
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);

        if (args == nullptr || args->nice_name == nullptr) return;

        char name[MAX_NAME + 1] = {};
        const char *raw = env->GetStringUTFChars(args->nice_name, nullptr);
        if (raw == nullptr) return;
        strncpy(name, raw, MAX_NAME);
        env->ReleaseStringUTFChars(args->nice_name, raw);
        if (name[0] == '\0') return;

        if (!ask_companion(api, name)) return;

        args->runtime_flags |= DEBUG_ENABLE_JDWP | DEBUG_JAVA_DEBUGGABLE;
        LOGI("已为 %s 打开调试标记（JDWP + java-debuggable）", name);
    }

    // system_server 一律不碰：给它打调试标记没有收益，风险却极高。
    void preServerSpecialize(ServerSpecializeArgs *) override {
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
};

// ── companion（root 进程）───────────────────────────────────────────────────
// 每次查询都重新读文件，所以改完名单下次启动应用即生效，不必重启手机。

bool name_in_list(const char *name) {
    FILE *fp = fopen(TARGET_LIST, "re");
    if (fp == nullptr) return false;

    char line[MAX_NAME + 16];
    bool hit = false;
    while (!hit && fgets(line, sizeof(line), fp) != nullptr) {
        // 去掉行尾换行与空白
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r' ||
                         line[n - 1] == ' ' || line[n - 1] == '\t')) {
            line[--n] = '\0';
        }
        // 去掉行首空白
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '\0' || *p == '#') continue;

        // 精确匹配进程名；`pkg:` 前缀写法也接受，便于以后扩展成「整个包的所有进程」
        if (strcmp(p, name) == 0) {
            hit = true;
        } else {
            size_t plen = strlen(p);
            // 形如 com.foo.bar 的名单项，匹配 com.foo.bar:remote 这类子进程
            if (plen < strlen(name) && strncmp(p, name, plen) == 0 && name[plen] == ':') {
                hit = true;
            }
        }
    }
    fclose(fp);
    return hit;
}

void companion_handler(int fd) {
    uint32_t len = 0;
    if (read(fd, &len, sizeof(len)) != sizeof(len)) return;
    if (len == 0 || len > MAX_NAME) return;

    char name[MAX_NAME + 1] = {};
    size_t got = 0;
    while (got < len) {
        ssize_t r = read(fd, name + got, len - got);
        if (r <= 0) return;
        got += static_cast<size_t>(r);
    }
    name[len] = '\0';

    uint8_t reply = name_in_list(name) ? 1 : 0;
    write(fd, &reply, sizeof(reply));
}

}  // namespace

REGISTER_ZYGISK_MODULE(SmaliScopeModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
