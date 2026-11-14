package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Atom;
import com.winlator.xserver.SelectionManager;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadAtom;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.SelectionNotify;
import com.winlator.xserver.events.SelectionRequest;

import java.io.IOException;

public abstract class SelectionRequests {
    public static void setSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int atom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        // Owner None (0) releases the selection; only a non-zero id that names no window is BadWindow.
        Window owner = null;
        if (windowId != 0) {
            owner = client.xServer.windowManager.getWindow(windowId);
            if (owner == null) throw new BadWindow(windowId);
        }
        if (!Atom.isValid(atom)) throw new BadAtom(atom);

        client.xServer.selectionManager.setSelection(atom, owner, client, timestamp);
    }

    /**
     * ConvertSelection: with an owner the request goes to it as SelectionRequest; without one the requestor gets SelectionNotify
     * with property None at once (the request used to be skipped silently, so the requestor waited for an answer in vain).
     */
    public static void convertSelection(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int requestorId = inputStream.readInt();
        int atom = inputStream.readInt();
        int target = inputStream.readInt();
        int property = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window requestor = client.xServer.windowManager.getWindow(requestorId);
        if (requestor == null) throw new BadWindow(requestorId);
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        if (!Atom.isValid(target)) throw new BadAtom(target);

        SelectionManager.Selection selection = client.xServer.selectionManager.getSelection(atom);
        XClient ownerClient = selection.getOwnerClient();
        if (selection.owner != null && ownerClient != null) {
            ownerClient.sendEvent(new SelectionRequest(timestamp, selection.owner.id, requestorId, atom, target, property));
        }
        else client.sendEvent(new SelectionNotify(timestamp, requestorId, atom, target, 0));
    }

    public static void getSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int atom = inputStream.readInt();
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        Window owner = client.xServer.selectionManager.getSelection(atom).owner;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(owner != null ? owner.id : 0);
            outputStream.writePad(20);
        }
    }
}
