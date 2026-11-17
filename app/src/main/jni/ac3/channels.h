#ifndef MOONLIGHT_AC3_CHANNELS_H
#define MOONLIGHT_AC3_CHANNELS_H
#include <stdint.h>
// Moonlight RtspConnection.c normalizes Opus to FL FR C LFE BL BR.
// AV_CHANNEL_LAYOUT_5POINT1_BACK has exactly these lanes. FFmpeg handles
// the AC-3 bitstream order (FL C FR BL BR LFE) internally.
static inline void ac3_planar(const int16_t *pcm, float **planes, int samples) {
    for (int i=0; i<samples; ++i)
        for (int ch=0; ch<6; ++ch) planes[ch][i] = pcm[i*6+ch] * (1.0f/32768.0f);
}
#endif
