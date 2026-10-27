/*
 * Controller input for the desktop build, via SDL2.
 *
 * Why SDL2 and not XInput: XInput is a Windows API, and on Linux the equivalents
 * are evdev/joydev. SDL2 is the portable layer over both *and* carries the
 * community controller-mapping database, which is what makes dozens of different
 * pads work without us mapping each one.
 *
 * SDL2 is dlopen'd rather than linked, exactly as the emulator core is, so a machine
 * without it still runs the application - it just reports no controller instead of
 * failing to start.
 *
 * The button bits this exposes are a stable order of our own (see the Button enum in
 * the Kotlin side); the translation from SDL's constants happens here, where the
 * real header is available, so the Kotlin mapping can be unit tested without SDL.
 */
#include <jni.h>
#include <dlfcn.h>
#include <SDL2/SDL.h>

#include <cstdio>
#include <cstring>
#include <string>

namespace {

struct Sdl {
    void *handle = nullptr;
    int (*InitSubSystem)(Uint32) = nullptr;
    void (*QuitSubSystem)(Uint32) = nullptr;
    int (*NumJoysticks)(void) = nullptr;
    SDL_bool (*IsGameController)(int) = nullptr;
    SDL_GameController *(*Open)(int) = nullptr;
    void (*Close)(SDL_GameController *) = nullptr;
    const char *(*Name)(SDL_GameController *) = nullptr;
    void (*Update)(void) = nullptr;
    void (*PumpEvents)(void) = nullptr;
    Uint8 (*GetButton)(SDL_GameController *, SDL_GameControllerButton) = nullptr;
    Sint16 (*GetAxis)(SDL_GameController *, SDL_GameControllerAxis) = nullptr;
    bool ok = false;
};

Sdl g_sdl;
SDL_GameController *g_pad = nullptr;
std::string g_name;

/** Bit positions the Kotlin side knows; order is ours, not SDL's. */
enum Bit {
    BIT_A = 0, BIT_B, BIT_X, BIT_Y,
    BIT_BACK, BIT_GUIDE, BIT_START, BIT_LSTICK, BIT_RSTICK,
    BIT_LSHOULDER, BIT_RSHOULDER,
    BIT_DPAD_UP, BIT_DPAD_DOWN, BIT_DPAD_LEFT, BIT_DPAD_RIGHT,
    BIT_LTRIGGER, BIT_RTRIGGER,
    BIT_COUNT
};

/** Trigger axes rest near zero and read ~32767 when fully pressed. */
constexpr Sint16 kTriggerThreshold = 8192;

bool loadSdl() {
    if (g_sdl.ok) return true;
    const char *names[] = {"libSDL2-2.0.so.0", "libSDL2-2.0.so", "libSDL2.so", "SDL2.dll"};
    for (const char *name : names) {
        g_sdl.handle = dlopen(name, RTLD_LAZY | RTLD_LOCAL);
        if (g_sdl.handle) break;
    }
    if (!g_sdl.handle) {
        // A missing libSDL2 is indistinguishable from a broken controller from the
        // player's side, so say what actually happened.
        std::fprintf(stderr, "[FF4Input] no controller support: libSDL2-2.0.so.0 not found\n");
        return false;
    }

    #define LOAD(field, symbol) \
        g_sdl.field = (decltype(g_sdl.field)) dlsym(g_sdl.handle, symbol); \
        if (!g_sdl.field) { dlclose(g_sdl.handle); g_sdl.handle = nullptr; return false; }

    LOAD(InitSubSystem, "SDL_InitSubSystem")
    LOAD(QuitSubSystem, "SDL_QuitSubSystem")
    LOAD(NumJoysticks, "SDL_NumJoysticks")
    LOAD(IsGameController, "SDL_IsGameController")
    LOAD(Open, "SDL_GameControllerOpen")
    LOAD(Close, "SDL_GameControllerClose")
    LOAD(Name, "SDL_GameControllerName")
    LOAD(Update, "SDL_GameControllerUpdate")
    LOAD(PumpEvents, "SDL_PumpEvents")
    LOAD(GetButton, "SDL_GameControllerGetButton")
    LOAD(GetAxis, "SDL_GameControllerGetAxis")
    #undef LOAD

    g_sdl.ok = true;
    return true;
}

void closePad() {
    if (g_pad && g_sdl.Close) g_sdl.Close(g_pad);
    g_pad = nullptr;
    g_name.clear();
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_desktop_Gamepad_nativeAvailable(JNIEnv *, jobject) {
    return loadSdl() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_desktop_Gamepad_nativeStart(JNIEnv *, jobject) {
    if (!loadSdl()) return JNI_FALSE;
    if (g_pad) return JNI_TRUE;

    g_sdl.InitSubSystem(SDL_INIT_GAMECONTROLLER);
    const int count = g_sdl.NumJoysticks();
    for (int i = 0; i < count; i++) {
        if (!g_sdl.IsGameController(i)) continue;
        g_pad = g_sdl.Open(i);
        if (g_pad) {
            const char *name = g_sdl.Name(g_pad);
            g_name = name ? name : "controller";
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freeenterprise_desktop_Gamepad_nativeName(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_name.c_str());
}

/** Pressed buttons as our bit order. */
extern "C" JNIEXPORT jint JNICALL
Java_com_freeenterprise_desktop_Gamepad_nativeButtons(JNIEnv *, jobject) {
    if (!g_pad) return 0;
    g_sdl.PumpEvents();
    g_sdl.Update();

    int bits = 0;
    auto press = [&](Bit bit, SDL_GameControllerButton button) {
        if (g_sdl.GetButton(g_pad, button)) bits |= (1 << bit);
    };
    press(BIT_A, SDL_CONTROLLER_BUTTON_A);
    press(BIT_B, SDL_CONTROLLER_BUTTON_B);
    press(BIT_X, SDL_CONTROLLER_BUTTON_X);
    press(BIT_Y, SDL_CONTROLLER_BUTTON_Y);
    press(BIT_BACK, SDL_CONTROLLER_BUTTON_BACK);
    press(BIT_GUIDE, SDL_CONTROLLER_BUTTON_GUIDE);
    press(BIT_START, SDL_CONTROLLER_BUTTON_START);
    press(BIT_LSTICK, SDL_CONTROLLER_BUTTON_LEFTSTICK);
    press(BIT_RSTICK, SDL_CONTROLLER_BUTTON_RIGHTSTICK);
    press(BIT_LSHOULDER, SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
    press(BIT_RSHOULDER, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER);
    press(BIT_DPAD_UP, SDL_CONTROLLER_BUTTON_DPAD_UP);
    press(BIT_DPAD_DOWN, SDL_CONTROLLER_BUTTON_DPAD_DOWN);
    press(BIT_DPAD_LEFT, SDL_CONTROLLER_BUTTON_DPAD_LEFT);
    press(BIT_DPAD_RIGHT, SDL_CONTROLLER_BUTTON_DPAD_RIGHT);

    if (g_sdl.GetAxis(g_pad, SDL_CONTROLLER_AXIS_TRIGGERLEFT) > kTriggerThreshold) bits |= (1 << BIT_LTRIGGER);
    if (g_sdl.GetAxis(g_pad, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > kTriggerThreshold) bits |= (1 << BIT_RTRIGGER);
    return bits;
}

/** Left stick, then right stick, then the two triggers, each in -1..1. */
extern "C" JNIEXPORT jint JNICALL
Java_com_freeenterprise_desktop_Gamepad_nativeAxes(JNIEnv *env, jobject, jfloatArray target) {
    if (!g_pad) return 0;
    const jsize length = env->GetArrayLength(target);
    if (length < 6) return 0;

    auto axis = [&](SDL_GameControllerAxis a) {
        return g_sdl.GetAxis(g_pad, a) / 32767.0f;
    };
    jfloat values[6] = {
        axis(SDL_CONTROLLER_AXIS_LEFTX),
        axis(SDL_CONTROLLER_AXIS_LEFTY),
        axis(SDL_CONTROLLER_AXIS_RIGHTX),
        axis(SDL_CONTROLLER_AXIS_RIGHTY),
        axis(SDL_CONTROLLER_AXIS_TRIGGERLEFT),
        axis(SDL_CONTROLLER_AXIS_TRIGGERRIGHT),
    };
    env->SetFloatArrayRegion(target, 0, 6, values);
    return 6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_freeenterprise_desktop_Gamepad_nativeStop(JNIEnv *, jobject) {
    closePad();
}
