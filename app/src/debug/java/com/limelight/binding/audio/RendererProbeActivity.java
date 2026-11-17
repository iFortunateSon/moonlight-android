package com.limelight.binding.audio;
import android.app.Activity;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;
import com.limelight.nvstream.jni.MoonBridge;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.LockSupport;

/** Paced 5 ms PCM packets through the exact streaming renderer. Debug diagnostics only. */
public class RendererProbeActivity extends Activity {
    private volatile boolean cancelled;
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!com.limelight.BuildConfig.DEBUG) {finish();return;}
        TextView text=new TextView(this);text.setTextSize(32);text.setText("Renderer test: 5 ms PCM packets. Back stops. See MoonlightAC3 Logcat.");setContentView(text);
        new Thread(this::test,"Renderer-probe").start();
    }
    private void test() {
        int channels=getIntent().getIntExtra("channels",6);
        String format=getIntent().getStringExtra("format");if(format==null)format="ac3";
        if(channels!=2 && channels!=6)throw new IllegalArgumentException("channels");
        if(!format.equals("pcm") && !format.equals("ac3") && !format.equals("eac3") && !format.equals("auto"))throw new IllegalArgumentException("format");
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString("list_audio_output_format",format).commit();
        int repeats=Math.max(1,Math.min(50,getIntent().getIntExtra("repeats",1)));
        boolean inject=getIntent().getBooleanExtra("fail",false),injected=false,secondInjected=false;
        AdaptiveAudioRenderer renderer=new AdaptiveAudioRenderer(this,false);
        long packets=0,late=0,start=System.nanoTime(),deadline=start;
        try {
            if(renderer.setup(channels==6?MoonBridge.AUDIO_CONFIGURATION_51_SURROUND:MoonBridge.AUDIO_CONFIGURATION_STEREO,48000,240)!=0)throw new IllegalStateException("renderer setup");
            renderer.start();
            byte[] bytes=new byte[240*6*2];short[] input=new short[240*6],pcm=new short[240*channels];
            for(int repeat=0;repeat<repeats && !cancelled;repeat++)try(InputStream stream=getAssets().open("channels.pcm")) {
                while(!cancelled) {
                    int offset=0,n;while(offset<bytes.length && (n=stream.read(bytes,offset,bytes.length-offset))>0)offset+=n;
                    if(offset==0)break;if(offset!=bytes.length)throw new IllegalStateException("partial source");
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(input);
                    if(channels==6)System.arraycopy(input,0,pcm,0,input.length);
                    else for(int i=0;i<240;i++) {pcm[2*i]=input[6*i];pcm[2*i+1]=input[6*i+1];}
                    if(inject && !injected && packets==600) {renderer.failForTest();injected=true;}
                    if(inject && !secondInjected && packets==1200 && getIntent().getBooleanExtra("failTwice",false)) {renderer.failForTest();secondInjected=true;}
                    renderer.playDecodedAudio(pcm);packets++;
                    deadline+=5000000L;long now=System.nanoTime();
                    if(now-deadline>20000000L) {late++;deadline=now;}
                    while(!cancelled && (now=System.nanoTime())<deadline)LockSupport.parkNanos(deadline-now);
                }
            }
            Log.i(Ac3Track.TAG,"renderer probe complete format="+format+" channels="+channels+" packets="+packets+" pacingResets="+late+" injected="+injected+" secondInjected="+secondInjected+" cancelled="+cancelled+" wallMs="+(System.nanoTime()-start)/1e6);
        } catch(Exception | LinkageError e) {Log.e(Ac3Track.TAG,"renderer probe failed",e);}
        finally {renderer.stop();renderer.cleanup();}
    }
    protected void onDestroy() {cancelled=true;super.onDestroy();}
}
