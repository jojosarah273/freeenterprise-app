# Native libraries for the Linux dev build.
#
#   libretro.so   the Snes9x core, built from the same source list as Android
#                 (app/src/main/cpp/libretro/Makefile.common)
#   libff4emu.so  the emulator bridge, compiled from the very same ff4emu.cpp the
#                 Android build uses, with a shim providing <android/log.h>
#
#   make            build both into ./build
#   make clean
CORE_DIR := $(abspath $(CURDIR)/../../app/src/main/cpp)
include $(CORE_DIR)/libretro/Makefile.common

CXX ?= g++
OUT ?= build

# The bridge is JNI code, so it needs the JDK's headers. Derived from `java` on the
# path rather than assumed, and overridable with JAVA_HOME=... on the command line.
JAVA_HOME ?= $(shell dirname $$(dirname $$(readlink -f $$(which java))))
JNI_INC   := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux

COREFLAGS := -D__LIBRETRO__ -DHAVE_STRINGS_H -DRIGHTSHIFT_IS_SAR -Wno-narrowing -fPIC
CXXFLAGS  := -std=gnu++17 -O2 $(COREFLAGS) $(INCFLAGS)

CORE_OBJS := $(SOURCES_CXX:$(CORE_DIR)/%.cpp=$(OUT)/core/%.o)
C_OBJS    := $(SOURCES_C:$(CORE_DIR)/%.c=$(OUT)/corec/%.o)

all: $(OUT)/libretro.so $(OUT)/libff4emu.so

# The core is a shared library here so the bridge can dlopen it, exactly as it does
# from the APK's native library directory.
$(OUT)/libretro.so: $(CORE_OBJS) $(C_OBJS)
	$(CXX) -shared -o $@ $^ -lpthread -lm

$(OUT)/libff4emu.so: $(CORE_DIR)/ff4emu.cpp
	@mkdir -p $(OUT)
	$(CXX) $(CXXFLAGS) $(JNI_INC) -shared -I$(CURDIR)/shim -o $@ $< -ldl -lpthread

$(OUT)/core/%.o: $(CORE_DIR)/%.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(OUT)/corec/%.o: $(CORE_DIR)/%.c
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -rf $(OUT)

.PHONY: all clean
