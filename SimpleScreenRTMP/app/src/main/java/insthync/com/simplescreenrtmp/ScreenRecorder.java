package insthync.com.simplescreenrtmp;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import me.lake.librestreaming.core.MediaCodecHelper;
import me.lake.librestreaming.core.Packager;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.rtmp.RESFlvData;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.rtmp.RESRtmpSender;
import me.lake.librestreaming.tools.LogTools;

public class ScreenRecorder extends Thread {
    private static final String TAG = "ScreenRecorder";

    private int mDpi;
    private MediaProjection mMediaProjection;
    private RESCoreParameters mCoreParameters;
    private MediaCodec mEncoder;
    private Surface mSurface;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private VirtualDisplay mVirtualDisplay;
    // Send data to server
    private static final long WAIT_TIME = 1000000000;
    private long startTime = 0;
    private RESFlvDataCollecter dataCollecter;

    public ScreenRecorder(RESCoreParameters coreParameters, int dpi, MediaProjection mp, RESFlvDataCollecter flvDataCollecter) {
        super(TAG);
        mDpi = dpi;
        mMediaProjection = mp;
        mCoreParameters = coreParameters;
        startTime = 0;
        dataCollecter = flvDataCollecter;
    }

    /**
     * stop task
     */
    public final void quit() {
        mQuit.set(true);
    }

    @Override
    public void run() {
        try {
            try {
                prepareEncoder();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display",
                    mCoreParameters.videoWidth, mCoreParameters.videoHeight, mDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    mSurface, null, null);
            Log.d(TAG, "created virtual display: " + mVirtualDisplay);
            recordVirtualDisplay();
        } finally {
            release();
        }
    }

    private void recordVirtualDisplay() {
        while (!mQuit.get()) {
            int eobIndex = MediaCodec.INFO_TRY_AGAIN_LATER;
            try {
                eobIndex = mEncoder.dequeueOutputBuffer(mBufferInfo, WAIT_TIME);
            } catch (Exception ignored) {
            }
            switch (eobIndex) {
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    LogTools.d("VideoSenderThread,MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
                    break;
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    LogTools.d("VideoSenderThread,MediaCodec.INFO_TRY_AGAIN_LATER");
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    LogTools.d("VideoSenderThread,MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:" + mEncoder.getOutputFormat().toString());
                    sendAVCDecoderConfigurationRecord(0, mEncoder.getOutputFormat());
                    break;
                default:
                    if (startTime == 0) {
                        startTime = mBufferInfo.presentationTimeUs / 1000;
                    }
                    /**
                     * we send sps pps already in INFO_OUTPUT_FORMAT_CHANGED
                     * so we ignore MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                     */
                    if (mBufferInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        if (mBufferInfo.size != 0) {
                            ByteBuffer realData = mEncoder.getOutputBuffer(eobIndex);
                            realData.position(mBufferInfo.offset + 4);
                            realData.limit(mBufferInfo.offset + mBufferInfo.size);
                            sendRealData((mBufferInfo.presentationTimeUs / 1000) - startTime, realData);
                        }
                    }
                    mEncoder.releaseOutputBuffer(eobIndex, false);
                    break;
            }
        }
    }

    private void prepareEncoder() throws IOException {
        MediaFormat format = new MediaFormat();
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / mCoreParameters.mediacodecAVCFrameRate);
        mEncoder = MediaCodecHelper.createHardVideoMediaCodec(mCoreParameters, format);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mEncoder.createInputSurface();
        mEncoder.start();
    }

    private void release() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    private void sendAVCDecoderConfigurationRecord(long tms, MediaFormat format) {
        byte[] AVCDecoderConfigurationRecord = Packager.H264Packager.generateAVCDecoderConfigurationRecord(format);
        int packetLen = Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH +
                AVCDecoderConfigurationRecord.length;
        byte[] finalBuff = new byte[packetLen];
        Packager.FLVPackager.fillFlvVideoTag(finalBuff,
                0,
                true,
                true,
                AVCDecoderConfigurationRecord.length);
        System.arraycopy(AVCDecoderConfigurationRecord, 0,
                finalBuff, Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH, AVCDecoderConfigurationRecord.length);
        RESFlvData resFlvData = new RESFlvData();
        resFlvData.droppable = false;
        resFlvData.byteBuffer = finalBuff;
        resFlvData.size = finalBuff.length;
        resFlvData.dts = (int) tms;
        resFlvData.flvTagType = RESFlvData.FLV_RTMP_PACKET_TYPE_VIDEO;
        resFlvData.videoFrameType = RESFlvData.NALU_TYPE_IDR;
        dataCollecter.collect(resFlvData, RESRtmpSender.FROM_VIDEO);
    }

    private void sendRealData(long tms, ByteBuffer realData) {
        int realDataLength = realData.remaining();
        int packetLen = Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH +
                Packager.FLVPackager.NALU_HEADER_LENGTH +
                realDataLength;
        byte[] finalBuff = new byte[packetLen];
        realData.get(finalBuff, Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH +
                        Packager.FLVPackager.NALU_HEADER_LENGTH,
                realDataLength);

        sendRealData(tms, realDataLength, finalBuff);
    }

    private void sendRealData(long tms, int realDataLength, byte[] finalBuff)
    {
        int frameType = finalBuff[Packager.FLVPackager.FLV_VIDEO_TAG_LENGTH +
                Packager.FLVPackager.NALU_HEADER_LENGTH] & 0x1F;
        Packager.FLVPackager.fillFlvVideoTag(finalBuff,
                0,
                false,
                frameType == 5,
                realDataLength);
        RESFlvData resFlvData = new RESFlvData();
        resFlvData.droppable = true;
        resFlvData.byteBuffer = finalBuff;
        resFlvData.size = finalBuff.length;
        resFlvData.dts = (int) tms;
        resFlvData.flvTagType = RESFlvData.FLV_RTMP_PACKET_TYPE_VIDEO;
        resFlvData.videoFrameType = frameType;
        dataCollecter.collect(resFlvData, RESRtmpSender.FROM_VIDEO);
    }
}