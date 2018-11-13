# aidl调用


回调写法

1. 在service的aidl中添加 register(自己的callback)和unregister(自己的callback)
2. 在服务端的aidl中再创建一个自己的callback,添加回调的方法。
