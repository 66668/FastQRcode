package com.ruijia.qrcode;

/**
 * 原生摄像头的常量池
 */
public class Constants {
    /**
     * 发送时间间隔 默认150
     */
    public static final String TIME_INTERVAL = "time_interval";
    public static final int DEFAULT_TIME = 150;

    /**
     * 最大文件大小 默认5M
     */
    public static final String FILE_SIZE = "fileSize";
    public static final int DEFAULT_SIZE = 5;

    /**
     * zxing core 3.3.3 最大的传输容量2954,17长度做标记头和标记尾。2954-17=2937
     */
    public static final int qrSize = 2938;


    /**
     * 识别过程，最大20次来回传图没有结果，强制结束
     */
    public static final int MAX_TIMES = 20;

}
