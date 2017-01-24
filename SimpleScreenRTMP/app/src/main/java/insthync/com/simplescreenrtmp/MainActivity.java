package insthync.com.simplescreenrtmp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import me.lake.librestreaming.client.RESAudioClient;
import me.lake.librestreaming.model.RESConfig;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.rtmp.RESFlvData;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.rtmp.RESRtmpSender;

public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_SCREEN_CAPTURE = 1;
    private static final int REQUEST_STREAM = 2;
    private static String[] PERMISSIONS_STREAM = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    // RTMP Constraints
    private static final String DEFAULT_RMTP_HOST = "188.166.191.129";
    private static final int DEFAULT_RTMP_PORT = 1935;
    private static final String DEFAULT_APP_NAME = "live";
    private static final String DEFAULT_PUBLISH_NAME = "test";
    // General
    private MediaProjectionManager mMediaProjectionManager;
    private ScreenRecorder mScreenRecorder;
    private Button mButton;
    private EditText mTextServerAddress;
    private EditText mTextServerPort;
    private EditText mTextAppName;
    private EditText mTextPublishName;
    // RTMP
    boolean authorized = false;
    private RESRtmpSender rtmpSender;
    private RESFlvDataCollecter dataCollecter;
    private RESCoreParameters coreParameters;
    private RESAudioClient audioClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mButton = (Button) findViewById(R.id.toggle);
        mButton.setOnClickListener(this);
        mButton.setText("Start recorder");
        mTextServerAddress = (EditText) findViewById(R.id.serverAddress);
        mTextServerPort = (EditText) findViewById(R.id.serverPort);
        mTextAppName = (EditText) findViewById(R.id.appName);
        mTextPublishName = (EditText) findViewById(R.id.publishName);
        mTextServerAddress.setText(DEFAULT_RMTP_HOST);
        mTextServerPort.setText(""+DEFAULT_RTMP_PORT);
        mTextAppName.setText(DEFAULT_APP_NAME);
        mTextPublishName.setText(DEFAULT_PUBLISH_NAME);
        //noinspection ResourceType
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        coreParameters = new RESCoreParameters();
        coreParameters.printDetailMsg = false;
        coreParameters.senderQueueLength = 150;

        verifyPermissions();
    }

    public void verifyPermissions() {
        int CAMERA_permission = ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA);
        int RECORD_AUDIO_permission = ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO);
        int WRITE_EXTERNAL_STORAGE_permission = ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (CAMERA_permission != PackageManager.PERMISSION_GRANTED ||
                RECORD_AUDIO_permission != PackageManager.PERMISSION_GRANTED ||
                WRITE_EXTERNAL_STORAGE_permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    MainActivity.this,
                    PERMISSIONS_STREAM,
                    REQUEST_STREAM
            );
            authorized = false;
        } else {
            authorized = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STREAM) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[2] == PackageManager.PERMISSION_GRANTED) {
                authorized = true;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e("@@", "media projection is null");
            return;
        }

        mScreenRecorder = new ScreenRecorder(coreParameters, 1, mediaProjection, dataCollecter);
        mScreenRecorder.start();
        rtmpSender.start(coreParameters.rtmpAddr);
        audioClient.start(dataCollecter);

        mButton.setText("Stop Recorder");
        Toast.makeText(this, "Screen recorder is running...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onClick(View v) {
        final String serverAddress = mTextServerAddress.getText().toString();
        final int serverPort = Integer.parseInt(mTextServerPort.getText().toString());
        final String appName = mTextAppName.getText().toString();
        final String publishName = mTextPublishName.getText().toString();
        final String rmptAddress = "rtmp://" + serverAddress + ":" + serverPort + "/" + appName + "/" + publishName;
        if (rtmpSender != null || mScreenRecorder != null) {
            release();
            mButton.setText("Start recorder");
        } else {
            DisplayMetrics displaymetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

            // video size
            final int width = 640;
            final int height = 480;
            final int bitrate = 600000;
            // Video setup
            coreParameters.rtmpAddr = rmptAddress;
            coreParameters.videoWidth = width;
            coreParameters.videoHeight = height;
            coreParameters.mediacdoecAVCBitRate = bitrate;
            coreParameters.mediacodecAVCIFrameInterval = 10;
            coreParameters.mediacodecAVCFrameRate = 15;
            // Audio setup
            audioClient = new RESAudioClient(coreParameters);
            audioClient.prepare(RESConfig.obtain());

            rtmpSender = new RESRtmpSender();
            rtmpSender.prepare(coreParameters);

            dataCollecter = new RESFlvDataCollecter() {
                @Override
                public void collect(RESFlvData flvData, int type) {
                    rtmpSender.feed(flvData, type);
                }
            };

            coreParameters.done = true;
            Log.d(TAG, "===INFO===coreParametersReady:");
            Log.d(TAG, coreParameters.toString());

            Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
    }

    private void release()
    {
        if (mScreenRecorder != null) {
            mScreenRecorder.quit();
            mScreenRecorder = null;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (rtmpSender != null) {
                    rtmpSender.stop();
                    rtmpSender.destroy();
                    rtmpSender = null;
                }

                if (audioClient != null) {
                    audioClient.stop();
                    audioClient.destroy();
                    audioClient = null;
                }
            }
        }).start();
    }
}