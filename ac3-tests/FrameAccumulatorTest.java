import com.limelight.binding.audio.PcmFrameAccumulator;
import java.util.*;
public class FrameAccumulatorTest {
 public static void main(String[] args) {
  for(int packet:new int[]{1,3,120,240,480,960,1536,2000,4608}) {
   PcmFrameAccumulator a=new PcmFrameAccumulator();
   int samples=1536*3+731;
   short[] source=new short[samples*6];
   for(int i=0;i<source.length;i++)source[i]=(short)(i*17+3);
   List<short[]> frames=new ArrayList<>();
   for(int offset=0;offset<samples;offset+=packet) {
    int n=Math.min(packet,samples-offset);
    short[] input=Arrays.copyOfRange(source,offset*6,(offset+n)*6);
    a.append(input,offset*1000000000L/48000,(data,first)->frames.add(data.clone()));
    Arrays.fill(input,(short)-1); // JNI reuses its array immediately after returning.
   }
   if(frames.size()!=3 || a.bufferedSamples()!=731)throw new AssertionError("packet="+packet);
   for(int f=0;f<3;f++)for(int i=0;i<9216;i++)
    if(frames.get(f)[i]!=source[f*9216+i])throw new AssertionError("channel or accumulation mismatch");
   a.reset();if(a.bufferedSamples()!=0)throw new AssertionError("reset");
   try {a.append(new short[7],0,(d,t)->{});throw new AssertionError("partial sample accepted");}
   catch(IllegalArgumentException expected){}
  }
  PcmFrameAccumulator gap=new PcmFrameAccumulator();
  short[] old=new short[240*6];Arrays.fill(old,(short)3);
  short[] fresh=new short[1296*6];Arrays.fill(fresh,(short)7);
  List<short[]> emitted=new ArrayList<>();
  gap.append(old,0,(d,t)->emitted.add(d.clone()));
  gap.append(fresh,100000000L,(d,t)->emitted.add(d.clone()));
  if(!emitted.isEmpty() || gap.discardedSamples()!=240 || gap.bufferedSamples()!=1296)throw new AssertionError("stale partial not reset");
  short[] tail=new short[240*6];Arrays.fill(tail,(short)7);
  gap.append(tail,105000000L,(d,t)->emitted.add(d.clone()));
  if(emitted.size()!=1)throw new AssertionError("fresh frame missing");
  for(short v:emitted.get(0))if(v!=7)throw new AssertionError("stale audio leaked across silence");
  System.out.println("PASS: discontinuity reset, no stale PCM across source silence");
  System.out.println("PASS: accumulation, reused input, all six lanes, variable packet sizes, partial sample rejection, reset");
 }
}
