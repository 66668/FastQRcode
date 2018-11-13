package com.ruijia.qrcode;

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
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.ruijia.qrcode.service.QRXmitService;
import com.ruijia.qrcode.utils.CodeUtils;
import com.ruijia.qrcode.utils.ConvertUtils;
import com.ruijia.qrcode.utils.IOUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lib.ruijia.zbar.ZBarContinueView;
import lib.ruijia.zbar.qrcodecore.BarcodeType;
import lib.ruijia.zbar.qrodecontinue.ContinueQRCodeView;

/**
 * 备份
 */
public class aaaAct extends BaseAct implements ContinueQRCodeView.Delegate {
    //===================常量=====================
    private static final long PSOTDELAY_TIME = 200;//发送间隔时间
    private static final long PSOTDELAY_TIME2 = 300;//缺失发送间隔时间
    //===================变量=====================

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
    List<String> sendDatas = new ArrayList<>();//已处理的发送端数据 String样式
    List<Bitmap> sendImgs = new ArrayList<>();//已处理的发送端数据 Bitmap样式
    List<String> sendDatas2 = new ArrayList<>();//缺失的数据；String样式
    List<Bitmap> sendImgs2 = new ArrayList<>();//缺失的数据； Bitmap样式
    private List<Integer> backFlagList = new ArrayList<>();//发送端 返回缺失的标记,
    private String selectPath;
    StringBuffer sendBuffer = new StringBuffer();  //统计结果
    private int sendTimes = 0;//发送次数，一次发送，到下一次再发送，为一次，
    private long send_startTime;
    private long send_overTime;
    private boolean hasBack = false;//是否有缺失数据；

    //===================接收端操作=====================
    private Map<Integer, String> receveMap = new HashMap<>();//接收的数据暂时保存到map中，最终保存到receiveDatas
    private List<String> receiveDatas = new ArrayList<>();

    private long receiveTime;
    private int receveSize = 0;//接收端 标记 总数据长度

    //数据
    private List<String> orgDatas = new ArrayList<>();//原始数据
    private StringBuffer lostBuffer = new StringBuffer();//缺失的数据,用/字符分开

    //service相关
    ServiceConnection conn;
    QRXmitService.QrAIDLServiceBinder myBinder = null;


//=====================================识别流程控制==========================================

    /**
     * zbar极限速度
     */

    //QRCodeView.Delegate
    @Override
    public void onScanQRCodeSuccess(String resultStr) {
//        Log.d("SJY", "扫描结果为：" + resultStr);
//        mZBarView.startSpot(); // 延迟x ms后开始识别

        //结果相同不处理
        if (TextUtils.isEmpty(resultStr) || resultStr.equals(lastText)) {
            Log.d("SJY", "重复扫描");
            return;
        }

        //接收端结束标记，进行数据统计
        if (resultStr.contains("识别完了")) {
            handOver(true);
            Log.d("SJY", "识别完了");
            return;
        } else if (resultStr.contains("rcvSuccess")) {//接收端 结束标记
            handOver(false);
            return;
        }
        //解析数据
        //（1）解析标记
        String flagStr = resultStr.substring(0, 11);
        //接收端
        if (flagStr.contains("snd")) {//发送端发送的数据

            String[] flagArray = flagStr.split("d");
            //继续排除乱码
            if (!(flagArray.length == 2)) {
                Log.d("SJY", "数据没有长度标记");
                return;
            }
            //处理标记
            String lenStr = flagStr.substring(3, 7);
            final String posStr = flagStr.substring(7, 11);
            receveSize = Integer.parseInt(lenStr);//标记 数据总长度
            final int pos = Integer.parseInt(posStr);
            Log.d("SJY", "保存标记=" + pos);
            //内容
            final String result = resultStr.substring(11);

            //扔到handler的异步中处理
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d("SJY", "识别速度=" + (System.currentTimeMillis() - receiveTime) + "ms");
                    receiveTime = System.currentTimeMillis();
                    lastText = result;
                    //震动
                    vibrate();
                    receveMap.put(pos, result);//map暂时保存数据
                }
            });

            //发送端
        } else if (flagStr.contains("rcv")) {//接收端返回的数据
            //返回的格式：rcv00000000+1/2/3
            String[] flagArray = flagStr.split("v");
            //继续排除乱码
            if (!(flagArray.length == 2)) {
                Log.d("SJY", "数据没有长度标记");
                return;
            }
            //内容
            final String result = resultStr.substring(11);
            String[] list = result.split("/");
            sendDatas2 = new ArrayList<>();
            hasBack = true;
            for (int i = 0; i < list.length; i++) {
                String lost = sendDatas.get(Integer.parseInt(list[i]));
                Bitmap lostBitmap = sendImgs.get(Integer.parseInt(list[i]));
                Log.d("SJY", "缺失位置=" + list[i]);
                sendDatas2.add(lost);
                sendImgs2.add(lostBitmap);
            }
            //发送缺失数据
            sendLostData();

        } else {
            Log.d("SJY", "数据没有任何标记");
            return;
        }

    }

    //QRCodeView.Delegate
    @Override
    public void onScanQRCodeOpenCameraError() {
        Log.e("SJY", "QRCodeView.Delegate--ScanQRCodeOpenCameraError()");
    }

    /**
     * 发送二维码
     */
    private void startShow() {
        //发送端时间统计
        receiveTime = System.currentTimeMillis();
        sendTimes = 0;
        send_startTime = System.currentTimeMillis();
        handler.postDelayed(sendtask, 3000);
    }

    /**
     * 发送缺失的数据（二次发送）
     */
    private void sendLostData() {
        sendTimes = 0;
        handler.postDelayed(sendLostTask, 3000);
    }

    //初次发送
    Runnable sendtask = new Runnable() {
        @Override
        public void run() {
            if (sendTimes < sendImgs.size()) {
                showBitmap(sendImgs.get(sendTimes));
                sendTimes++;
                handler.postDelayed(this, PSOTDELAY_TIME);
            } else {
                showBitmap("识别完了识别完了识别完了识别完了识别完了");
                send_overTime = System.currentTimeMillis();
                hasBack = false;
                if (!hasBack) {
                    Log.d("SJY", "发送端耗时：" + (send_overTime - send_startTime - 3000) + "ms--缺失返回标记--无");
                }
            }
        }
    };

    //发送缺失数据
    Runnable sendLostTask = new Runnable() {
        @Override
        public void run() {
            if (sendTimes < sendImgs2.size()) {
                showBitmap(sendImgs2.get(sendTimes));
                sendTimes++;
                handler.postDelayed(this, PSOTDELAY_TIME2);
            } else {
                showBitmap("识别完了识别完了识别完了识别完了识别完了");
                send_overTime = System.currentTimeMillis();
            }
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

    /**
     * 根据发送端最后一张二维码，设置接收端处理结果
     *
     * @param isReceive true 接收端
     */
    private void handOver(boolean isReceive) {
        //统计结果
        sendBuffer = new StringBuffer();
        if (isReceive) {
            lostBuffer = new StringBuffer();
            handler.removeCallbacks(handOverTask);
            handler.post(handOverTask);
        } else {
            //关闭bitmap
            img_result.setImageBitmap(null);
            img_result.setBackground(ContextCompat.getDrawable(this, R.mipmap.ic_launcher));

        }
    }


    /**
     * 重新初始化流程控制参数
     */
    private void initParams() {
        receiveTime = 0;
        receveSize = 0;
        sendTimes = 0;
        receiveDatas = new ArrayList<>();
        orgDatas = new ArrayList<>();
        sendDatas = new ArrayList<>();
        sendImgs = new ArrayList<>();
        sendImgs2 = new ArrayList<>();
        backFlagList = new ArrayList<>();
        receveMap = new HashMap<>();
        sendBuffer = new StringBuffer();
        //
        img_result.setImageBitmap(null);
        img_result.setBackground(ContextCompat.getDrawable(this, R.mipmap.ic_launcher));
    }


    //=====================================复写==========================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act_main);
        initView();
        initService();
        initIntent();
    }

    /**
     * app可能由service调起，service会传值
     */
    private void initIntent() {
        if (getIntent() == null) {
            return;
        } else {
            Intent intent = getIntent();
            selectPath = null;
            selectPath = intent.getStringExtra("path");
            if (TextUtils.isEmpty(selectPath)) {
                Log.d("SJY", "act--path=null");
            } else {
                Log.d("SJY", "act--path=" + selectPath);
            }
        }
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
        img_result.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //点击图片重新唤醒
                if (mZBarView != null) {
                    mZBarView.startSpot(); // 显示扫描框，并且延迟0.1秒后开始识别
                }
                initParams();
            }
        });

        //设置宽高
        size = 400;
        handler = new Handler();
    }

    /**
     * 播放震动
     */
    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(100);
    }

    /**
     * 初始化service连接
     */
    private void initService() {
        conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                myBinder = (QRXmitService.QrAIDLServiceBinder) service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };

        Intent intent = new Intent(aaaAct.this, QRXmitService.class);
        bindService(intent, conn, BIND_AUTO_CREATE);//开启服务
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


    //=====================================private处理==========================================

    /**
     * 将绝对路径转成文件
     *
     * @param absolutePath
     */
    private void getRealFile(String absolutePath) {
        File file = new File(absolutePath);
        if (file == null || !file.exists()) {
        } else {
            initData(file);
        }
    }

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
                    Log.e("SJY", "生成英文二维码失败");
                }
            }
        }.execute();
    }

    /**
     * 创建并显示
     *
     * @return
     */
    private void showBitmap(final Bitmap bitmap) {
        if (bitmap != null) {
            img_result.setImageBitmap(bitmap);
        }
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


    /**
     * 创建数据,
     * <p>
     * 将txt转成List<String>
     *
     * @param file
     */
    @SuppressLint("StaticFieldLeak")
    private void initData(final File file) {
        //
        if (!file.getName().contains("zip")) {
            return;
        }
        //
        orgDatas = new ArrayList<>();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    //File转String
                    String data = IOUtils.fileToBase64(file);
                    Log.e("SJY", "<<<<<<<<<<<File转String总长度>>>>>>>>>>>>>>=" + data.length());
                    //String切割成list
                    orgDatas = IOUtils.stringToArray(data);
                    if (orgDatas == null) {
                        String s = "原始数据长度超过指定长度！";
                        return s;
                    }
                    //添加标记，snd00001111,共11位版标记
                    // 前四位是size标记，后四位是第几个标记
                    int size = orgDatas.size();
                    String strSize = ConvertUtils.int2String(size);//不会处理大于10000的size
                    for (int i = 0; i < size; i++) {
                        String pos = ConvertUtils.int2String(i);
                        //拼接数据-->格式：snd(发送标记)+0022(数据长度)+0001(第几个，从0开始)+数据段
                        sendDatas.add("snd" + strSize + pos + orgDatas.get(i)); //eg 00120001xxstr
                    }
                    //sendDatas 转bitmap
                    for (int i = 0; i < size; i++) {
                        Log.d("SJY", "生成bitmap=" + i);
                        Bitmap bitmap = CodeUtils.createByMultiFormatWriter(sendDatas.get(i), 400);
                        sendImgs.add(bitmap);
                    }
                    return null;
                } catch (Exception e) {
                    Log.d("SJY", "catch异常=" + e.toString());
                    e.printStackTrace();
                    String s = "无法生成原始数据，无法转成二维码";
                    return s;
                }
            }

            @Override
            protected void onPostExecute(String s) {
                if (s != null) {
                    Log.e("SJY", s);
                    return;
                }
                Log.d("SJY", "---orgDatas.size()=" + orgDatas.size());
            }
        }.execute();
    }

}

