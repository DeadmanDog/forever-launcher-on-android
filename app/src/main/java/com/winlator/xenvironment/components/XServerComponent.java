package com.winlator.xenvironment.components;

import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xserver.XClientConnectionHandler;
import com.winlator.xserver.XClientRequestHandler;
import com.winlator.xserver.XServer;
import com.winlator.xserver.extensions.PresentExtension;

public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        PresentExtension present = xServer.getExtension(PresentExtension.MAJOR_OPCODE);
        if (present != null) present.resetPacing();
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        xServer.inputDeviceManager.startSessionCallbacks();
        // Experimental (Settings -> Experiments): output that the client does not read is queued instead of blocking the writer.
        connector.setNonBlockingOutput(nonBlockingOutput());
        connector.setInitialInputBufferCapacity(262144);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
    }

    private boolean nonBlockingOutput() {
        try {
            return app.gamenative.ui.screen.wow.WowLaunchPolicy.INSTANCE.enabled(environment.getContext(), "xnonblock");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
            xServer.inputDeviceManager.stopSessionCallbacks();
            // connector.stop has joined all socket readers before mappings are released.
            var segments = xServer.getSHMSegmentManager();
            if (segments != null) segments.detachAll();
            PresentExtension present = xServer.getExtension(PresentExtension.MAJOR_OPCODE);
            if (present != null) present.close();
        }
    }

    public XServer getXServer() {
        return xServer;
    }
}
