LOCAL_PATH := $(call my-dir)
CORE_DIR := $(LOCAL_PATH)

# Snes9x 1.63 + libretro glue (source list from the upstream Makefile.common).
include $(CORE_DIR)/libretro/Makefile.common

COREFLAGS := -DANDROID -D__LIBRETRO__ -DHAVE_STRINGS_H -DRIGHTSHIFT_IS_SAR $(INCFLAGS)

# ---------------------------------------------------------------- the core
include $(CLEAR_VARS)
LOCAL_MODULE     := retro
LOCAL_SRC_FILES  := $(SOURCES_C) $(SOURCES_CXX)
LOCAL_CXXFLAGS   := $(COREFLAGS) -O2
LOCAL_CFLAGS     := $(COREFLAGS) -O2
LOCAL_LDFLAGS    := -Wl,-version-script=$(CORE_DIR)/libretro/link.T
LOCAL_LDLIBS     := -llog
include $(BUILD_SHARED_LIBRARY)

# ------------------------------------------------------- our frontend bridge
include $(CLEAR_VARS)
LOCAL_MODULE     := ff4emu
LOCAL_SRC_FILES  := ff4emu.cpp
LOCAL_CXXFLAGS   := -DANDROID -O2
LOCAL_LDLIBS     := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
