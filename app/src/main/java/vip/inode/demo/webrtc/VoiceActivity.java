package vip.inode.demo.webrtc;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;

import com.blankj.utilcode.util.ArrayUtils;
import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.UriUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class VoiceActivity extends AppCompatActivity {

    private Switch mEnableNsAgcSwitch;
    private Button mStartBtn;
    private Button mStopBtn;
    private Button mStartPlay;
    private RecordThread thread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);
        mEnableNsAgcSwitch = findViewById(R.id.enable_ns_agc_switch);
        mStartBtn  =findViewById(R.id.start_btn);
        mStopBtn = findViewById(R.id.stop_btn);
        mStartPlay = findViewById(R.id.start_play);
        mStartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                thread = new RecordThread();
                thread.start();
            }
        });
        mStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (thread != null){
                    thread.stopRecord();
                }
            }
        });
        mStartPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        playVoice();
                    }
                }).start();
            }
        });
    }

    private void playVoice(){
        AudioManager  audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build();
        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int sessionId = audioManager.generateAudioSessionId();
        AudioTrack audioTrack = new AudioTrack(
                audioAttributes, audioFormat, bufferSize, AudioTrack.MODE_STREAM, sessionId);
        audioTrack.play();
        try {
            FileInputStream fileInputStream = new FileInputStream(new File(getExternalCacheDir(), "a.mp3"));
            byte[] datas = new byte[1024];
            while (true){
                int read = fileInputStream.read(datas, 0, datas.length);
                if (read <= 0){
                    break;
                }
                audioTrack.write(datas,0,read);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        audioTrack.stop();
        audioTrack.release();
    }

    private class RecordThread extends Thread {
        private AudioRecord mAudioRecord;
        private boolean mIsRecord = true;
        private final int mBufferSize;
        private File mFile;
        private NoiseSuppressorUtils nsUtils;
        private long nsxId;
        private AutomaticGainControlUtils agcUtils;
        private long agcId;

        RecordThread() {
            mBufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, mBufferSize);
            mFile = new File(getExternalCacheDir(),"a.mp3");
            nsUtils = new NoiseSuppressorUtils();
            agcUtils = new AutomaticGainControlUtils();
        }

        public void stopRecord() {
            mIsRecord = false;
        }

        @Override
        public void run() {
            super.run();
            nsxId = nsUtils.nsxCreate();
            int nsxInit = nsUtils.nsxInit(nsxId, 16000);
            int nexSetPolicy = nsUtils.nsxSetPolicy(nsxId, 2);
            agcId = agcUtils.agcCreate();
            int agcInitResult = agcUtils.agcInit(agcId, 0, 255, 3, 16000);
            int agcSetConfigResult = agcUtils.agcSetConfig(agcId, new Integer(9).shortValue(), new Integer(9).shortValue(), true);

            mAudioRecord.startRecording();
            byte[] buffer = new byte[mBufferSize];
            FileUtils.delete(mFile);
            while (mIsRecord) {
                int read = mAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    if (mEnableNsAgcSwitch.isChecked()){
                        int index = 0;
                        while (true){
                            if (index >= buffer.length){
                                break;
                            }
                            int end = index + 320;
                            if (end > buffer.length) {
                                break;
                            }
                            byte[] output = ArrayUtils.subArray(buffer, index, end);
                            short[] inputData = new short[160];
                            short[] outNsData = new short[160];
                            short[] outAgcData = new short[160];
                            ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputData);
                            nsUtils.nsxProcess(nsxId, inputData, 1, outNsData);
                            agcUtils.agcProcess(agcId, outNsData, 1, 160, outAgcData,
                                    0, 0, 0, false);
                            byte[] outDatas = new byte[320];
                            for (int i = 0; i < inputData.length; i++) {
                                short outAgcDatum = inputData[i];
                                byte high = (byte) ((0xFF00 & outAgcDatum)>>8);//定义第一个byte
                                byte low = (byte) (0x00FF & outAgcDatum);
                                outDatas[i * 2 + 1] = high;
                                outDatas[i * 2] = low;
                            }
                            FileIOUtils.writeFileFromBytesByStream(mFile,outDatas,true);

                            index += 320;
                        }
                    }else{
                        FileIOUtils.writeFileFromBytesByStream(mFile,buffer,true);
                    }
                }
            }
            nsUtils.nsxFree(nsxId);
            agcUtils.agcFree(agcId);
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }
}