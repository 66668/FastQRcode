package com.ruijia.demo;

import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import cn.qqtheme.framework.picker.FilePicker;

/**
 * 应用层/业务层demo
 */
public class DemoActivity extends BaseActivity implements View.OnClickListener {
    public static final String TAG = "SJY";
    //=================控件=================
    private EditText et_timeInteral, et_maxSize;
    private Button btn_select, btn_QRCtrl, btn_QRRecv, btn_QRSend, btn_bind, btn_unbind, btn_time_sure, btn_maxSize_sure;
    private TextView tv_path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initMyView();
    }

    private void initMyView() {
        et_timeInteral = findViewById(R.id.et_timeInteral);
        et_maxSize = findViewById(R.id.et_maxSize);
        tv_path = findViewById(R.id.tv_path);
        btn_maxSize_sure = findViewById(R.id.btn_maxSize_sure);
        btn_time_sure = findViewById(R.id.btn_time_sure);
        btn_bind = findViewById(R.id.btn_bind);
        btn_unbind = findViewById(R.id.btn_unbind);
        btn_select = findViewById(R.id.btn_select);
        btn_QRCtrl = findViewById(R.id.btn_QRCtrl);
        btn_QRRecv = findViewById(R.id.btn_QRRecv);
        btn_QRSend = findViewById(R.id.btn_QRSend);
        btn_maxSize_sure.setOnClickListener(this);
        btn_time_sure.setOnClickListener(this);
        tv_path.setOnClickListener(this);
        btn_bind.setOnClickListener(this);
        btn_unbind.setOnClickListener(this);
        btn_select.setOnClickListener(this);
        btn_QRCtrl.setOnClickListener(this);
        btn_QRRecv.setOnClickListener(this);
        btn_QRSend.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        if (v == tv_path) {
            // 清空路径
            selectPath = null;
            tv_path.setText("");
        } else if (v == btn_select) {
            selectDialog();
        } else if (v == btn_time_sure) {
            String str = et_timeInteral.getText().toString();
            if (TextUtils.isEmpty(str)) {
                timeInterval = 150;
                et_timeInteral.setText("");
                et_timeInteral.setHint("默认发送时间间隔：" + timeInterval + "ms");
            } else {
                timeInterval = Integer.parseInt(str);
                et_timeInteral.setText("");
                et_timeInteral.setHint("设置发送时间间隔：" + timeInterval + "ms");
            }
        } else if (v == btn_maxSize_sure) {
            String str = et_maxSize.getText().toString();
            if (TextUtils.isEmpty(str)) {
                maxSize = 5;
                et_maxSize.setText("");
                et_maxSize.setHint("默认最大文件：" + maxSize + "M");
            } else {
                maxSize = Integer.parseInt(str);
                et_maxSize.setText("");
                et_maxSize.setHint("设置文件最大：" + maxSize + "M");
            }

        } else if (v == btn_QRCtrl) {
            qrCtrl();
        } else if (v == btn_QRRecv) {
            QrRecv();
        } else if (v == btn_QRSend) {
            qrSend();
        } else if (v == btn_bind) {
            bind();
        } else if (v == btn_unbind) {
            unbind();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbind();
    }

    /**
     *
     */
    private void selectDialog() {
        //文件选择器
        FilePicker picker = new FilePicker(this, FilePicker.FILE);
//        picker.setShowUpDir(true);
        picker.setRootPath(Environment.getExternalStorageDirectory().getAbsolutePath());
//        picker.setAllowExtensions(new String[]{".txt"});
        picker.setAllowExtensions(null);//没有过滤
        picker.setFileIcon(getResources().getDrawable(android.R.drawable.ic_menu_agenda));
        picker.setFolderIcon(getResources().getDrawable(android.R.drawable.ic_menu_upload_you_tube));
        //picker.setArrowIcon(getResources().getDrawable(android.R.drawable.arrow_down_float));
        picker.setOnFilePickListener(new FilePicker.OnFilePickListener() {
            @Override
            public void onFilePicked(String currentPath) {
                Log.d("SJY", "currentPath=" + currentPath);
                selectPath = currentPath;
                tv_path.setText(selectPath);
            }
        });
        picker.show();
    }
    //-------------------------进程间通讯 客户端向服务端发送请求-------------------------------

    /**
     * 控制设置
     */
    private void qrCtrl() {
        try {
            //调用服务端接口
            ibinder.QrCtrl(timeInterval, maxSize);
        } catch (RemoteException e) {
            //异常
            e.printStackTrace();
            Log.e("SJY", "binder" + e.toString());
        }

    }

    /**
     * 发送文件
     */
    private void qrSend() {
        if (TextUtils.isEmpty(selectPath)) {
            Log.d(TAG, "请选择文件路径");
            return;
        }
        try {
            //调用服务端接口
            ibinder.QRSend(selectPath);
        } catch (RemoteException e) {
            //异常
            e.printStackTrace();
            Log.e(TAG, "binder" + e.toString());
        }

    }

    private void QrRecv() {
        try {
            Log.d(TAG, ibinder.QRRecv());
        } catch (RemoteException e) {
            Log.d(TAG, e.toString());
            e.printStackTrace();
        }
    }

    //-------------------------服务端回调-------------------------------


    @Override
    void clientIsTrans(boolean isSuccess, String msg) {
        super.clientIsTrans(isSuccess, msg);
        Log.d(TAG, "isTrans-isSuccess=" + isSuccess + "--msg=" + msg);
    }

    @Override
    void clientSplitToIoTime(long time, String msg) {
        super.clientSplitToIoTime(time, msg);
        Log.d(TAG, "splitToIoTime-time=" + time + "--msg=" + msg);
    }

    @Override
    void clientSplitToArrayTime(long time, String msg) {
        super.clientSplitToIoTime(time, msg);
        Log.d(TAG, "SplitToArrayTime-time=" + time + "--msg=" + msg);
    }

    @Override
    void clientCreateNewArrayTime(long time, String msg) {
        super.clientSplitToIoTime(time, msg);
        Log.d(TAG, "createNewArrayTime-time=" + time + "--msg=" + msg);
    }

    @Override
    void clientCreateQrImgTime(long time, String msg) {
        super.clientCreateQrImgTime(time, msg);
        Log.d(TAG, "createQrImgTime-time=" + time + "--msg=" + msg);
    }

    @Override
    void clientCreateQrImgProgress(int total, int position, String msg) {
        super.clientCreateQrImgProgress(total, position, msg);
        Log.d(TAG, "createQrImgProgress-total=" + total + "--position=" + position + "--msg=" + msg);
    }

    @Override
    void clientTransProgress(long time, int total, int position, String msg) {
        super.clientTransProgress(time, total, position, msg);
        Log.d(TAG, "二维码进度：" + msg + "--识别耗时=" + time + "ms--total=" + total + "--position=" + position);
    }

    @Override
    void clientTransTime(long time, String msg) {
        super.clientTransTime(time, msg);
        Log.d(TAG, "transTime-time=" + time + "--msg=" + msg);
    }

    @Override
    void clientTransComplete() {
        super.clientTransComplete();
        Log.d(TAG, "transComplete");
    }
}
