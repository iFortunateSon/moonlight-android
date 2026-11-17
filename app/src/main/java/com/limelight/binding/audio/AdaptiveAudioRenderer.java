package com.limelight.binding.audio;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;
import com.limelight.R;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/** Keeps the upstream PCM renderer intact, including stereo and AudioFX. */
public final class AdaptiveAudioRenderer implements AudioRenderer {
    private final Context context;
    private final boolean audioFx;
    private AudioRenderer selected;
    private MoonBridge.AudioConfiguration config;
    private int rate,packetSamples;
    private long pcmPackets,pcmWriteNanos,pcmWriteMax;
    public AdaptiveAudioRenderer(Context context,boolean audioFx) {this.context=context;this.audioFx=audioFx;}
    private boolean suitable() {
        if(Build.VERSION.SDK_INT<29 || !Ac3Track.supported())return false;
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if(d.getType()!=AudioDeviceInfo.TYPE_HDMI && d.getType()!=AudioDeviceInfo.TYPE_HDMI_ARC && d.getType()!=AudioDeviceInfo.TYPE_HDMI_EARC)continue;
            for(int encoding:d.getEncodings())if(encoding==AudioFormat.ENCODING_AC3)return true;
        }
        return false;
    }
    private void message() {message(context.getString(R.string.surround_pcm_fallback));}
    private void message(String text) {new Handler(Looper.getMainLooper()).post(()->Toast.makeText(context,text,Toast.LENGTH_LONG).show());}
    private boolean dolby(boolean eac3) {
        Ac3AudioRenderer renderer=new Ac3AudioRenderer(context,eac3);
        if(renderer.setup(config,rate,packetSamples)==0) {selected=renderer;return true;}
        return false;
    }
    private int pcm() {
        AudioRenderer pcm=new AndroidAudioRenderer(context,audioFx);
        Log.i(Ac3Track.TAG,"selected PCM original channels="+config.channelCount);
        int result=pcm.setup(config,rate,packetSamples);
        selected=result==0?pcm:null;return result;
    }
    public int setup(MoonBridge.AudioConfiguration config,int rate,int packetSamples) {
        this.config=config;this.rate=rate;this.packetSamples=packetSamples;
        String format=PreferenceManager.getDefaultSharedPreferences(context).getString("list_audio_output_format","pcm");
        if((format.equals("eac3") || format.equals("ac3") || format.equals("auto")) && config.channelCount==6 && rate==48000 && Build.VERSION.SDK_INT>=29
                && (!format.equals("auto") || suitable())) {
            if(dolby(format.equals("eac3")))return 0;
            if(format.equals("eac3") && dolby(false)) {message(context.getString(R.string.surround_ac3_setup_fallback));return 0;}
            message();
        } else if((format.equals("eac3") || format.equals("ac3")) && config.channelCount!=2)message();
        return pcm();
    }
    public void playDecodedAudio(short[] data) {
        if(selected instanceof Ac3AudioRenderer && ((Ac3AudioRenderer)selected).failed()) {
            boolean wasEac3=((Ac3AudioRenderer)selected).isEac3();
            selected.stop();selected.cleanup();
            if(wasEac3 && dolby(false)) {message(context.getString(R.string.surround_ac3_runtime_fallback));selected.start();}
            else {
                message();
                if(pcm()!=0) {Log.e(Ac3Track.TAG,"PCM fallback setup failed");selected=null;return;}
                selected.start();
            }
        }
        if(selected instanceof AndroidAudioRenderer) {
            long before=System.nanoTime();selected.playDecodedAudio(data);long elapsed=System.nanoTime()-before;
            pcmPackets++;pcmWriteNanos+=elapsed;pcmWriteMax=Math.max(pcmWriteMax,elapsed);
            if(pcmPackets%1000==0)Log.i(Ac3Track.TAG,"PCM baseline packets="+pcmPackets+" writeAvgMs="+pcmWriteNanos/pcmPackets/1e6+" writeMaxMs="+pcmWriteMax/1e6+" pendingMs="+MoonBridge.getPendingAudioDuration()+" outputLatency=unmeasured");
        } else if(selected!=null)selected.playDecodedAudio(data);
    }
    void failForTest() {if(selected instanceof Ac3AudioRenderer)((Ac3AudioRenderer)selected).failForTest();}
    public void start() {if(selected!=null)selected.start();}
    public void stop() {if(selected!=null)selected.stop();}
    public void cleanup() {if(selected!=null)selected.cleanup();}
}
