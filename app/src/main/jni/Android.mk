MOONLIGHT_JNI_ROOT := $(call my-dir)
# Explicit entries also invalidate Gradle native metadata when AC3 is added.
include $(MOONLIGHT_JNI_ROOT)/ac3/Android.mk
include $(MOONLIGHT_JNI_ROOT)/evdev_reader/Android.mk
include $(MOONLIGHT_JNI_ROOT)/moonlight-core/Android.mk
