
客户端在连接完成之后直接写会报：
Exception in thread "main" java.lang.RuntimeException: java.io.IOException: Connection reset by peer
解决办法：
这里是由于客户端关闭了，服务端仍然给客户端发送数据，服务端就会接收到这个异常

服务端在接收到客户端的连接请求后，调用方法ServerSocketChannel.accept可以获取到与客户端连接相关联的服务端的通道，以传输数据。
这两个通道如果有其中任何一个关闭，都会报下面这个异常
Exception in thread "main" java.lang.RuntimeException: java.io.IOException: Connection reset by peer
