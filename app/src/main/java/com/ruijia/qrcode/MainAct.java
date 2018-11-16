package com.ruijia.qrcode;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Vibrator;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import com.ruijia.qrcode.listener.OnServiceAndActListener;
import com.ruijia.qrcode.service.QRXmitService;
import com.ruijia.qrcode.utils.CodeUtils;
import com.ruijia.qrcode.utils.ConvertUtils;
import com.ruijia.qrcode.utils.IOUtils;
import com.ruijia.qrcode.utils.SPUtil;

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
    //控件
    private ZBarContinueView mZBarView; //zbar
    private ImageView img_result;
    //生成二维码使用
    private int size = 400;//200就够了，再大也没用
    private Handler handler;
    //==============通用标记==============
    private long PSOTDELAY_TIME_SEND = 150;//默认 发送间隔时间
    private long PSOTDELAY_TIME_BACK = 150;//默认 缺失发送间隔时间
    private String sendOver_Contnet = "QrcodeContentSendOver";//发送端 所有数据一次发送完成，发送结束标记
    private String receiveOver_Content = "QrCodeContentReceiveOver";//接收端 完全收到数据，发送结束标记
    private String SUCCESS = "Success";//识别成功结束标记，和sendOver_Contnet和receiveOver_Content拼接使用
    private String endTag = "RJQR";
    private String lastText;//
    private String lastRecvOver = "";//接收端使用的标记
    private String lastSendOver = "";//发送端使用的标记
    private boolean isFirst = true;//是否走onCreate生命周期

    //===================发送端操作=====================
    private List<String> sendDatas = new ArrayList<>();//发送端 数据
    private List<Bitmap> sendImgs = new ArrayList<>();//发送端 数据
    private List<Integer> sendBackList = new ArrayList<>();//发送端 返回缺失数据
    private List<Bitmap> sendImgsMore = new ArrayList<>();//缺失的数据； Bitmap样式
    private String sendFlePath;//发送端 文件路径
    private int sendSize = 0;//发送端 文件路径
    private int sendCounts = 0;//发送次数统计，handler发送使用

    //===================接收端操作=====================
    private Map<Integer, String> receveContentMap = new HashMap<>();//接收的数据暂时保存到map中，最终保存到receiveDatas
    private List<String> receiveContentDatas = new ArrayList<>();//文件内容存储
    private List<Integer> feedBackFlagList = new ArrayList<>();//缺失标记list,用于拼接数据
    private StringBuffer feedBackBuffer = new StringBuffer();  //统计结果
    private List<String> feedBackDatas = new ArrayList<>();//接收端处理结果，反馈list
    private List<Bitmap> feedBackImgs = new ArrayList<>();//接收端处理结果，反馈list
    private String recvFlePath;//接收端 文件路径
    private int receveSize = 0;//接收端 标记 总数据长度
    private int recvCounts = 0;//发送次数统计，handler发送使用

    //service相关
    private ServiceConnection conn;
    private QRXmitService myService = null;
    private QRXmitService.QrAIDLServiceBinder myBinder = null;


//=================================================================================================================
//=====================================识别结果,细分 接收端处理和发送端处理============================================
//=================================================================================================================

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
        Log.d("AAA", resultStr);
        /**
         *  （一）数据过滤,包括 重复结果，接收端识别完成，发送端识别完成。
         */
        //结果相同不处理
        if (TextUtils.isEmpty(resultStr) || resultStr.length() < 14 || resultStr.equals(lastText)) {
            Log.d("SJY", "重复扫描");
            return;
        }
        long startTime = System.currentTimeMillis();

        //接收端，收到结束标记，处理接收端的数据
        if (resultStr.contains(sendOver_Contnet)) {
            RecvTerminalOver(resultStr);
            return;
        }

        //发送端，收到结束标记，处理缺失数据/
        if (resultStr.contains(receiveOver_Content)) {//接收端 结束标记
            senTerminalOver(resultStr);
            return;
        }
        /**
         *（二）解析传输内容，文件的内容，将数据保存在map中
         */
        //首标记
        String flagStr = resultStr.substring(0, 10);
        //尾标记
        String endFlag = resultStr.substring((resultStr.length() - 4), resultStr.length());
        //内容
        final String result = resultStr.substring(10, (resultStr.length() - 4));
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

//========================================================================================
//=====================================接收端处理==========================================
//========================================================================================

    /**
     * 接收端数据处理（实时扫描结果）
     * 相当于另一个手机的app,不会处理myService
     */
    private void RecvTerminalScan(final long startTime, String startTags, String endTags, final String recvStr) {
        String[] flagArray = startTags.split("d");
        //继续排除乱码
        if ((flagArray.length != 2) || (!endTags.equals(endTag))) {
            return;
        }
        //处理片段位置标记
        String posStr = startTags.substring(3, 10);
        final int pos = Integer.parseInt(posStr);//位置 "0001234"-->1234
        //扔到handler的异步中处理
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lastText = recvStr;
                vibrate();  //震动
                receveContentMap.put(pos, recvStr);//map暂时保存数据
                //QRXmitService的aidl在发送端，不会在接收端处理
            }
        });
    }

    /**
     * 接收端 识别结束处理（实时扫描结果）
     * 数据拼接类型：QrcodeContentSendOver+文件路径+7位的文件大小
     */
    private void RecvTerminalOver(String resultStr) {
        //处理标记
        if (resultStr.equals(lastRecvOver)) {
            //再一次过滤，保证拿到结束标记 只处理一次
            return;
        }
        //注意该标记需要清除，否则容易出问题，清除时间在发送二维码处
        lastRecvOver = resultStr;
        String pathAndPos = resultStr.substring(sendOver_Contnet.length(), resultStr.length());
        String positionStr = pathAndPos.substring((pathAndPos.length() - 7), pathAndPos.length());
        //拿到发送端的数据大小
        receveSize = Integer.parseInt(positionStr);
        //拿到发送端文件类型
        recvFlePath = pathAndPos.substring(0, (pathAndPos.length() - 7));
        //处理是否有缺失文件。
        handler.removeCallbacks(recvTerminalOverTask);
        handler.post(recvTerminalOverTask);
    }

    /**
     * 接收端 处理识别结束标记/异步
     */
    Runnable recvTerminalOverTask = new Runnable() {
        @Override
        public void run() {
            //计算缺失的部分
            feedBackFlagList = new ArrayList<>();
            feedBackDatas = new ArrayList<>();
            feedBackBuffer = new StringBuffer();
            feedBackImgs = new ArrayList<>();

            for (int i = 0; i < receveSize; i++) {
                if (receveContentMap.get(i) == null || TextUtils.isEmpty(receveContentMap.get(i))) {
                    Log.d("SJY", "缺失=" + i);
                    feedBackFlagList.add(i);
                }
            }
            if (feedBackFlagList.size() > 0) {//有缺失数据
                //拼接数据,告诉发送端发送缺失数据
                Log.d("SJY", "接收端--数据缺失:");
                //
                List<String> orgList = new ArrayList<>();
                for (int i = 0; i < feedBackFlagList.size(); i++) {
                    //拼接
                    feedBackBuffer.append(feedBackFlagList.get(i) + "").append("/");
                    if (feedBackBuffer.length() > 2000) {
                        //添加到list中
                        feedBackBuffer.deleteCharAt(feedBackBuffer.toString().length() - 1);
                        orgList.add(feedBackBuffer.toString());
                        //重新赋值
                        feedBackBuffer = new StringBuffer();
                    }
                }
                feedBackBuffer.deleteCharAt(feedBackBuffer.toString().length() - 1);
                orgList.add(feedBackBuffer.toString());

                //拼接数据 rcv1234567+内容+RJQR
                int backsize = orgList.size();
                String backSizeStr = null;
                try {
                    backSizeStr = ConvertUtils.int2String(backsize);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                for (int i = 0; i < backsize; i++) {
                    feedBackDatas.add("rcv" + backSizeStr + orgList.get(i) + endTag);
                }

                //生成bitmap
                for (int i = 0; i < feedBackDatas.size(); i++) {
                    Bitmap bitmap = CodeUtils.createByMultiFormatWriter(feedBackDatas.get(i), 400);
                    feedBackImgs.add(bitmap);
                }
                //
                recvTerminalBackSend();

            } else {//没有缺失数据
                //保存文件
                saveFile();
            }
        }
    };

    /**
     * 接收端 发送反馈二维码数据
     */
    private void recvTerminalBackSend() {
        //需要清除 lastRecvOver标记，否则，二次+接收端收不到结束处理标记
        lastRecvOver = "";
        recvCounts = 0;
        handler.post(feedBackTask);
    }

    /**
     * 接收端 发送二维码
     */
    Runnable feedBackTask = new Runnable() {
        @Override
        public void run() {
            //没有缺失，直接结束
            if (feedBackImgs.size() <= 0) {
                //发送结束标记，结束标记为：QrCodeContentReceiveOver
                showBitmap(receiveOver_Content + SUCCESS);
                return;
            }
            //有缺失发送
            if (recvCounts < feedBackImgs.size()) {
                img_result.setImageBitmap(feedBackImgs.get(recvCounts));
                recvCounts++;
                handler.postDelayed(this, PSOTDELAY_TIME_BACK);
            } else {
                //发送结束标记，结束标记为：QrCodeContentReceiveOver
                showBitmap(receiveOver_Content);
            }
        }
    };

    /**
     * 接收端 保存文件
     */
    private void saveFile() {

        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                //拼接数据
                receiveContentDatas = new ArrayList<>();
                String data = new String();
                //提取map数据
                for (int i = 0; i < receveSize; i++) {
                    String str = receveContentMap.get(i);
                    data += receveContentMap.get(i);
                    receiveContentDatas.add(str);
                }
                return IOUtils.StringToFile(data, recvFlePath);
            }

            @Override
            protected void onPostExecute(String strPath) {
                //发送结束二维码
                feedBackImgs = new ArrayList<>();
                recvTerminalBackSend();

                //aidl 与测试b通讯
                try {
                    fileBinder.QRRecv(strPath);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }

        }.execute();
    }


//=======================================================================================
//=====================================发送端处理==========================================
//=======================================================================================

    /**
     * 发送端 接收数据处理（实时扫描结果）
     * 数据格式：rcv1234567+内容+RJQR
     */
    private void SndTerminalScan(String headTags, final String endTags, final String recvStr) {

        String[] flagArray = headTags.split("v");
        //继续排除乱码
        if ((flagArray.length != 2) || (!endTags.equals(endTag))) {
            return;
        }
        //处理片段位置标记
        String posStr = headTags.substring(3, 10);
        final int pos = Integer.parseInt(posStr);//位置 "0001234"-->1234
        //扔到handler的异步中处理
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lastText = endTags;
                vibrate();  //震动
                //数据转成list,list保存位置信息
                String[] strDatas = recvStr.split("/");
                //数据保存在list中
                for (int i = 0; i < strDatas.length; i++) {
                    sendBackList.add(Integer.parseInt(strDatas[i]));
                }
            }
        });
    }

    /**
     * 发送端 结束标记处理（实时扫描结果）
     * 数据格式：QrCodeContentReceiveOver或QrCodeContentReceiveOverSuccess
     *
     * @param resultStr
     */
    private void senTerminalOver(String resultStr) {

        //处理标记
        if (resultStr.equals(lastSendOver)) {
            //再一次过滤，保证拿到结束标记 只处理一次
            return;
        }

        //注意该标记需要清除，否则容易出问题，清除时间在发送二维码处
        lastSendOver = resultStr;

        //说明格式是QrCodeContentReceiveOverSuccess,文件传输完成，回调aidl。
        if (resultStr.length() > receiveOver_Content.length()) {
            //TODO 统计耗时 清除所有发送端参数


        } else {//格式是QrCodeContentReceiveOver
            //查找缺失数据并拼接
            new AsyncTask<Void, Void, List<Bitmap>>() {
                @Override
                protected List<Bitmap> doInBackground(Void... voids) {
                    List<Bitmap> maps = new ArrayList<>();
                    //利用位置信息，取bitmap
                    for (int i = 0; i < sendBackList.size(); i++) {
                        for (int j = 0; j < sendImgs.size(); j++) {
                            if (sendBackList.get(i) == j) {
                                maps.add(sendImgs.get(j));
                            }
                        }
                    }
                    return maps;
                }

                @Override
                protected void onPostExecute(List<Bitmap> bitmaps) {
                    super.onPostExecute(bitmaps);
                    sendImgsMore = bitmaps;
                    //发送二维码
                    startSendMore();
                }
            }.execute();
        }
    }

    /**
     * 发送二维码
     */
    private void startSend() {
        Log.d("SJY", "startShow");
        sendCounts = 0;
        if (isFirst) {
            handler.postDelayed(firstSendTask, 1500);
            isFirst = false;
        } else {
            handler.post(firstSendTask);
        }
    }

    /**
     * 发送二维码（二次+发送）
     */
    private void startSendMore() {
        sendCounts = 0;
        lastSendOver = "";//清除，否则该方法不再出发。
        handler.removeCallbacks(moreSendTask);
        handler.post(moreSendTask);
    }

    /**
     * 初次发送
     */
    Runnable firstSendTask = new Runnable() {
        @Override
        public void run() {
            if (sendCounts < sendImgs.size()) {
                img_result.setImageBitmap(sendImgs.get(sendCounts));
                sendCounts++;
                handler.postDelayed(this, PSOTDELAY_TIME_SEND);
            } else {
                //发送结束标记，结束标记为：QrcodeContentSendOver+文件路径+文件大小（7位数）
                try {
                    String sizeStr = ConvertUtils.int2String(sendSize);
                    showBitmap(sendOver_Contnet + sendFlePath + sizeStr);
                } catch (Exception e) {
                    //已处理
                    e.printStackTrace();
                }
            }
        }
    };


    //发送端二次+ 发送缺失数据
    Runnable moreSendTask = new Runnable() {
        @Override
        public void run() {

            if (sendImgsMore.size() > 0 && sendCounts < sendImgsMore.size()) {
                img_result.setImageBitmap(sendImgsMore.get(sendCounts));
                sendCounts++;
                handler.postDelayed(this, PSOTDELAY_TIME_SEND);
            } else {
                //发送结束标记，结束标记为：QrcodeContentSendOver+文件路径+文件大小（7位数）
                try {
                    String sizeStr = ConvertUtils.int2String(sendSize);
                    showBitmap(sendOver_Contnet + sendFlePath + sizeStr);
                } catch (Exception e) {
                    //已处理
                    e.printStackTrace();
                }
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
        sendImgsMore = new ArrayList<>();
        sendImgs = new ArrayList<>();
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
        isFirst = true;
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
        PSOTDELAY_TIME_SEND = SPUtil.getInt(Constants.TIME_INTERVAL, Constants.DEFAULT_TIME);
        PSOTDELAY_TIME_BACK = PSOTDELAY_TIME_SEND;
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
        handler.removeCallbacks(recvTerminalOverTask);
        handler.removeCallbacks(feedBackTask);
        handler.removeCallbacks(firstSendTask);
        handler.removeCallbacks(moreSendTask);
        if (conn != null) {
            unbindService(conn);
        }
        super.onDestroy();

    }
    //=====================================发送端 act与service相关==========================================

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
                //act通知service,可以发送数据传输了,
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
            sendFlePath = path;
            sendDatas = newData;
            sendImgs = maps;
            sendSize = sendDatas.size();
            //
            if (sendDatas != null && sendDatas.size() > 0 &&
                    sendImgs != null && sendImgs.size() > 0 && (!TextUtils.isEmpty(sendFlePath)) &&
                    sendSize > 0) {
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


}

