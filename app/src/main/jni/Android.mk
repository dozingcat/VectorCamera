LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_C_INCLUDES := .
include jni/libvpx/build/make/Android.mk

