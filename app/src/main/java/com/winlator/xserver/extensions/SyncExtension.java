package com.winlator.xserver.extensions;


import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension implements Extension {
    public static final byte MAJOR_OPCODE = -104;
    private final SyncFenceTable<XClient> fences = new SyncFenceTable<>();
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 2; }

    @Override
    public int getNumErrors() { return 3; } // SYNC 3.1: Counter, Alarm, Fence

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    boolean afterPresentFence(XClient client, int id, Runnable ready, Runnable cancel) throws BadFence {
        try {
            return fences.afterTrigger(client, id, ready, cancel);
        } catch (BadFence e) {
            throw new BadFence(firstErrorId, e.getData());
        }
    }

    Object capturePresentFence(int id) { return fences.capture(id); }

    void triggerPresentFence(int id, Object captured) { fences.triggerCaptured(id, captured); }

    public void onClientDisconnected(XClient client) { fences.disconnect(client); }

    private void createFence(XClient client, XInputStream input, XOutputStream output) throws XRequestError {
        requireLength(client, 12);
        input.skip(4);
        int id = input.readInt();
        if (!client.isValidResourceId(id)) throw new BadIdChoice(id);
        boolean triggered = input.readByte() == 1;
        input.skip(3);
        fences.create(id, client, triggered);
    }

    private void triggerFence(XClient client, XInputStream input, XOutputStream output) throws XRequestError {
        requireLength(client, 4);
        fences.trigger(input.readInt(), false);
    }

    private void resetFence(XClient client, XInputStream input, XOutputStream output) throws XRequestError {
        requireLength(client, 4);
        fences.reset(input.readInt());
    }

    private void destroyFence(XClient client, XInputStream input, XOutputStream output) throws XRequestError {
        requireLength(client, 4);
        fences.destroy(input.readInt());
    }

    private static void requireLength(XClient client, int length) throws XRequestError {
        if (client.getRemainingRequestLength() != length) throw new XRequestError(16, 0);
    }

    private void awaitFence(XClient client, XInputStream input, XOutputStream output) throws XRequestError {
        int length = client.getRemainingRequestLength();
        if (length < 0 || (length & 3) != 0) throw new XRequestError(16, 0);
        int[] ids = new int[length / 4];
        for (int i = 0; i < ids.length; i++) ids[i] = input.readInt();
        fences.await(client, ids, () -> client.connectorClient.suspendRequests());
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try {
            dispatch(client, inputStream, outputStream);
        } catch (BadFence e) {
            throw new BadFence(firstErrorId, e.getData());
        }
    }

    private void dispatch(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
