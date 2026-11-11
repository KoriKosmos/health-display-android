// JNI bridge between Kotlin (DiscordPresence) and the Discord Social SDK.
// Rich Presence only, without authentication (Social SDK 1.10+ on Android):
// SetApplicationId + UpdateRichPresence, no Connect()/OAuth/token.
// Every function here is called from DiscordPresence's single worker thread.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <jni.h>
#include <android/log.h>
#include <memory>
#include <string>

namespace {
JavaVM *gVm = nullptr;
jclass gBridge = nullptr;      // dev.korikosmos.healthdisplay.discord.DiscordPresence
jmethodID gOnLog = nullptr;    // static void onNativeLog(String)
jmethodID gOnResult = nullptr; // static void onNativeResult(boolean, String)
std::shared_ptr<discordpp::Client> gClient;

JNIEnv *env() {
    JNIEnv *e = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) != JNI_OK)
        gVm->AttachCurrentThread(&e, nullptr);
    return e;
}

void toKotlinLog(const std::string &msg) {
    JNIEnv *e = env();
    jstring s = e->NewStringUTF(msg.c_str());
    e->CallStaticVoidMethod(gBridge, gOnLog, s);
    e->DeleteLocalRef(s);
}

void toKotlinResult(bool ok, const std::string &msg) {
    JNIEnv *e = env();
    jstring s = e->NewStringUTF(msg.c_str());
    e->CallStaticVoidMethod(gBridge, gOnResult, static_cast<jboolean>(ok), s);
    e->DeleteLocalRef(s);
}

std::string str(JNIEnv *e, jstring s) {
    if (!s) return {};
    const char *c = e->GetStringUTFChars(s, nullptr);
    std::string out(c);
    e->ReleaseStringUTFChars(s, c);
    return out;
}
} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    JNIEnv *e = env();
    jclass c = e->FindClass("dev/korikosmos/healthdisplay/discord/DiscordPresence");
    gBridge = static_cast<jclass>(e->NewGlobalRef(c));
    gOnLog = e->GetStaticMethodID(gBridge, "onNativeLog", "(Ljava/lang/String;)V");
    gOnResult = e->GetStaticMethodID(gBridge, "onNativeResult", "(ZLjava/lang/String;)V");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_korikosmos_healthdisplay_discord_DiscordPresence_nativeInit(JNIEnv *, jclass, jlong appId) {
    gClient = std::make_shared<discordpp::Client>();
    gClient->AddLogCallback(
        [](std::string message, discordpp::LoggingSeverity) { toKotlinLog(message); },
        discordpp::LoggingSeverity::Info);
    gClient->SetApplicationId(static_cast<uint64_t>(appId));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_korikosmos_healthdisplay_discord_DiscordPresence_nativeUpdate(
    JNIEnv *e, jclass, jstring details, jstring state, jstring largeImage, jstring largeText,
    jlong startMs) {
    if (!gClient) return;
    discordpp::Activity a;
    a.SetType(discordpp::ActivityTypes::Playing);
    if (auto d = str(e, details); !d.empty()) a.SetDetails(d);
    if (auto s = str(e, state); !s.empty()) a.SetState(s);
    if (auto img = str(e, largeImage); !img.empty()) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(img);
        if (auto t = str(e, largeText); !t.empty()) assets.SetLargeText(t);
        a.SetAssets(assets);
    }
    if (startMs > 0) {
        discordpp::ActivityTimestamps ts;
        ts.SetStart(static_cast<uint64_t>(startMs));
        a.SetTimestamps(ts);
    }
    gClient->UpdateRichPresence(a, [](discordpp::ClientResult r) {
        toKotlinResult(r.Successful(), r.Successful() ? "" : r.Error());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_korikosmos_healthdisplay_discord_DiscordPresence_nativeClear(JNIEnv *, jclass) {
    if (gClient) gClient->ClearRichPresence();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_korikosmos_healthdisplay_discord_DiscordPresence_nativeRunCallbacks(JNIEnv *, jclass) {
    discordpp::RunCallbacks();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_korikosmos_healthdisplay_discord_DiscordPresence_nativeShutdown(JNIEnv *, jclass) {
    if (gClient) gClient->ClearRichPresence();
    gClient.reset();
}
