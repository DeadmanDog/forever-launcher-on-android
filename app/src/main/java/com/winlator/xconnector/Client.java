package com.winlator.xconnector;

import java.nio.ByteOrder;

public class Client {
    public final ClientSocket clientSocket;
    private final XConnectorEpoll connector;
    private XInputStream inputStream;
    private XOutputStream outputStream;
    private Object tag;
    protected volatile boolean connected;
    final ClientRequestGate requestGate = new ClientRequestGate();
    protected java.util.concurrent.ScheduledFuture<?> pauseTask;
    protected long pauseDeadlineNs;
    protected int pacedSupersedes;
    protected boolean pacingExempt;

    public Client(XConnectorEpoll connector, ClientSocket clientSocket) {
        this.connector = connector;
        this.clientSocket = clientSocket;
    }

    public void createIOStreams() {
        if (inputStream != null || outputStream != null) return;
        inputStream = new XInputStream(clientSocket, connector.getInitialInputBufferCapacity());
        outputStream = new XOutputStream(clientSocket, connector.getInitialOutputBufferCapacity());
        inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
    }

    public Runnable suspendRequests() { return connector.suspendClientRequests(this); }

    public XConnectorEpoll getConnector() {
        return connector;
    }

    public XInputStream getInputStream() {
        return inputStream;
    }

    public XOutputStream getOutputStream() {
        return outputStream;
    }

    public Object getTag() {
        return tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

}
