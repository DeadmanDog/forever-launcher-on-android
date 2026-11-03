package com.winlator.xserver.extensions;

import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.XRequestError;
import java.util.*;
import java.util.function.Supplier;

/** Fence state and indexed wait registrations, with wake callbacks outside the state lock. */
final class SyncFenceTable<C> {
    private static final class Fence<C> {
        final C owner;
        boolean triggered;
        Fence(C owner, boolean triggered) { this.owner = owner; this.triggered = triggered; }
    }
    private static final class Waiter<C> {
        final C client;
        final Set<Integer> ids;
        final Runnable resume;
        Waiter(C client, Set<Integer> ids, Runnable resume) {
            this.client = client; this.ids = ids; this.resume = resume;
        }
    }
    private static final class PresentWait<C> {
        final C client; final Runnable ready; final Runnable cancel;
        PresentWait(C client, Runnable ready, Runnable cancel) { this.client = client; this.ready = ready; this.cancel = cancel; }
    }
    private final Map<Integer, List<PresentWait<C>>> presentWaits = new HashMap<>();
    /** Queue the presentation, not all requests from its client (which may trigger the fence). */
    synchronized boolean afterTrigger(C client, int id, Runnable ready, Runnable cancel) throws BadFence {
        if (require(id).triggered) return false;
        presentWaits.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new PresentWait<>(client, ready, cancel));
        return true;
    }
    private final Map<Integer, Fence<C>> fences = new HashMap<>();
    private final IdentityHashMap<C, Waiter<C>> clients = new IdentityHashMap<>();
    private final Map<Integer, Set<Waiter<C>>> waiters = new HashMap<>();

    synchronized void create(int id, C owner, boolean triggered) throws BadIdChoice {
        if (fences.containsKey(id)) throw new BadIdChoice(id);
        fences.put(id, new Fence<>(owner, triggered));
    }

    private Fence<C> require(int id) throws BadFence {
        Fence<C> fence = fences.get(id);
        if (fence == null) throw new BadFence(id);
        return fence;
    }

    synchronized boolean isTriggered(int id) throws BadFence { return require(id).triggered; }

    synchronized void reset(int id) throws XRequestError {
        Fence<C> fence = require(id);
        if (!fence.triggered) throw new BadMatch();
        fence.triggered = false;
    }

    synchronized void await(C client, int[] ids, Supplier<Runnable> suspend) throws BadFence {
        boolean ready = ids.length == 0;
        Set<Integer> unique = new LinkedHashSet<>();
        for (int id : ids) { ready |= require(id).triggered; unique.add(id); }
        if (ready) return;
        if (clients.containsKey(client)) throw new IllegalStateException("Client already waiting");
        Runnable resume = suspend.get();
        Waiter<C> waiter = new Waiter<>(client, unique, resume);
        clients.put(client, waiter);
        for (int id : unique) waiters.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(waiter);
    }

    private void remove(Waiter<C> waiter) {
        clients.remove(waiter.client);
        for (int id : waiter.ids) {
            Set<Waiter<C>> set = waiters.get(id);
            if (set != null) { set.remove(waiter); if (set.isEmpty()) waiters.remove(id); }
        }
    }

    private void release(int id, List<Runnable> callbacks) {
        Set<Waiter<C>> set = waiters.get(id);
        if (set == null) return;
        for (Waiter<C> waiter : new ArrayList<>(set)) { remove(waiter); callbacks.add(waiter.resume); }
    }

    synchronized Object capture(int id) { return fences.get(id); }

    void triggerCaptured(int id, Object captured) {
        try { trigger(id, true, captured, true); }
        catch (BadFence impossible) { throw new AssertionError(impossible); }
    }

    void trigger(int id, boolean allowMissing) throws BadFence {
        trigger(id, allowMissing, null, false);
    }

    private void trigger(int id, boolean allowMissing, Object captured, boolean checkIdentity) throws BadFence {
        List<Runnable> callbacks;
        synchronized (this) {
            Fence<C> fence = fences.get(id);
            if (fence == null) { if (!allowMissing) throw new BadFence(id); return; }
            if (checkIdentity && fence != captured) return;
            fence.triggered = true;
            // Present signals usually have no XSync waiter: keep that hot path allocation-free.
            if (!waiters.containsKey(id) && !presentWaits.containsKey(id)) return;
            callbacks = new ArrayList<>();
            release(id, callbacks);
            List<PresentWait<C>> presents = presentWaits.remove(id);
            if (presents != null) for (PresentWait<C> present : presents) callbacks.add(present.ready);
        }
        callbacks.forEach(Runnable::run);
    }

    void destroy(int id) throws BadFence {
        List<Runnable> callbacks = new ArrayList<>();
        synchronized (this) {
            require(id); fences.remove(id); release(id, callbacks);
            List<PresentWait<C>> presents = presentWaits.remove(id);
            if (presents != null) for (PresentWait<C> present : presents) callbacks.add(present.cancel);
        }
        callbacks.forEach(Runnable::run);
    }

    void disconnect(C client) {
        List<Runnable> callbacks = new ArrayList<>();
        synchronized (this) {
            Waiter<C> waiter = clients.get(client);
            if (waiter != null) remove(waiter);
            presentWaits.values().forEach(list -> list.removeIf(p -> p.client == client));
            presentWaits.values().removeIf(List::isEmpty);
            List<Integer> owned = new ArrayList<>();
            fences.forEach((id, fence) -> { if (fence.owner == client) owned.add(id); });
            for (int id : owned) {
                fences.remove(id); release(id, callbacks);
                List<PresentWait<C>> presents = presentWaits.remove(id);
                if (presents != null) for (PresentWait<C> present : presents) callbacks.add(present.cancel);
            }
        }
        callbacks.forEach(Runnable::run);
    }

    synchronized int pendingCount() { return clients.size(); }
}
