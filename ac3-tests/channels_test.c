#include "../app/src/main/jni/ac3/channels.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
int main(void) {
 float data[6][5],*planes[6];int16_t pcm[30]={0};
 for(int c=0;c<6;c++)planes[c]=data[c];
 for(int selected=0;selected<6;selected++) {
  for(int i=0;i<30;i++)pcm[i]=0;
  for(int i=0;i<5;i++)pcm[i*6+selected]=selected==3?-32768:32767;
  ac3_planar(pcm,planes,5);
  for(int c=0;c<6;c++)for(int i=0;i<5;i++) {
   float expected=c==selected?(selected==3?-1.0f:32767.0f/32768.0f):0;
   assert(fabsf(planes[c][i]-expected)<0.000001f);
  }
 }
 puts("PASS: FL FR C LFE BL BR planar conversion, silence isolation, signed extrema");
}
