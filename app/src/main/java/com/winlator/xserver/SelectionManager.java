package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.xserver.events.SelectionClear;

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
