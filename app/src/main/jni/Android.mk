LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := aeonps1_bridge
LOCAL_SRC_FILES := aeonps1_bridge.c
LOCAL_LDLIBS := -llog -landroid -ldl
LOCAL_CFLAGS := -O2 -Wall -Wextra -Wno-unused-parameter
include $(BUILD_SHARED_LIBRARY)
