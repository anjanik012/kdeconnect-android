package org.kde.connect.LinkProviders;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.textline.LineDelimiter;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.kde.connect.ComputerLinks.BaseComputerLink;
import org.kde.connect.ComputerLinks.NioSessionComputerLink;
import org.kde.connect.NetworkPackage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashMap;

public class BroadcastTcpLinkProvider extends BaseLinkProvider {

    private final static int port = 1714;

    Context context;
    HashMap<String, NioSessionComputerLink> visibleComputers = new HashMap<String, NioSessionComputerLink>();
    HashMap<Long, NioSessionComputerLink> nioSessions = new HashMap<Long, NioSessionComputerLink>();

    NioSocketAcceptor tcpAcceptor = null;
    NioDatagramAcceptor udpAcceptor = null;

    private final IoHandler tcpHandler = new IoHandlerAdapter() {
        @Override
        public void sessionClosed(IoSession session) throws Exception {

            NioSessionComputerLink brokenLink = nioSessions.remove(session.getId());
            if (brokenLink != null) {
                connectionLost(brokenLink);
                String deviceId = brokenLink.getDeviceId();
                visibleComputers.remove(deviceId);
            }

        }

        @Override
        public void messageReceived(IoSession session, Object message) throws Exception {
            super.messageReceived(session, message);

            String address = ((InetSocketAddress) session.getRemoteAddress()).toString();
            Log.e("BroadcastTcpLinkProvider","Incoming package, address: "+address);

            String theMessage = (String) message;
            NetworkPackage np = NetworkPackage.unserialize(theMessage);

            NioSessionComputerLink prevLink = nioSessions.get(session.getId());

            if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                NioSessionComputerLink link = new NioSessionComputerLink(session, np.getString("deviceId"), BroadcastTcpLinkProvider.this);
                nioSessions.put(session.getId(),link);
                addLink(np, link);
            } else {
                if (prevLink == null) {
                    Log.e("BroadcastTcpLinkProvider","2 Expecting an identity package");
                } else {
                    prevLink.injectNetworkPackage(np);
                }
            }

        }
    };

    private void addLink(NetworkPackage identityPackage, NioSessionComputerLink link) {
        Log.e("BroadcastTcpLinkProvider","addLink to "+identityPackage.getString("deviceName"));
        String deviceId = identityPackage.getString("deviceId");
        BaseComputerLink oldLink = visibleComputers.get(deviceId);
        if (oldLink != null) {
            Log.e("BroadcastTcpLinkProvider","Removing old connection to same device");
            connectionLost(oldLink);
        }
        visibleComputers.put(deviceId, link);
        connectionAccepted(identityPackage, link);
    }

    public BroadcastTcpLinkProvider(Context context) {

        this.context = context;

        //This handles the case when I'm the new device in the network and somebody answers my introduction package
        tcpAcceptor = new NioSocketAcceptor();
        tcpAcceptor.setHandler(tcpHandler);
        tcpAcceptor.getSessionConfig().setKeepAlive(true);
        //TextLineCodecFactory will split incoming data delimited by the given string
        tcpAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );
        try {
            tcpAcceptor.getSessionConfig().setReuseAddress(true);
            tcpAcceptor.bind(new InetSocketAddress(port));
        } catch(Exception e) {
            Log.e("BroadcastTcpLinkProvider", "Error: Could not bind tcp socket");
            e.printStackTrace();
        }


    }

    @Override
    public void onStart() {

        //This handles the case when I'm the existing device in the network and receive a "hello" UDP package
        udpAcceptor = new NioDatagramAcceptor();
        udpAcceptor.getSessionConfig().setReuseAddress(true);        //Share port if existing
        udpAcceptor.setHandler(new IoHandlerAdapter() {
            @Override
            public void messageReceived(IoSession udpSession, Object message) throws Exception {
                super.messageReceived(udpSession, message);

                Log.e("BroadcastTcpLinkProvider", "Udp message received (" + message.getClass() + ") " + message.toString());

                NetworkPackage np = null;

                try {
                    //We should receive a string thanks to the TextLineCodecFactory filter
                    String theMessage = (String) message;
                    np = NetworkPackage.unserialize(theMessage);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("BroadcastTcpLinkProvider", "Could not unserialize package");
                }

                if (np != null) {

                    final NetworkPackage identityPackage = np;
                    if (!np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                        Log.e("BroadcastTcpLinkProvider", "1 Expecting an identity package");
                        return;
                    }

                    Log.e("BroadcastTcpLinkProvider", "It is an identity package, creating link");

                    final InetSocketAddress address = (InetSocketAddress) udpSession.getRemoteAddress();

                    final NioSocketConnector connector = new NioSocketConnector();
                    connector.setHandler(tcpHandler);
                    //TextLineCodecFactory will split incoming data delimited by the given string
                    connector.getFilterChain().addLast("codec",
                            new ProtocolCodecFilter(
                                    new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                            )
                    );
                    connector.getSessionConfig().setKeepAlive(true);

                    ConnectFuture future = connector.connect(new InetSocketAddress(address.getAddress(), port));
                    future.addListener(new IoFutureListener<IoFuture>() {
                        @Override
                        public void operationComplete(IoFuture ioFuture) {
                            IoSession session = ioFuture.getSession();

                            Log.e("BroadcastTcpLinkProvider", "Connection successful: " + session.isConnected());

                            NioSessionComputerLink link = new NioSessionComputerLink(session, identityPackage.getString("deviceId"), BroadcastTcpLinkProvider.this);

                            NetworkPackage np2 = NetworkPackage.createIdentityPackage(context);
                            link.sendPackage(np2);

                            nioSessions.put(session.getId(), link);
                            addLink(identityPackage, link);
                        }
                    });

                }
            }

        });
        //TextLineCodecFactory will split incoming data delimited by the given string
        udpAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );
        try {
            udpAcceptor.bind(new InetSocketAddress(port));
        } catch(Exception e) {
            Log.e("BroadcastTcpLinkProvider", "Error: Could not bind udp socket");
            e.printStackTrace();
        }

        onNetworkChange();

    }

    @Override
    public void onNetworkChange() {

        Log.e("BroadcastTcpLinkProvider","OnNetworkChange: " + (udpAcceptor != null));

        if (udpAcceptor == null) return;

        //I'm on a new network, let's be polite and introduce myself
        new AsyncTask<Void,Void,Void>() {
            @Override
            protected Void doInBackground(Void... voids) {

                try {
                    String s = NetworkPackage.createIdentityPackage(context).serialize();
                    byte[] b = s.getBytes("UTF-8");
                    DatagramPacket packet = new DatagramPacket(b, b.length, InetAddress.getByAddress(new byte[]{-1,-1,-1,-1}), port);
                    DatagramSocket socket = new DatagramSocket();
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.send(packet);
                    Log.e("BroadcastTcpLinkProvider","Udp identity package sent");
                } catch(Exception e) {
                    e.printStackTrace();
                    Log.e("BroadcastTcpLinkProvider","Sending udp identity package failed");
                }

                return null;
            }

        }.execute();
    }

    @Override
    public void onStop() {

        if (udpAcceptor != null) {
            udpAcceptor.unbind();
            udpAcceptor = null;
        }

    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public String getName() {
        return "BroadcastTcpLinkProvider";
    }
}