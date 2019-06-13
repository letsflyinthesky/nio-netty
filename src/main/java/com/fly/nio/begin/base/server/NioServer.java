package com.fly.nio.begin.base.server;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;

/**
 * @author zhishui
 * @date 2019-06-12
 * @time 19:20
 */
public class NioServer {

    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private SelectorProvider provider;
    private SocketAddress socketAddress;
    private final int timeout;
    private static final int DEFAULT_TIMEOUT = 1000;
    private static final int DEFAULT_PORT = 9999;


    private static Logger logger = LoggerFactory.getLogger(NioServer.class);

    public NioServer() {
        this(DEFAULT_TIMEOUT);
    }

    public NioServer(int timeout) {
        this(SelectorProvider.provider(), timeout);
    }

    public NioServer(SelectorProvider provider, int timeout) {
        this.provider = Preconditions.checkNotNull(provider);
        this.timeout = timeout < 0 ? DEFAULT_TIMEOUT : timeout;
        try {
            this.selector = provider.openSelector();
        } catch (IOException e) {
            throw new RuntimeException("无法打开新的选择器");
        }
    }

    public NioServer bind(int port) {
        //检查端口是否符合要求
        Preconditions.checkArgument(port >= 0 && port <= 65535, "端口取值范围必须在0到65535之间");
        return doBind(port);
    }

    private NioServer doBind(int port) {
        try {
            this.serverSocketChannel = provider.openServerSocketChannel();
            serverSocketChannel.configureBlocking(false);
            this.socketAddress = Preconditions.checkNotNull(new InetSocketAddress(port));
            serverSocketChannel.bind(socketAddress);

            //将通道选择器和通道绑定,并为该通道注册SelectionKey.OP_ACCEPT事件,注册该事件后，
            //selector.select()会一直阻塞到客户端连接到来，也就是客户端调用connect方法。
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            if (logger.isInfoEnabled()) {
                logger.info("服务端绑定端口{}，启动成功", port);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法打开服务端套接字");
        }

        return this;
    }

    private void handle(Iterator<SelectionKey> iterator) {
        Preconditions.checkNotNull(iterator);
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();

            if (!key.isValid()) {
                continue;
            }

            int readyOps = key.readyOps();

            if ((readyOps & SelectionKey.OP_ACCEPT) != 0) {
                doAccept(key);
            }

            if ((readyOps & SelectionKey.OP_READ) != 0) {
                doRead(key);
            }
        }
    }

    private void doRead(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        if (logger.isInfoEnabled()) {
            logger.info("接收到客户端的写入");
        }

        //创建读取的缓冲区，这里我们暂时只测试1024字节大小的数据
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        try {
            int read = channel.read(buffer);
            if (read > 0 && read < 1024) {
                String msg = new String(buffer.array()).trim();
                System.out.println("客户端写入的信息为：" + msg);



                if (msg.equals("exit")) {
                    logger.info("关闭客户端");
                    ByteBuffer message = ByteBuffer.wrap("exit".getBytes());
                    channel.write(message);
                    if (channel.isOpen()) {
                        channel.close();
                    }
                } else {
                    ByteBuffer message = ByteBuffer.wrap("我接收到你的消息了\n".getBytes());
                    channel.write(message);
                }

                //这里一定要进行key的有效性验证，否则很容易报无效key异常
                //Exception in thread "main" java.nio.channels.CancelledKeyException
                //因为我们上面调用了channel.close()，因此，这里必须要进行验证
                if (key.isValid()) {
                    int ops = key.interestOps();
                    if ((ops & SelectionKey.OP_READ) == 0) {
                        key.interestOps(ops | SelectionKey.OP_READ);
                    }
                }
            } else {
                logger.warn("客户端连接关闭。。。");
                key.cancel();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void doAccept(SelectionKey key) {
        //由于我们在SelectionKey.OP_ACCEPT事件上注册的通道是ServerSocketChannel，因此我们这里获取到的是ServerSocketChannel
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();


        try {
            //获得和客户端连接的通道
            SocketChannel channel = serverChannel.accept();
            //获取到客户端通道后，将其设置为非阻塞
            channel.configureBlocking(false);

            if (logger.isInfoEnabled()) {
                logger.info("新的客户端接入，客户端地址为{}", channel.socket().getRemoteSocketAddress());
            }

            //将该通道注册到选择器上，注册事件为SelectionKey.OP_READ
            //也就是说，如果客户端执行了写操作，那么我们这里注册的事件会将selector.select阻塞打断
            channel.register(selector, SelectionKey.OP_READ);

            //也就是说下一次调用selector.select(timeout)立即返回
            selector.wakeup();
            logger.info("服务端开始注册了OP_READ事件");
        } catch (IOException e) {
            logger.error("", e);
        }


    }


    private void listen() {
        try {
            for (; ; ) {

                int keys = selector.select(timeout);

                if (keys > 0) {
                    handle(selector.selectedKeys().iterator());
                }
            }
        } catch (IOException e) {
            logger.error("IO异常", e);
        } finally {
            if (!serverSocketChannel.socket().isClosed()) {
                try {
                    serverSocketChannel.socket().close();
                } catch (IOException e) {
                }
            }
        }
    }

    public static void main(String[] args) {
        NioServer server = new NioServer();
        server.bind(DEFAULT_PORT).listen();

    }

}
