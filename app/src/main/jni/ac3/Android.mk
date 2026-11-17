LOCAL_PATH := $(call my-dir)
ifndef AC3_FFMPEG_PREFIX
$(error Set AC3_FFMPEG_PREFIX to minimal FFmpeg install prefix)
endif
include $(CLEAR_VARS)
LOCAL_MODULE := ac3-avcodec
LOCAL_SRC_FILES := $(AC3_FFMPEG_PREFIX)/lib/libavcodec.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := ac3-avutil
LOCAL_SRC_FILES := $(AC3_FFMPEG_PREFIX)/lib/libavutil.a
include $(PREBUILT_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := moonlight-ac3
LOCAL_SRC_FILES := encoder.c
LOCAL_C_INCLUDES := $(AC3_FFMPEG_PREFIX)/include
LOCAL_CFLAGS := -O3 -std=c11
LOCAL_STATIC_LIBRARIES := ac3-avcodec ac3-avutil
LOCAL_LDLIBS := -lm -lz
LOCAL_LDFLAGS := -Wl,--exclude-libs,ALL
include $(BUILD_SHARED_LIBRARY)
