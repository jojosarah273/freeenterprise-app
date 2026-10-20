/*
 * Linux stand-in for Android's <android/log.h>.
 *
 * The emulator bridge (app/src/main/cpp/ff4emu.cpp) is shared between Android and
 * this build, and logging is the only thing in it that is Android-specific. Putting
 * this header on the include path lets that file compile unchanged, so there is one
 * bridge rather than two that drift.
 */
#ifndef FF4_ANDROID_LOG_SHIM_H
#define FF4_ANDROID_LOG_SHIM_H

#include <stdarg.h>
#include <stdio.h>

#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_ERROR 6

static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    FILE *out = (prio >= ANDROID_LOG_ERROR) ? stderr : stdout;
    va_list ap;
    va_start(ap, fmt);
    fprintf(out, "[%s] ", tag);
    int n = vfprintf(out, fmt, ap);
    fputc('\n', out);
    va_end(ap);
    return n;
}

#endif
