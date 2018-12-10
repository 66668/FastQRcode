package com.ruijia.qrcode;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Vibrator;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
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
import java.util.Timer;
import java.util.TimerTask;

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
    //TODO  需要再优化，暂定
    private boolean isFirst = true;//是否走onCreate生命周期
    private Timer timer;//倒计时类

    //===================发送端连接接收端测试 变量=====================
    private String send_init = "QrcodeSENDCONNECTQrcodeSENDCONNECTQrcodeSENDCONNECTQrcodeSENDCONNECT";//发送端 发送连接信息，通知接收端初始化数据
    private String recv_init = "QrcodeRECVCONNECTQrcodeRECVCONNECTQrcodeRECVCONNECTQrcodeRECVCONNECT";//接收端 发送连接信息，通知发送端发送数据
    private long ininConnectTime;//测试接收端是否可用
    private static final int TIMEOUT = 20;//连接超时
    private int timeoutCount = 0;
    private String recv_lastStr = null;
    private boolean isRecvInit = false;

    //===================发送端操作=====================
    private List<String> sendDatas = new ArrayList<>();//发送端 数据
    private List<Bitmap> sendImgs = new ArrayList<>();//发送端 数据
    private List<Integer> sendBackList = new ArrayList<>();//发送端 返回缺失数据
    //TODO 异常
    private List<Bitmap> sendImgsMore = new ArrayList<>();//缺失的数据； Bitmap样式
    private String sendFlePath;//发送端 文件路径
    private int sendSize = 0;//发送端 文件路径
    private int sendCounts = 0;//发送次数统计，handler发送使用
    private boolean isSending = false;//代码成对出现，用于保护第一次发送，当第一次发送结束，设置为false
    //时间设置
    private long handler_lastTime;//用于计算发送耗时+显示到界面的时长
    private long lastSaveTime;//用于计算发送耗时,


    //===================接收端操作=====================
    //TODO 容易出问题，每次传文件都需要清除数据
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

        //======初始化连接=======

        //接收端：发送端发送初始化信息 接收端初始化使用
        if (resultStr.contains(send_init)) {
            //初始化
            initRecvConnect(resultStr);
            lastText = resultStr;
        }

        //发送端：接收端发送初始化信息，发送端接收后发送数据
        if (resultStr.contains(recv_init)) {
            if (!isSending) {
                //第一次发送数据
                startSend();
                //第一次发送结束后，设置为false
                isSending = true;
            }
        }

        //======数据传输结束=======
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

        //======数据传输中=======
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

            //必要的参数修改 清空初始化连接
            recv_lastStr = null;
            handler.removeCallbacks(initRecvTimeoutTask);//本想boolean判断，但是麻烦，就实时清除吧。
            //
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

    private void clearRecvParams() {
        //
        clearInitConnect();
        //
        receveContentMap = new HashMap<>();//接收的数据暂时保存到map中，最终保存到receiveDatas
        receiveContentDatas = new ArrayList<>();//文件内容存储
        feedBackFlagList = new ArrayList<>();//缺失标记list,用于拼接数据
        feedBackBuffer = new StringBuffer();  //统计结果
        feedBackDatas = new ArrayList<>();//接收端处理结果，反馈list
        feedBackImgs = new ArrayList<>();//接收端处理结果，反馈list
        recvFlePath = null;//接收端 文件路径
        receveSize = 0;//接收端 标记 总数据长度
        recvCounts = 0;//发送次数统计，handler发送使用
    }

    /**
     * 异步倒计时，处理接收端 脏数据倒计时清理，如果不是脏数据了/倒计时结束了
     * 关闭该异步的判断是 接收端接收数据/倒计时结束
     * <p>
     * 问题：
     * 如果接收端数据接收中，突然触发该方法，不会造成数据损坏，不影响数据接收
     */
    Runnable initRecvTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (timeoutCount > TIMEOUT) {
                //该种情况，链路层传输失败。
                //清空脏数据
                img_result.setImageBitmap(null);
                clearRecvParams();
                //结束倒计时
                handler.removeCallbacks(this);
            } else {
                //倒计时
                timeoutCount++;
                handler.postDelayed(this, 950);
            }

        }
    };

    /**
     * 接收端初始化
     * <p>
     * 发送端发送初始化信息，接收端接收后，初始化信息，并返回结果。
     * <p>
     * 需要做倒计时处理，如果接收端数据反馈不到发送端，以后接收端就无法再使用。
     * <p>
     * 所以清除该参数有两处，倒计时超时处，和数据识别处，
     * <p>
     * 倒计时处使用，说明链路层连接失败，
     * <p>
     * 数据识别处，说明链路正常连接（需要修改recv_lastStr+结束倒计时）
     * <p>
     */

    private void initRecvConnect(String result) {
        if (TextUtils.isEmpty(recv_lastStr) || !result.equals(recv_lastStr)) {
            //初始化接收端数据
            clearRecvParams();
            //发送信息，通知发送端，可以发送数据了
            showBitmap(recv_init);
            //该参数需要在适当位置清空，否则出问题。
            recv_lastStr = result;

            //处理可能成为脏数据的情况
            timeoutCount = 0;
            handler.removeCallbacks(initRecvTimeoutTask);
            handler.post(initRecvTimeoutTask);
        }
    }

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
     * <p>
     * 这里需要讨论情况：
     * （1）接收端只拿到该结束标记，则receveContentMap对象没有值，识别流程失败
     * （2）接收端拿到部分（或全部数据）识别数据+结束标记，则符合设计要求
     */
    private void RecvTerminalOver(String resultStr) {
        //处理标记
        if (resultStr.equals(lastRecvOver)) {
            //再一次过滤，保证拿到结束标记 只处理一次
            return;
        }
        //注意该标记需要清除，否则容易出问题，清除时间在：接收端发送二维码处
        lastRecvOver = resultStr;

        //提取结束端信息：路径+数据长度，用于判断接收端数据是否接收全，否则就通知发送端再次发送缺失数据。
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
        //
        recvCounts = 0;

        //开启倒计时
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                //终止倒计时
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //没有缺失，直接结束
                        if (feedBackImgs.size() <= 0) {
                            //发送结束标记，结束标记为：QrCodeContentReceiveOver
                            showBitmap(receiveOver_Content + SUCCESS);
                            timer.cancel();
                            return;
                        }
                        //有缺失发送
                        if (recvCounts < feedBackImgs.size()) {
                            img_result.setImageBitmap(feedBackImgs.get(recvCounts));
                            recvCounts++;
                        } else {
                            //发送结束标记，结束标记为：QrCodeContentReceiveOver
                            showBitmap(receiveOver_Content);
                            timer.cancel();
                        }
                    }
                });
            }
        }, 100, PSOTDELAY_TIME_BACK);
    }

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
     * 连接测试清空
     */
    private void clearInitConnect() {
        ininConnectTime = 0;
        timeoutCount = 0;
        recv_lastStr = null;
    }

    /**
     * 发送端数据清空
     */
    private void clearSendParams() {
        clearInitConnect();
        sendDatas = new ArrayList<>();//发送端 数据
        sendImgs = new ArrayList<>();//发送端 数据
        sendBackList = new ArrayList<>();//发送端 返回缺失数据
        sendImgsMore = new ArrayList<>();//缺失的数据； Bitmap样式
        sendFlePath = null;//发送端 文件路径
        sendSize = 0;//发送端 文件路径
        sendCounts = 0;//发送次数统计，handler发送使用
        isSending = false;//代码成对出现，用于保护发送
        //时间设置
        handler_lastTime = 0;//用于计算发送耗时+显示到界面的时长
        lastSaveTime = 0;//用于计算发送耗时,
    }

    /**
     * 发送端向接收端发送连接测试，连接通了，则发送数据，连接不通，则回调 连接失败
     */
    Runnable initSendConnectTask = new Runnable() {
        @Override
        public void run() {
            if (timeoutCount < TIMEOUT) {
                timeoutCount++;
                handler.postDelayed(this, 950);
            } else {//连接超时
                //清除图片
                img_result.setImageBitmap(null);
                //清除发送端所有数据
                clearSendParams();
                //回调
                myService.isTrans(false, "连接接收端设备失败，连接超时:" + TIMEOUT + "S");
            }
        }
    };

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
     * 发送前的连接初始化，用于测试是否可以使用链路层，同时通知接收端，初始化参数。
     * <p>
     * 如果接收端接收到数据，则反馈给发送端可以发送数据，则触发发送。
     * <p>
     * 倒计时连接，如果连接超时，则连接失败，回调通知失败
     */
    private void initSendConnect() {
        //

        //初始化
        ininConnectTime = System.currentTimeMillis();
        timeoutCount = 0;
        showBitmap(send_init);
        //触发异步
        handler.removeCallbacks(initSendConnectTask);
        handler.post(initSendConnectTask);

    }


    /**
     * 发送端 发送数据
     * <p>
     * 第一次发送
     */
    private void startSend() {
        Log.d("SJY", "发送端发送二维码");
        //保存当前时间节点。
        handler_lastTime = System.currentTimeMillis();
        lastSaveTime = System.currentTimeMillis();
        SPUtil.putLong(Constants.START_SEND_TIME, handler_lastTime);
        sendCounts = 0;
        img_result.setImageBitmap(null);
        //开启倒计时
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                final long time = System.currentTimeMillis() - lastSaveTime;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //回调发送间隔，用于计算识别速度，也用于鉴别 发送间隔是否标准
                        myService.sendAidlQrUnitTime((System.currentTimeMillis() - handler_lastTime), sendImgs.size(), sendCounts, "firstSend--发送间隔=" + time);
                        //
                        if (sendCounts < sendImgs.size()) {
                            img_result.setImageBitmap(sendImgs.get(sendCounts));
                            sendCounts++;

                        } else {
                            //第一次发送结束后，设置为false
                            isSending = false;

                            //发送结束标记，结束标记为：QrcodeContentSendOver+文件路径+文件大小（7位数）
                            try {
                                final String sizeStr = ConvertUtils.int2String(sendSize);

                                //数据发送结束，发送数据配置信息：文件路径+图片尺寸
                                showBitmap(sendOver_Contnet + sendFlePath + sizeStr);
                                //结束倒计时
                                timer.cancel();

                            } catch (Exception e) {
                                //已处理
                                e.printStackTrace();
                            }
                        }
                        handler_lastTime = System.currentTimeMillis();
                    }
                });
                lastSaveTime = System.currentTimeMillis();

            }
        }, 100, PSOTDELAY_TIME_SEND);
    }

    /**
     * 发送端 发送数据
     * <p>
     * 二次+发送
     */
    private void startSendMore() {
        sendCounts = 0;
        lastSendOver = "";//清除，否则该方法不再出发。
        //此处不保存时间节点，因为统计用不到
        handler_lastTime = System.currentTimeMillis();
        //开启倒计时
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (sendImgsMore.size() > 0 && sendCounts < sendImgsMore.size()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            //回调发送间隔，用于计算识别速度，也用于鉴别 发送间隔是否标准
                            myService.sendAidlQrUnitTime((System.currentTimeMillis() - handler_lastTime), sendImgs.size(), sendCounts, "firstSend");
                            //
                            img_result.setImageBitmap(sendImgsMore.get(sendCounts));
                            sendCounts++;
                            handler_lastTime = System.currentTimeMillis();
                        }
                    });

                } else {
                    //发送结束标记，结束标记为：QrcodeContentSendOver+文件路径+文件大小（7位数）
                    try {
                        final String sizeStr = ConvertUtils.int2String(sendSize);
                        //终止倒计时
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showBitmap(sendOver_Contnet + sendFlePath + sizeStr);
                                timer.cancel();
                            }
                        });

                    } catch (Exception e) {
                        //已处理
                        e.printStackTrace();
                    }
                }

            }
        }, 100, PSOTDELAY_TIME_SEND);

    }

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
        //屏幕常量
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.act_main);
        isFirst = true;
        initView();
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
        //
        if (conn != null && myBinder != null && myService != null) {
            //act通知service,可以发送数据传输了,
            myService.startServiceTrans();
        } else {
            initService();
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
        if (conn != null) {
            unbindService(conn);
        }
        //屏幕常亮
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            Log.d("SJY", "onQrsend监听触发发送按钮");
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
                initSendConnect();
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

