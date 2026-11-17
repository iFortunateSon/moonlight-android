#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include "channels.h"

typedef struct { AVCodecContext *codec; AVFrame *frame; AVPacket *packet; int64_t pts; } Encoder;
static void release(Encoder *e) {
    if (!e) return;
    av_packet_free(&e->packet); av_frame_free(&e->frame); avcodec_free_context(&e->codec); free(e);
}
JNIEXPORT jlong JNICALL Java_com_limelight_binding_audio_Ac3Encoder_create(JNIEnv *env,jclass cls,jboolean eac3) {
    Encoder *e=calloc(1,sizeof(*e));
    if (!e) return 0;
    const AVCodec *codec=avcodec_find_encoder(eac3?AV_CODEC_ID_EAC3:AV_CODEC_ID_AC3);
    if (!codec || !(e->codec=avcodec_alloc_context3(codec))) { release(e); return 0; }
    e->codec->sample_rate=48000; e->codec->bit_rate=640000;
    e->codec->sample_fmt=AV_SAMPLE_FMT_FLTP; e->codec->thread_count=1;
    e->codec->ch_layout=(AVChannelLayout)AV_CHANNEL_LAYOUT_5POINT1_BACK;
    if (avcodec_open2(e->codec,codec,NULL)<0 || e->codec->frame_size!=1536) { release(e); return 0; }
    e->frame=av_frame_alloc(); e->packet=av_packet_alloc();
    if (!e->frame || !e->packet) { release(e); return 0; }
    e->frame->format=e->codec->sample_fmt; e->frame->sample_rate=48000; e->frame->nb_samples=1536;
    if (av_channel_layout_copy(&e->frame->ch_layout,&e->codec->ch_layout)<0 || av_frame_get_buffer(e->frame,0)<0) { release(e); return 0; }
    return (jlong)(intptr_t)e;
}
JNIEXPORT jint JNICALL Java_com_limelight_binding_audio_Ac3Encoder_encodeNative(JNIEnv *env,jclass cls,jlong handle,jshortArray pcm,jobject output) {
    Encoder *e=(Encoder*)(intptr_t)handle;
    if (!e || (*env)->GetArrayLength(env,pcm)!=1536*6 || (*env)->GetDirectBufferCapacity(env,output)<2560) return AVERROR(EINVAL);
    uint8_t *dest=(*env)->GetDirectBufferAddress(env,output);
    if (!dest || av_frame_make_writable(e->frame)<0) return AVERROR(ENOMEM);
    jshort *src=(*env)->GetShortArrayElements(env,pcm,NULL);
    if (!src) return AVERROR(ENOMEM);
    ac3_planar(src,(float**)e->frame->extended_data,1536);
    (*env)->ReleaseShortArrayElements(env,pcm,src,JNI_ABORT);
    e->frame->pts=e->pts; e->pts+=1536;
    int result=avcodec_send_frame(e->codec,e->frame);
    if (result<0) return result;
    result=avcodec_receive_packet(e->codec,e->packet);
    if (result<0) return result;
    int length=e->packet->size;
    if (length==2560) memcpy(dest,e->packet->data,length);
    av_packet_unref(e->packet);
    return length==2560?length:AVERROR(EINVAL);
}
JNIEXPORT void JNICALL Java_com_limelight_binding_audio_Ac3Encoder_destroy(JNIEnv *env,jclass cls,jlong handle) { release((Encoder*)(intptr_t)handle); }
