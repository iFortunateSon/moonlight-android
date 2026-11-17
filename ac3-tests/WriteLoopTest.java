import com.limelight.binding.audio.Ac3WriteLoop;
import java.nio.ByteBuffer;
public class WriteLoopTest {
 static class Clock implements Ac3WriteLoop.Clock {long t;public long millis(){return t;}public void idle(){t++;}}
 static void failure(Runnable run){try{run.run();throw new AssertionError("error accepted");}catch(IllegalStateException expected){}}
 public static void main(String[] args) {
  ByteBuffer b=ByteBuffer.allocate(2560);Clock clock=new Clock();int[] count={0};
  Ac3WriteLoop.writeFully(b,data->{if(count[0]++%3==0)return 0;int n=Math.min(73,data.remaining());data.position(data.position()+n);return n;},clock,150);
  if(b.hasRemaining())throw new AssertionError("partial writes");
  failure(()->Ac3WriteLoop.writeFully(ByteBuffer.allocate(10),d->-6,new Clock(),150));
  Clock stalled=new Clock();failure(()->Ac3WriteLoop.writeFully(ByteBuffer.allocate(10),d->0,stalled,150));
  if(stalled.t!=150)throw new AssertionError("deadline");
  failure(()->Ac3WriteLoop.writeFully(ByteBuffer.allocate(10),d->1,new Clock(),150));
  ByteBuffer fresh=ByteBuffer.allocate(10);
  Ac3WriteLoop.writeFully(fresh,d->{int n=d.remaining();d.position(d.limit());return n;},new Clock(),150);
  if(fresh.hasRemaining())throw new AssertionError("fresh stream after failure");
  System.out.println("PASS: partial/zero writes, DEAD_OBJECT, bounded stalls, progress validation, fresh stream after failure");
 }
}
