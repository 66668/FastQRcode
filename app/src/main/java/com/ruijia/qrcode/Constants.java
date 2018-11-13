package com.ruijia.qrcode;

/**
 * 常量池
 */
public class Constants {
    /**
     * 发送时间间隔 默认150
     */
    public static final String TIME_INTERVAL = "time_interval";

    /**
     * 最大文件大小 默认5M
     */
    public static final String FILE_SIZE = "fileSize";

    /**
     * zxing core 3.3.3 最大的传输容量2954,17长度做标记头和标记尾。2954-17=2937
     */
    public static final int qrSize = 2935;

    public static final String KEY_PATH = "path";

    public static final String KEY_STRLIST = "strList";

    public static final String KEY_BITMAPLIST = "bitmapList";

}
