package com.ruijia.qrcode;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.ruijia.qrcode.listener.OnServiceAndActListener;
import com.ruijia.qrcode.persmission.PermissionHelper;
import com.ruijia.qrcode.persmission.PermissionInterface;
import com.ruijia.qrcode.service.QRXmitService;
import com.ruijia.qrcode.utils.CacheUtils;
import com.ruijia.qrcode.utils.CheckUtils;
import com.ruijia.qrcode.utils.CodeUtils;
import com.ruijia.qrcode.utils.ConvertUtils;
import com.ruijia.qrcode.utils.IOUtils;
import com.ruijia.qrcode.utils.SPUtil;

import org.w3c.dom.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lib.ruijia.zbar.ZBarContinueView;
import lib.ruijia.zbar.qrcodecore.BarcodeType;
import lib.ruijia.zbar.qrodecontinue.ContinueQRCodeView;

/**
 * 链路层 物理连接
 * 开启MainAct识别功能有两种方式，1：若MainAct没启动，使用service的serviceStartAct()方法启动 2：若MainAct已在前端显示，service使用接口回调启动
 */
public class MainAct extends BaseAct implements ContinueQRCodeView.Delegate {

    //===================变量=====================
    private long PSOTDELAY_TIME = 150;//发送间隔时间
    private long PSOTDELAY_TIME2 = 150;//缺失发送间隔时间
    private String sendOver_Contnet = "QrcodeContentSendOver";//发送端 所有数据一次发送完成，发送结束标记
    private String ReceiveOver_Content = "QrCodeContentReceiveOver";//接收端 完全收到数据，发送结束标记
    private String endTag = "RJQR";
    //控件
    private ZBarContinueView mZBarView; //zbar
    private ImageView img_result;
    //相机
//    int cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;//
    int cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;//
    //生成二维码使用
    private int size = 400;//200就够了，再大也没用
    private Handler handler;
    private String lastText;

    //===================发送端操作=====================
    List<String> sendDatas = new ArrayList<>();//发送端 数据
    List<Bitmap> sendImgs = new ArrayList<>();//发送端 数据
    //
    List<String> sendDatas2 = new ArrayList<>();//发送端 缺失的数据；String样式
    List<Bitmap> sendImgs2 = new ArrayList<>();//缺失的数据； Bitmap样式
    private String selectPath;

    //===================发送端二次+操作（收到反馈后的操作）=====================
    private List<Integer> backFlagList = new ArrayList<>();//发送端 返回缺失的标记,
    StringBuffer sendBuffer = new StringBuffer();  //统计结果
    private int sendCounts = 0;//发送次数统计，handler发送使用
    private boolean hasBack = false;//是否有缺失数据；

    //===================接收端操作=====================
    private Map<Integer, String> receveMap = new HashMap<>();//接收的数据暂时保存到map中，最终保存到receiveDatas
    private List<String> receiveDatas = new ArrayList<>();

    private int receveSize = 0;//接收端 标记 总数据长度

    //service相关
    ServiceConnection conn;
    QRXmitService myService = null;
    QRXmitService.QrAIDLServiceBinder myBinder = null;


//=========================================================================================
//=====================================识别结果==========================================
//=========================================================================================

    /**
     * zbar识别
     * <p>
     * 发送端：
     * 发送数据后，接收端使用该方法处理另一个app的反馈结果，并根据反馈结果，重新发送缺失数据，等待再次反馈。直到反馈结果为识别成功
     * <p>
     * 接收端：
     * 发送端固定时间间隔发送的数据，将数据拼接并处理缺失的数据，并将缺失数据反馈给发送端。
     */

    //QRCodeView.Delegate
    @Override
    public void onScanQRCodeSuccess(String resultStr) {
        /**
         *  （一）数据过滤,包括 重复结果，接收端识别完成，发送端识别完成。
         */
        //结果相同不处理
        if (TextUtils.isEmpty(resultStr) || resultStr.equals(lastText)) {
            Log.d("SJY", "重复扫描");
            return;
        }
        long startTime = System.currentTimeMillis();
        //接收端，收到结束标记，处理接收端的数据
        if (resultStr.contains(sendOver_Contnet)) {
            handOver(true);
            Log.d("SJY", sendOver_Contnet);
            return;
        }
        //发送端，收到结束标记，处理缺失数据/
        if (resultStr.contains("rcvSuccess")) {//接收端 结束标记
            handOver(false);
            return;
        }
        /**
         *（二）解析传输内容，文件的内容，将数据保存在map中
         */
        //首标记
        String flagStr = resultStr.substring(0, 13);
        //尾标记
        String endFlag = resultStr.substring((resultStr.length() - 4), resultStr.length());
        //内容
        final String result = resultStr.substring(13, (resultStr.length() - 4));
        if (flagStr.contains("snd")) {//发送端发送的数据
            //接收端
            RecvTerminalScan(startTime, flagStr, endFlag, result);
        } else if (flagStr.contains("rcv")) {
            //发送端
            SndTerminalScan(flagStr, endFlag, result);
        } else {
            //未识别的错误数据;
        }
    }

    //QRCodeView.Delegate
    @Override
    public void onScanQRCodeOpenCameraError() {
        Log.e("SJY", "QRCodeView.Delegate--ScanQRCodeOpenCameraError()");
    }

    /**
     * 接收端数据处理
     * 相当于另一个手机的app,不会处理myService
     */
    private void RecvTerminalScan(final long startTime, String startTags, String endTags, final String recvStr) {
        String[] flagArray = startTags.split("d");
        //继续排除乱码
        if ((flagArray.length != 2) || (!endTags.equals(endTag))) {
            return;
        }
        //处理标记
        String lenStr = startTags.substring(3, 8);
        final String posStr = startTags.substring(8, 13);
        receveSize = Integer.parseInt(lenStr);//总长度
        final int pos = Integer.parseInt(posStr);//位置
        //扔到handler的异步中处理
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lastText = recvStr;
                //震动
                vibrate();
                receveMap.put(pos, recvStr);//map暂时保存数据

                //QRXmitService的aidl在发送端，不会再接收端处理
            }
        });
    }

    /**
     * 发送端数据处理
     */
    private void SndTerminalScan(String headTags, String endTags, String recvStr) {
        //返回的格式：rcv00000000+1/2/3
//        String[] flagArray = flagStr.split("v");
//        //继续排除乱码
//        if (!(flagArray.length == 2)) {
//            Log.d("SJY", "数据没有长度标记");
//            return;
//        }
//        //内容
//        final String result = resultStr.substring(11);
//        String[] list = result.split("/");
//        sendDatas2 = new ArrayList<>();
//        hasBack = true;
//        for (int i = 0; i < list.length; i++) {
//            String lost = sendDatas.get(Integer.parseInt(list[i]));
//            Bitmap lostBitmap = sendImgs.get(Integer.parseInt(list[i]));
//            Log.d("SJY", "缺失位置=" + list[i]);
//            sendDatas2.add(lost);
//            sendImgs2.add(lostBitmap);
//        }
//        //发送缺失数据
//        sendLostData();
    }

//=========================================================================================
//=====================================控制流程==========================================
//=========================================================================================

    /**
     * 发送二维码
     */
    private void startSend() {
        Log.d("SJY", "startShow");
        //发送端时间统计
        sendCounts = 0;
        handler.postDelayed(sendtask, 1500);
    }

    /**
     * 发送缺失的数据（二次发送）
     */
    private void sendLostData() {
        sendCounts = 0;
        handler.postDelayed(sendLostTask, 3000);
    }

    /**
     * 根据发送端最后一张二维码，设置接收端处理结果
     *
     * @param isReceive true 接收端
     */
    private void handOver(boolean isReceive) {
        //统计结果
        sendBuffer = new StringBuffer();
        if (isReceive) {
            handler.removeCallbacks(handOverTask);
            handler.post(handOverTask);
        } else {
            //关闭bitmap
            img_result.setImageBitmap(null);
            img_result.setBackground(ContextCompat.getDrawable(this, R.mipmap.ic_launcher));
        }
    }

    //--------------------------------------------------------------------------
    //初次发送
    Runnable sendtask = new Runnable() {
        @Override
        public void run() {
            if (sendCounts < sendImgs.size()) {
                img_result.setImageBitmap(sendImgs.get(sendCounts));
                sendCounts++;
                handler.postDelayed(this, PSOTDELAY_TIME);
            } else {
                showBitmap(sendOver_Contnet + selectPath);
            }
        }
    };

    //发送缺失数据
    Runnable sendLostTask = new Runnable() {
        @Override
        public void run() {
//            if (sendTimes < sendImgs2.size()) {
//                showBitmap(sendImgs2.get(sendTimes));
//                sendTimes++;
//                handler.postDelayed(this, PSOTDELAY_TIME2);
//            } else {
//                showBitmap("识别完了识别完了识别完了识别完了识别完了");
//            }
        }
    };

    /**
     * 根据发送端最后一张二维码，设置接收端处理结果
     */
    Runnable handOverTask = new Runnable() {
        @Override
        public void run() {
            //计算缺失的部分
            backFlagList = new ArrayList<>();
            for (int i = 0; i < receveSize; i++) {
                if (receveMap.get(i) == null || TextUtils.isEmpty(receveMap.get(i))) {
                    Log.d("SJY", "缺失=" + i);
                    backFlagList.add(i);
                }
            }
            if (backFlagList.size() > 0) {//有缺失数据
                //拼接数据,告诉发送端发送缺失数据
                Log.d("SJY", "接收端--数据缺失:");
                //
                int count = 0;
                for (int i = 0; i < backFlagList.size(); i++) {
                    sendBuffer.append(backFlagList.get(i) + "").append("/");
                    count++;
                }
                sendBuffer.deleteCharAt(sendBuffer.toString().length() - 1);
                //拼接数据
                String backStr = null;
                try {
                    backStr = "rcv" + ConvertUtils.int2String(receveSize) + ConvertUtils.int2String(count) + sendBuffer.toString();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                //数据返回通知
                showBitmap(backStr);

            } else {//没有缺失数据
                Log.d("SJY", "接收端--数据接收完成");
                showBitmap("rcvSuccess");
                //保存图片
                saveFile();
            }
        }
    };
//--------------------------------------------------------------------------


    /**
     * 初始化发送端数据
     */
    private void initSendParams() {
        sendCounts = 0;
        sendDatas = new ArrayList<>();
        sendDatas2 = new ArrayList<>();
        sendImgs = new ArrayList<>();
        sendImgs2 = new ArrayList<>();
        sendBuffer = new StringBuffer();
        //
        img_result.setImageBitmap(null);
        img_result.setBackground(ContextCompat.getDrawable(this, R.mipmap.ic_launcher));
    }

    /**
     * 初始化 接收端数据
     */
    private void initRecvParams() {

    }


    //===============================================================================
    //=====================================复写==========================================
    //===============================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act_main);
        initView();
        initService();
    }


    /**
     * 初始化控件
     */
    private void initView() {

        //zbar
        mZBarView = (ZBarContinueView) findViewById(R.id.zbarview);
        mZBarView.setDelegate(this);
        //
        img_result = (ImageView) findViewById(R.id.barcodePreview);

        //设置宽高
        size = 400;
        handler = new Handler();
        //获取缓存的时间间隔
        PSOTDELAY_TIME = SPUtil.getInt(Constants.TIME_INTERVAL, 150);
        PSOTDELAY_TIME2 = PSOTDELAY_TIME;
        initSendParams();
        initRecvParams();
        //开启扫描
        startPreview();
    }

    /**
     * 播放震动
     */
    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(100);
    }


    /**
     * 开始识别（其实布局绑定就已经识别，此处设置识别样式）
     */
    private void startPreview() {
        //前置摄像头(不加显示后置)
        mZBarView.startCamera(); // 打开后置摄像头开始预览，但是并未开始识别
        mZBarView.setType(BarcodeType.ONLY_QR_CODE, null); // 只识别 QR_CODE
        mZBarView.getScanBoxView().setOnlyDecodeScanBoxArea(false); // 仅识别扫描框中的码
//        mZBarView.startCamera(cameraId); // 打开前置摄像头开始预览，但是并未开始识别
        mZBarView.startSpot(); // 显示扫描框，并且延迟0.1秒后开始识别
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mZBarView != null) {
            mZBarView.startCamera(); // 打开后置摄像头开始预览，但是并未开始识别
            mZBarView.getScanBoxView().setOnlyDecodeScanBoxArea(false); // 仅识别扫描框中的码
            mZBarView.setType(BarcodeType.ONLY_QR_CODE, null); // 只识别 QR_CODE
//            mZBarView.startCamera(cameraId); // 打开前置摄像头开始预览，但是并未开始识别
            mZBarView.startSpot(); // 显示扫描框，并且延迟0.1秒后开始识别
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mZBarView != null) {
            //前置摄像头(不加显示后置)
            mZBarView.startCamera(); // 打开后置摄像头开始预览，但是并未开始识别
            mZBarView.setType(BarcodeType.ONLY_QR_CODE, null); // 只识别 QR_CODE
            mZBarView.getScanBoxView().setOnlyDecodeScanBoxArea(false); // 仅识别扫描框中的码
//            mZBarView.startCamera(cameraId); // 打开前置摄像头开始预览，但是并未开始识别
            mZBarView.startSpot(); // 显示扫描框，并且延迟0.1秒后开始识别
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mZBarView != null) {
            mZBarView.stopCamera(); // 关闭摄像头预览，并且隐藏扫描框
        }
    }

    @Override
    protected void onStop() {
        mZBarView.stopCamera(); // 关闭摄像头预览，并且隐藏扫描框
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mZBarView.onDestroy(); // 销毁二维码扫描控件
        handler.removeCallbacks(sendtask);
        if (conn != null) {
            unbindService(conn);
        }
        super.onDestroy();

    }
    //=====================================act与service相关==========================================

    /**
     * service连接
     */
    private void initService() {
        conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                myBinder = (QRXmitService.QrAIDLServiceBinder) service;
                myService = myBinder.geSerVice();
                //绑定监听
                myService.setListener(myListener);
                //act通知service,可以发送数据传输了
                myService.startServiceTrans();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };
        //act绑定service
        Intent intent = new Intent(MainAct.this, QRXmitService.class);
        bindService(intent, conn, BIND_AUTO_CREATE);//开启服务
    }

    /**
     * service的回调
     */
    private OnServiceAndActListener myListener = new OnServiceAndActListener() {
        @Override
        public void onQrsend(String path, List<String> newData, List<Bitmap> maps) {
            //清空发送端数据，保证本次数据不受上一次影响
            initSendParams();
            //赋值
            selectPath = path;
            sendDatas = newData;
            sendImgs = maps;
            //
            if (sendDatas != null && sendDatas.size() > 0 &&
                    sendImgs != null && sendImgs.size() > 0 && (!TextUtils.isEmpty(selectPath))) {
                //发送数据
                startSend();
            } else {
                myService.isTrans(false, "myListener获取到空数据，无法发送");
            }
        }
    };


    //=====================================private处理==========================================

    /**
     * 创建并显示
     *
     * @param content 不为空
     * @return
     */
    private void showBitmap(final String content) {
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                return CodeUtils.createByMultiFormatWriter(content, size);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    img_result.setImageBitmap(bitmap);

                } else {
                    Log.e("SJY", "生成二维码失败");
                }
            }
        }.execute();
    }

    /**
     * 数据接收完成，转换成文件
     */
    private void saveFile() {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                //拼接数据
                receiveDatas = new ArrayList<>();
                for (int i = 0; i < receveSize; i++) {
                    String str = receveMap.get(i);
                    Log.d("SJY", i + "---数据长度=" + str.length());
                    receiveDatas.add(str);
                }
                //
                String data = new String();
                for (int i = 0; i < receiveDatas.size(); i++) {
                    data += receiveDatas.get(i);
                }
                Log.e("SJY", "》》》》》》》》》》接收端总长度《《《《《《《《《《《《=" + data.length());

                return IOUtils.base64ToFile(data, "zip");
            }

            @Override
            protected void onPostExecute(String strPath) {
                if (TextUtils.isEmpty(strPath)) {
                    Log.e("SJY", "异常，无图片路径");
                } else {
                    Log.d("SJY", "接收图片已保存，路径：" + strPath);
                }

            }
        }.execute();
    }


}

