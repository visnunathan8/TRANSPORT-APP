package com.example.javacv.stream.test2;

import android.Manifest;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.PowerManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private final static String LOG_TAG = "MainActivity";

    private PowerManager.WakeLock mWakeLock;

    private String ffmpeg_link = "rtsp://live:live@10.4.62.18:1935/live/mpegts.stream";

    private volatile FFmpegFrameRecorder recorder;
    boolean recording = false;
    long startTime = 0;

    private int sampleAudioRateInHz = 44100;
    private int imageWidth = 320;
    private int imageHeight = 240;
    private int frameRate = 30;

    private Thread audioThread;
    volatile boolean runAudioThread = true;
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;

    private CameraView cameraView;

    private Frame yuvImage;

    private Button recordButton;
    private LinearLayout mainLayout;
    private boolean init = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean hasPermissions = EasyPermissions.hasPermissions(this, Manifest.permission.CAMERA)
                && EasyPermissions.hasPermissions(this, Manifest.permission.RECORD_AUDIO)
                && EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (hasPermissions) {
            if (!init) {
                initLayout();
                init = true;
            }
        } else {
            Toast.makeText(this, "Please, grant all permissions in app settings", Toast.LENGTH_LONG).show();
        }

        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE); 
            mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, LOG_TAG); 
            mWakeLock.acquire(); 
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        recording = false;
    }


    private void initLayout() {

        mainLayout = (LinearLayout) this.findViewById(R.id.record_layout);

        recordButton = (Button) findViewById(R.id.recorder_control);
        recordButton.setText("Start");
        recordButton.setOnClickListener(this);

        cameraView = new CameraView(this);

        LinearLayout.LayoutParams layoutParam = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        mainLayout.addView(cameraView, layoutParam);
        Log.v(LOG_TAG, "added cameraView to mainLayout");
    }

    private void initRecorder() {
        Log.w(LOG_TAG,"initRecorder");


        // region
        yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
        Log.d(LOG_TAG, "IplImage.create");
        // endregion

        recorder = new FFmpegFrameRecorder(ffmpeg_link, imageWidth, imageHeight, 1);
        Log.v(LOG_TAG, "FFmpegFrameRecorder: " + ffmpeg_link + " imageWidth: " + imageWidth + " imageHeight " + imageHeight);

        recorder.setFormat("flv");
        Log.v(LOG_TAG, "recorder.setFormat(\"flv\")");

        recorder.setSampleRate(sampleAudioRateInHz);
        Log.v(LOG_TAG, "recorder.setSampleRate(sampleAudioRateInHz)");

        // re-set in the surface changed method as well
        recorder.setFrameRate(frameRate);
        Log.v(LOG_TAG, "recorder.setFrameRate(frameRate)");

        // Create audio recording thread
        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable);
    }

    // Start the capture
    public void startRecord() {
        initRecorder();

        try {
            recorder.start();
            startTime = System.currentTimeMillis();
            recording = true;
            audioThread.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    public void stopRecord() {
    	// This should stop the audio thread from running
    	runAudioThread = false;

        if (recorder != null && recording) {
            recording = false;
            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");
            try {
                recorder.stop();
                recorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            recorder = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
    	// Quit when back button is pushed
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (recording) {
                stopRecord();
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onClick(View v) {
        if (!recording) {
            startRecord();
            Log.w(LOG_TAG, "Start Button Pushed");
            recordButton.setText("Stop");
        } else {
            stopRecord();
            Log.w(LOG_TAG, "Stop Button Pushed");
            recordButton.setText("Start");
        }
    }

    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            int bufferSize;
            short[] audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz, 
                    AudioFormat.CHANNEL_CONFIGURATION_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz, 
                    AudioFormat.CHANNEL_CONFIGURATION_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = new short[bufferSize];

            Log.d(LOG_TAG, "audioRecord.startRecord()");
            audioRecord.startRecording();


            while (runAudioThread) {
                bufferReadResult = audioRecord.read(audioData, 0, audioData.length);
                if (bufferReadResult > 0) {

                    if (recording) {
                        try {
                            recorder.recordSamples(ShortBuffer.wrap(audioData, 0, bufferReadResult));
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.e(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(LOG_TAG,"AudioThread Finished");

            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.v(LOG_TAG,"audioRecord released");
            }
        }
    }

    class CameraView extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {

    	private boolean previewRunning = false;
    	
        private SurfaceHolder holder;
        private Camera camera;
        
        private byte[] previewBuffer;
        
        long videoTimestamp = 0;

        Bitmap bitmap;
        Canvas canvas;
        
        public CameraView(Context _context) {
            super(_context);
            
            holder = this.getHolder();
            holder.addCallback(this);
            holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);

            try {
                camera.setPreviewDisplay(holder);
                camera.setPreviewCallback(this);

                Camera.Parameters currentParams = camera.getParameters();
                Log.v(LOG_TAG,"Preview Framerate: " + currentParams.getPreviewFrameRate());
                Log.v(LOG_TAG,"Preview imageWidth: " + currentParams.getPreviewSize().width + " imageHeight: " + currentParams.getPreviewSize().height);

                imageWidth = currentParams.getPreviewSize().width;
                imageHeight = currentParams.getPreviewSize().height;
                frameRate = currentParams.getPreviewFrameRate();

                bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ALPHA_8);



                camera.startPreview();
                previewRunning = true;
            }
            catch (IOException e) {
                Log.v(LOG_TAG,e.getMessage());
                e.printStackTrace();
            }
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.v(LOG_TAG,"Surface Changed: width " + width + " height: " + height);



            // Get the current parameters
            Camera.Parameters currentParams = camera.getParameters();
            Log.v(LOG_TAG,"Preview Framerate: " + currentParams.getPreviewFrameRate());
            Log.v(LOG_TAG,"Preview imageWidth: " + currentParams.getPreviewSize().width + " imageHeight: " + currentParams.getPreviewSize().height);

            imageWidth = currentParams.getPreviewSize().width;
            imageHeight = currentParams.getPreviewSize().height;
            frameRate = currentParams.getPreviewFrameRate();


            yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            try {
                camera.setPreviewCallback(null);

                previewRunning = false;
                camera.release();

            } catch (RuntimeException e) {
                Log.v(LOG_TAG,e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {

            if (yuvImage != null && recording) {
                videoTimestamp = 1000 * (System.currentTimeMillis() - startTime);

                ((ByteBuffer)yuvImage.image[0].position(0)).put(data);

            	bitmap.copyPixelsFromBuffer(yuvIplimage.getByteBuffer());

            	canvas = new Canvas(bitmap);
            	Paint paint = new Paint(); 
            	paint.setColor(Color.GREEN);
            	float leftx = 20; 
            	float topy = 20; 
            	float rightx = 50; 
            	float bottomy = 100; 
            	RectF rectangle = new RectF(leftx,topy,rightx,bottomy); 
            	canvas.drawRect(rectangle, paint);

            	bitmap.copyPixelsToBuffer(yuvIplimage.getByteBuffer());

                try {

                    recorder.setTimestamp(videoTimestamp);

                    recorder.record(yuvImage);

                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.v(LOG_TAG,e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }
}
