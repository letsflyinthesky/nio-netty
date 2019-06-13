package com.fly.nio.begin.base.client;

import com.fly.nio.begin.base.server.NioServer;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;

/**
 * @author zhishui
 * @date 2019-06-12
 * @time 19:20
 */
public class NioClient {

    private Selector selector;
    private SocketChannel socketChannel;
    private final int timeout;
    private SelectionKey selectionKey;
    private static final int DEFAULT_TIMEOUT = 1000;
    private static final int SERVER_PORT = 9999;
    private static Logger logger = LoggerFactory.getLogger(NioServer.class);

    private boolean stopped;

    public NioClient() {
        this(DEFAULT_TIMEOUT);
    }

    public NioClient(int timeout) {
        this.timeout = timeout < 0 ? DEFAULT_TIMEOUT : timeout;
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public NioClient(Selector selector) {
        this(selector, DEFAULT_TIMEOUT);
    }

    public NioClient(Selector selector, int timeout) {
        this.timeout = timeout < 0 ? DEFAULT_TIMEOUT : timeout;
        this.selector = Preconditions.checkNotNull(selector);
    }

    public boolean connect(SocketAddress address) {
        try {
            this.socketChannel = SocketChannel.open();
            //在打开后，就设置通道为非阻塞的
            socketChannel.configureBlocking(false);
            //不在选择器上注册事件，只是为了获取selectionKey
            this.selectionKey = socketChannel.register(selector, 0);
            return doConnect(socketChannel, address);
        } catch (IOException e) {
            throw new RuntimeException("IO异常，无法打开客户端套接字");
        }
    }

    public boolean doConnect(SocketChannel socketChannel, SocketAddress remoteAddress) {
        try {
            boolean connected = socketChannel.connect(remoteAddress);
            if (!connected) {
                selectionKey.interestOps(SelectionKey.OP_CONNECT);
            }
            return connected;
        } catch (IOException e) {
            throw new RuntimeException("客户端连接异常");
        }
    }

    private void finishConnect(SelectionKey key) {
        //        SocketChannel channel = (SocketChannel) selectionKey.channel();
        try {

            if (!socketChannel.finishConnect()) {
                throw new RuntimeException("连接异常");
            }

            if (logger.isInfoEnabled()) {
                logger.info("客户端连接成功，服务端地址为{}", socketChannel.getRemoteAddress());
            }

            ByteBuffer msg = ByteBuffer.wrap("hello server".getBytes());
            socketChannel.write(msg);

            //注册读取事件
//            socketChannel.register(selector, SelectionKey.OP_READ);


            //大家可能比较疑惑为什么这里使用的是成员变量selectionKey而不是传入的key，其实很简单，因为这两个是一样的
            //或者，我们使用另外一种方法
            int ops = selectionKey.interestOps();
            if ((ops & SelectionKey.OP_READ) == 0) {
                selectionKey.interestOps(ops | SelectionKey.OP_READ);
            }

            //这里调用wakeup，可以使下一次调用selector.select(timeout)直接返回，因为本次wakeup调用将作用于下一次select——有“记忆”作用。
            //另外，两次成功的select之间多次调用wakeup等价于一次调用。
            selector.wakeup();

        } catch (IOException e) {
            logger.error("连接异常");
            throw new RuntimeException(e);
        }

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

            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                //在连接成功后，将这里的连接事件取消
                //这里就确保客户端的OP_CONNECT事件只触发一次
                int ops = key.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                key.interestOps(ops);

                finishConnect(key);
            }

            if ((readyOps & SelectionKey.OP_READ) != 0) {
                doRead(key);
            }
        }
    }


    private void doRead(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        if (logger.isInfoEnabled()) {
            logger.info("接收到服务端的写入");
        }

        //创建读取的缓冲区，这里我们暂时只测试1024字节大小的数据
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        try {
            int read = channel.read(buffer);
            if (read > 0 && read < 1024) {
                String msg = new String(buffer.array()).trim();
                System.out.println("接收到服务端写入的信息为：" + msg);

                if (msg.equals("exit")) {
                    logger.info("服务端对应的客户端已经关闭");
                    stopped = true;
                    return;
                }
                ByteBuffer terminate = ByteBuffer.wrap("exit".getBytes());
                channel.write(terminate);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void listen() {
        try {
            for (; ; ) {
                if (stopped) {
                    break;
                }

                int keys = selector.select(timeout);

                if (keys > 0) {
                    handle(selector.selectedKeys().iterator());
                }

            }
        } catch (IOException e) {
            logger.error("IO异常", e);
        } finally {
            if (!socketChannel.socket().isClosed()) {
                try {
                    socketChannel.socket().close();
                } catch (IOException e) {
                }
            }
        }
    }

    public static void main(String[] args) {
        NioClient client = new NioClient();
        client.connect(new InetSocketAddress("localhost", SERVER_PORT));
        client.listen();
    }
}
