package com.limelight.binding.audio;

import android.app.Activity;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Explicit diagnostic entry point. It never reads pairing data or starts a PC stream. */
public class Ac3ProbeActivity extends Activity {
    private volatile boolean cancelled;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String setting=getIntent().getStringExtra("format");
        if(setting!=null && (setting.equals("pcm") || setting.equals("ac3") || setting.equals("eac3") || setting.equals("auto"))) {
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString("list_audio_output_format",setting).commit();
            Log.i(Ac3Track.TAG,"saved output format="+setting);
            if(getIntent().getBooleanExtra("configureOnly",false)) {finish();return;}
        }
        TextView text=new TextView(this);text.setTextSize(32);text.setText("AC-3: spoken 5.1 test. Back stops playback.");setContentView(text);
        new Thread(()->runProbe(text),"AC3-probe").start();
    }
    private void runProbe(TextView text) {
        boolean encode=getIntent().getBooleanExtra("encode",false);
        boolean eac3=getIntent().getBooleanExtra("eac3",false);
        boolean outputOnly=getIntent().getBooleanExtra("outputOnly",false);
        boolean capture=getIntent().getBooleanExtra("capture",false);
        boolean iec=getIntent().getBooleanExtra("iec",false);
        int repeats=Math.max(1,Math.min(50,getIntent().getIntExtra("repeats",1)));
        int total=0;long encodeNs=0,maxEncode=0,writeNs=0,maxWrite=0,start=System.nanoTime();
        try {
            Ac3Track.capabilities(this);
            try(Ac3Track sink=outputOnly?null:new Ac3Track(iec,getIntent().getBooleanExtra("offload",true),getIntent().getBooleanExtra("carrierStereo",true),getIntent().getIntExtra("buffer",0),eac3);
                Ac3Encoder encoder=encode?new Ac3Encoder(eac3):null;
                FileOutputStream file=capture?openFileOutput(eac3?"native-selftest.eac3":"native-selftest.ac3",MODE_PRIVATE):null) {
                byte[] input=new byte[encode?1536*6*2:2560];
                short[] pcm=new short[1536*6];
                byte[] packet=new byte[2560];
                ByteBuffer coded=ByteBuffer.allocateDirect(2560);
                for(int repeat=0;repeat<repeats && !cancelled;repeat++) {
                    try(InputStream stream=getAssets().open(encode?"channels.pcm":eac3?"channels.eac3":"channels.ac3")) {
                        int count=0;
                        while(!cancelled) {
                            int offset=0,n;
                            while(offset<input.length && (n=stream.read(input,offset,input.length-offset))>0)offset+=n;
                            if(offset==0)break;
                            if(offset!=input.length)throw new IllegalStateException("Partial test frame");
                            if(count%125==0) {
                                final String label=new String[]{"Front left","Front right","Center","Subwoofer: low tone","Surround left","Surround right"}[Math.min(5,count/125)];
                                runOnUiThread(()->text.setText(label+"\n\n"+(encode?(eac3?"Live PCM → E-AC-3":"Live PCM → AC-3"):(eac3?"E-AC-3 fixture":"AC-3 fixture"))+"\nBack stops playback."));
                            }
                            if(encode) {
                                ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                                long before=System.nanoTime();encoder.encode(pcm,coded);long elapsed=System.nanoTime()-before;
                                encodeNs+=elapsed;maxEncode=Math.max(maxEncode,elapsed);
                            } else {coded.clear();coded.put(input);coded.flip();}
                            if(file!=null) {coded.get(packet);coded.rewind();file.write(packet);}
                            if(sink!=null) {long before=System.nanoTime();sink.write(coded,2560);long elapsed=System.nanoTime()-before;writeNs+=elapsed;maxWrite=Math.max(maxWrite,elapsed);}
                            total++;count++;
                        }
                    }
                }
                if(sink!=null) {sink.logRoute();if(!cancelled)android.os.SystemClock.sleep(300);}
            }
            Log.i(Ac3Track.TAG,"probe complete codec="+(eac3?"EAC3":"AC3")+" encode="+encode+" outputOnly="+outputOnly+" frames="+total+" cancelled="+cancelled
                +" encodeAvgMs="+(total==0?0:encodeNs/total/1e6)+" encodeMaxMs="+maxEncode/1e6
                +" writeAvgMs="+(total==0?0:writeNs/total/1e6)+" writeMaxMs="+maxWrite/1e6+" wallMs="+(System.nanoTime()-start)/1e6);
        } catch(Exception | LinkageError e) {Log.e(Ac3Track.TAG,"probe failed",e);}
    }
    @Override public void onDestroy() {cancelled=true;super.onDestroy();}
}
