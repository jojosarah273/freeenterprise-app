# Headless Snes9x driver for Free Enterprise work.
#
# The core source list is included from the same file the Android build uses
# (app/src/main/cpp/libretro/Makefile.common), so the two can never drift: there
# is one list, owned upstream, and both builds consume it.
#
#   make          build ./ff4dump
#   make clean
CORE_DIR := $(abspath $(CURDIR)/../../app/src/main/cpp)
include $(CORE_DIR)/libretro/Makefile.common

CXX ?= g++
CC  ?= gcc
OUT ?= build

# Same defines as the Android build, minus -DANDROID (this is not Android), plus
# -Wno-narrowing which the NDK passes by default.
COREFLAGS := -D__LIBRETRO__ -DHAVE_STRINGS_H -DRIGHTSHIFT_IS_SAR -Wno-narrowing
CXXFLAGS  := -std=gnu++17 -O2 $(COREFLAGS) $(INCFLAGS)
CFLAGS    := -O2 $(COREFLAGS) $(INCFLAGS)
LDLIBS    := -lpthread -lm

CORE_OBJS := $(SOURCES_CXX:$(CORE_DIR)/%.cpp=$(OUT)/core/%.o)
C_OBJS    := $(SOURCES_C:$(CORE_DIR)/%.c=$(OUT)/corec/%.o)
OBJS      := $(OUT)/main.o $(CORE_OBJS) $(C_OBJS)

all: ff4dump

ff4dump: $(OBJS)
	$(CXX) $(OBJS) -o $@ $(LDLIBS)

$(OUT)/main.o: main.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(OUT)/core/%.o: $(CORE_DIR)/%.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(OUT)/corec/%.o: $(CORE_DIR)/%.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -rf $(OUT) ff4dump

.PHONY: all clean
