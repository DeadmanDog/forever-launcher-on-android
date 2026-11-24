package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.xserver.events.SelectionClear;
import com.winlator.xserver.events.SelectionNotify;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class SelectionManager implements XResourceManager.OnResourceLifecycleListener {
    private final SparseArray<Selection> selections = new SparseArray<>();

    public SelectionManager(WindowManager windowManager) {
        windowManager.addOnResourceLifecycleListener(this);
    }

    public static class Selection {
        public Window owner;
        private XClient client;

        public XClient getOwnerClient() {
            return client;
        }
    }

    /**
     * SetSelectionOwner; owner null (None) releases the selection. The previous owner gets SelectionClear when the owning client
     * changes or the selection is released; the event names the window that LOST the selection (it used to carry the new owner,
     * and a release to None crashed on it).
     */
    public void setSelection(int atom, Window owner, XClient client, int timestamp) {
        Selection selection = getSelection(atom);
        if (selection.owner != null && selection.client != null && (owner == null || selection.client != client)) {
            selection.client.sendEvent(new SelectionClear(timestamp, selection.owner, atom));
        }
        selection.owner = owner;
        selection.client = owner != null ? client : null;
    }

    /** X11: a selection whose owning client disconnects goes back to None (also when its owner window was not the client's). */
    public void onClientDisconnected(XClient client) {
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.valueAt(i);
            if (selection.client == client) {
                selection.owner = null;
                selection.client = null;
            }
        }
    }

    // ---- 1.2.15: a selection the server itself owns (Android text pasted into the game) ----
    // Two owner windows that no client can see: each new text flips the owner, so Wine (it compares the owner and the TARGETS
    // list every 2 s and on OpenClipboard) notices a new text even when the targets stay the same. Their ids come from the
    // server's own range (IDGenerator), below every client's resource-id base.
    private final Window[] serverOwners = new Window[2];
    private int serverOwnerIndex = 0;
    private byte[] serverText = new byte[0];
    private int serverGeneration = 0;
    private volatile int servedGeneration = 0;

    /**
     * The server takes [atom] (CLIPBOARD) with [text]; the previous client owner gets SelectionClear (Wine imports at once then).
     * Returns the text's generation; [getServedGeneration] reaches it once a client has read the new TARGETS or text.
     * The caller holds the WINDOW_MANAGER lock, as for SetSelectionOwner.
     */
    public int setServerText(int atom, String text, int timestamp) {
        if (serverOwners[0] == null) {
            for (int i = 0; i < serverOwners.length; i++) serverOwners[i] = new Window(IDGenerator.generate(), null, 0, 0, 1, 1, null);
        }
        serverOwnerIndex ^= 1;
        Selection selection = getSelection(atom);
        if (selection.owner != null && selection.client != null) {
            selection.client.sendEvent(new SelectionClear(timestamp, selection.owner, atom));
        }
        selection.owner = serverOwners[serverOwnerIndex];
        selection.client = null;
        serverText = text.getBytes(StandardCharsets.UTF_8);
        return ++serverGeneration;
    }

    public boolean isServerOwned(Selection selection) {
        return selection.owner != null && (selection.owner == serverOwners[0] || selection.owner == serverOwners[1]);
    }

    public int getServedGeneration() {
        return servedGeneration;
    }

    /**
     * ConvertSelection of a server-owned selection, answered as an owning client would: TARGETS (TARGETS, UTF8_STRING, STRING),
     * UTF8_STRING (the text) or STRING (Latin-1, unknown characters as '?') is written to the requestor's property in one piece,
     * then SelectionNotify; any other target gets property None. Property None in the request means "use the target".
     */
    public void serveServerSelection(XClient requestorClient, Window requestor, int atom, int target, int property, int timestamp) {
        int targetsAtom = Atom.internAtom("TARGETS"), utf8Atom = Atom.internAtom("UTF8_STRING"), stringAtom = Atom.getId("STRING");
        int prop = property != 0 ? property : target;
        if (target == targetsAtom) {
            ByteBuffer list = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            list.putInt(targetsAtom).putInt(utf8Atom).putInt(stringAtom);
            requestor.modifyProperty(prop, Atom.getId("ATOM"), Property.Format.INT_ARRAY, Property.Mode.REPLACE, list.array());
        }
        else if (target == utf8Atom) {
            requestor.modifyProperty(prop, utf8Atom, Property.Format.BYTE_ARRAY, Property.Mode.REPLACE, serverText);
        }
        else if (target == stringAtom) {
            byte[] latin1 = new String(serverText, StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1);
            requestor.modifyProperty(prop, stringAtom, Property.Format.BYTE_ARRAY, Property.Mode.REPLACE, latin1);
        }
        else prop = 0;
        if (prop != 0) servedGeneration = serverGeneration;
        requestorClient.sendEvent(new SelectionNotify(timestamp, requestor.id, atom, target, prop));
    }

    public Selection getSelection(int atom) {
        Selection selection = selections.get(atom);
        if (selection != null) return selection;
        selection = new Selection();
        selections.put(atom, selection);
        return selection;
    }

    @Override
    public void onFreeResource(XResource resource) {
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.valueAt(i);
            if (selection.owner == resource) {
                selection.owner = null;
                selection.client = null;
            }
        }
    }
}
