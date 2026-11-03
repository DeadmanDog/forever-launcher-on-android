// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 Joshua Tam <297250+joshuatam@users.noreply.github.com>

//! Userspace reimplementation of the Linux ntsync driver semantics
//! (drivers/misc/ntsync.c): NT semaphores, mutexes, and events.
//!
//! Cross-process design using Android/Linux NDK primitives, tuned for
//! Proton/Wine workloads where dozens of threads hammer sync objects:
//!
//! - All objects live in a fixed-size table in a file-backed shared mapping
//!   (opened by every process at the same path), mirroring the kernel's
//!   global object table.
//! - Each object has a process-shared operation lock. WAIT_ALL takes all
//!   affected locks in slot order, checks every object, then commits without
//!   exposing a partial acquisition. Operations on unrelated objects remain
//!   independent; waiter-list registration uses the separate region lock.
//! - A process-shared lock serializes waiter-list registration, wake walks,
//!   creation and close. Android uses atomic PID/TID ownership plus futex;
//!   a killed holder poisons the region instead of exposing corrupt lists.
//! - Wakeups are per-object like the kernel's wait queues: each object keeps
//!   an intrusive list of registered waiters, and each waiter sleeps with
//!   futex(FUTEX_WAIT) on its own word in the shared waiter pool. A state
//!   change walks only that object's list (mirroring wake_up(&obj->q) +
//!   try_wake_any_obj/try_wake_all re-check). Registration and wake walks
//!   both happen under the region lock, and waiters re-check under it
//!   before sleeping, so no wakeup can be missed.
//!
//! Divergences from the kernel: closing an object that other threads are
//! waiting on fails those waits with EINVAL (the kernel keeps the object
//! alive via fd references); object cleanup after a process crash requires
//! ntsync_sweep_dead().

use std::ffi::CString;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::Duration;

pub const MAX_WAIT_COUNT: usize = 64;

const MAGIC: u64 = 0x6E7473796E635F75; // "ntsyc_u"
// The region file name "wow-ntsync.v14.shm" is also compiled into wineserver and ntdll (android/ntsync_android/wow_ntsync.h): the
// server unlinks that name at the start of every session, so the number must stay in step with them. r25 changed the layout
// (4x slots, 2x nodes, header counters) without renaming: every session starts on a fresh file, and a file of another layout is
// refused by the capacity check in open_and_map (ESTALE), never misread.
const VERSION: u32 = 14;
const SLOT_COUNT: u32 = 65536; // r25: 4x - Wine 11 needs an object per open file, thread and timer
const NODE_COUNT: u32 = 16384;
const INDEX_BITS: u32 = 16; // log2(SLOT_COUNT)
const INDEX_MASK: u32 = (1 << INDEX_BITS) - 1;
const GENERATION_MASK: u32 = u32::MAX >> INDEX_BITS;
const NIL: u32 = u32::MAX;
/// Slot::closing: bit 31 = every handle is closed; bits 0..30 = waits (non-alert nodes) that hold the object.
const CLOSING: u32 = 1 << 31;
const REF_MASK: u32 = CLOSING - 1;
/// WaitNode::flags: this node holds one of those references on the object of `handle`.
const NODE_REF: u32 = 8;

/// Resolve the region path: explicit argument, else $NTSYNC_SHM, else
/// $TMPDIR/ntsync_userspace.shm (the layout version is inserted before the
/// ".shm" extension: ntsync_userspace.vN.shm).
/// NTSYNC_SHM exists because in containerized
/// setups (e.g. GameNative) wineserver and game processes run with different
/// TMPDIRs and would otherwise mmap different files and never share objects.
fn resolve_path(path: Option<&str>) -> Result<String, i32> {
    if let Some(p) = path {
        return Ok(p.into());
    }
    if let Ok(shm) = std::env::var("NTSYNC_SHM") {
        if !shm.is_empty() {
            return Ok(shm);
        }
    }
    match std::env::var("TMPDIR") {
        Ok(tmp) if !tmp.is_empty() => Ok(format!("{tmp}/ntsync_userspace.shm")),
        _ => Err(libc::EINVAL),
    }
}

const SLOT_FREE: u32 = 0;
const SLOT_USED: u32 = 1;

const TYPE_SEM: u32 = 1;
const TYPE_MUTEX: u32 = 2;
const TYPE_EVENT: u32 = 3;
/// Slot 0 is permanently reserved: handle 0 is the "no alert" sentinel in
/// ntsync_wait_args.alert, so it must never be handed out as a real object.
const TYPE_RESERVED: u32 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    /// Bad handle, wrong object type, or invalid argument (-EINVAL).
    Invalid,
    /// Caller is not the mutex owner (-EPERM).
    Perm,
    /// Semaphore count would exceed its maximum (-EOVERFLOW).
    Overflow,
    /// Region could not be opened/created/mapped (-errno).
    Init(i32),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WaitOutcome {
    /// Object at `index` satisfied the wait; `owner_dead` is set when an
    /// abandoned mutex was acquired (kernel: -EOWNERDEAD).
    Signaled { index: u32, owner_dead: bool },
    /// Timed out (kernel: -ETIMEDOUT).
    Timeout,
    /// Bad arguments, or an object was closed mid-wait (kernel: -EINVAL).
    Invalid,
}

/// One object slot in the shared table. `state`/`generation`/`obj_type`/`pid`
/// change only under the region lock; `w0` and `d` are accessed atomically
/// at all times. State mutation/read is additionally protected by operation_lock.
///
/// w0 packing by type:
///   sem:   hi32 = max,    lo32 = count
///   mutex: hi32 = count (31 bits) | ownerdead<<31, lo32 = owner
///   event: hi32 = manual, lo32 = signaled
#[repr(C)]
struct Slot {
    state: u32,     // SLOT_FREE / SLOT_USED
    generation: u32,
    obj_type: u32,  // TYPE_*
    pid: u32,       // creator pid, for ntsync_sweep_dead
    waiter_head: u32, // head of the per-object waiter list (node index, NIL)
    /// Bit 31 (CLOSING) = every handle is closed but waits that started before the close keep the object alive (kernel:
    /// their fds do); nobody can signal it any more. Bits 0..30 count those waits, from their first block until they return,
    /// also while a signaler has already taken them off the waiter list. The slot is reused once the count is zero.
    closing: u32,
    w0: u64,        // packed object state (atomic)
    d: u64,         // reserved event sequence field (atomic; no pulse credit)
    operation_lock: region_mutex::Lock, // persistent across slot generations
}

const SLOT_SIZE: usize = std::mem::size_of::<Slot>();

/// One waiter node in the shared pool. A thread waiting on N objects
/// allocates N nodes and registers one on each object's waiter list;
/// it sleeps on nodes[0].seq ("head node"). Signalers detach the node
/// from their object's list, bump the head node's seq, and FUTEX_WAKE it.
/// Accessed only under the region lock, except `seq` during futex waits.
#[repr(C)]
struct WaitNode {
    seq: AtomicU32,     // futex word (only the head node's is waited on)
    registered: u32,    // 1 while linked on `obj`'s waiter list
    obj: u32,           // object slot index this node is registered on
    wait_word: u32,     // head node index whose `seq` is the futex word
    obj_prev: u32,      // per-object list links (doubly linked, O(1) detach)
    obj_next: u32,
    waiter_next: u32,   // next node of the same waiter / free-list link
    pid: u32,           // Linux owner PID
    tid: u32,           // Linux TID; NT owner IDs are not OS thread IDs
    /// CLOCK_MONOTONIC ns when the head node was last woken (diagnostics;
    /// written by the waker before bumping `seq`, read by the waiter after
    /// futex wake while it still owns the node).
    wake_ts: std::sync::atomic::AtomicU64,
    handle: u32,       // generation-qualified handle for pulse eligibility
    owner: u32,        // NT owner ID, needed for atomic WAIT_ALL acquisition
    flags: u32,        // bit 0: wait-all; bit 1: this node is the alert
    completion: AtomicU32, // head only: NIL pending, else index | ownerdead<<31
}

/// CLOCK_MONOTONIC in nanoseconds.
fn monotonic_ns() -> u64 {
    let mut ts = unsafe { std::mem::zeroed::<libc::timespec>() };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    (ts.tv_sec as u64) * 1_000_000_000 + ts.tv_nsec as u64
}

const NODE_SIZE: usize = std::mem::size_of::<WaitNode>();

#[repr(C)]
struct Header {
    magic: u64,
    version: u32,
    capacity: u32,
    node_capacity: u32,
    /// Free-list head for the waiter node pool (linked via waiter_next).
    node_free_head: u32,
    /// Lazy pool: index of the first never-handed-out node. Nodes below
    /// this cursor are either in use or on the free list; nodes at or above
    /// it have never been touched, so their pages stay untouched (sparse
    /// zero pages) until actually needed. Caller holds the lock.
    node_fresh: u32,
    /// Allocation cursor.
    next_scan: u32,
    /// CLOCK_MONOTONIC ns of the last auto-sweep completion (atomic).
    sweep_ns: u64,
    /// Debug: which thread currently holds `lock` (0 = none). Written after
    /// acquiring, cleared before releasing; only meaningful while locked.
    lock_owner_pid: u32,
    lock_owner_tid: u32,
    poisoned: AtomicU32,
    /// Objects alive (SLOT_USED, including closing ones), their high-water mark, and creations refused on a full table.
    live_slots: AtomicU32,
    peak_slots: AtomicU32,
    alloc_failures: AtomicU32,
    /// Waits that returned Invalid after they had blocked (an object closed or swept under a running wait).
    wait_invalid: AtomicU32,
    lock: region_mutex::Lock,
    // Slot array follows immediately, then the waiter node pool.
}

const HEADER_SIZE: usize = std::mem::size_of::<Header>();

// Android Bionic has no pthread robust mutex API. Use shared futex wakeups
// through Android-allowed syscalls, with explicit PID/TID owner-death detection.
#[cfg(any(target_os = "android", feature = "bionic-owner-lock-tests"))]
#[path = "region_mutex.rs"]
mod region_mutex;

#[cfg(not(any(target_os = "android", feature = "bionic-owner-lock-tests")))]
mod region_mutex {
    pub type Lock = libc::pthread_mutex_t;
    pub unsafe fn init(lock: *mut Lock) -> i32 {
        let mut attr = std::mem::zeroed();
        let mut e = libc::pthread_mutexattr_init(&mut attr);
        if e == 0 { e = libc::pthread_mutexattr_setpshared(&mut attr, libc::PTHREAD_PROCESS_SHARED); }
        if e == 0 { e = libc::pthread_mutexattr_setrobust(&mut attr, libc::PTHREAD_MUTEX_ROBUST); }
        if e == 0 { e = libc::pthread_mutex_init(lock, &attr); }
        libc::pthread_mutexattr_destroy(&mut attr);
        e
    }
    pub unsafe fn lock(lock: *mut Lock) -> i32 { libc::pthread_mutex_lock(lock) }
    pub unsafe fn try_lock(lock: *mut Lock) -> i32 { libc::pthread_mutex_trylock(lock) }
    pub unsafe fn timed_lock(lock: *mut Lock, ts: *const libc::timespec) -> i32 {
        libc::pthread_mutex_timedlock(lock, ts)
    }
    pub unsafe fn consistent(lock: *mut Lock) -> i32 { libc::pthread_mutex_consistent(lock) }
    pub unsafe fn unlock(lock: *mut Lock) -> i32 { libc::pthread_mutex_unlock(lock) }
}

fn errno() -> i32 {
    #[cfg(target_os = "android")]
    unsafe {
        *libc::__errno()
    }
    #[cfg(not(target_os = "android"))]
    unsafe {
        *libc::__errno_location()
    }
}

// Lock-free accessors for the packed per-object state words.
#[inline]
fn ld32(p: &u32) -> u32 {
    unsafe { (&*(p as *const u32 as *const AtomicU32)).load(Ordering::Acquire) }
}
#[inline]
fn ld64(p: &u64) -> u64 {
    unsafe { (&*(p as *const u64 as *const AtomicU64)).load(Ordering::Acquire) }
}
#[inline]
fn st32(p: &u32, v: u32) {
    unsafe { (&*(p as *const u32 as *const AtomicU32)).store(v, Ordering::Release) }
}
#[inline]
fn st64(p: &u64, v: u64) {
    unsafe { (&*(p as *const u64 as *const AtomicU64)).store(v, Ordering::Release) }
}
#[inline]
fn cas64(p: &u64, old: u64, new: u64) -> Result<u64, u64> {
    let r = unsafe {
        (&*(p as *const u64 as *const AtomicU64)).compare_exchange(
            old,
            new,
            Ordering::AcqRel,
            Ordering::Acquire,
        )
    };
    if r.is_err() {
        stat_bump(6); // cas_retries
    }
    r
}

// w0 packing helpers.
#[inline]
fn pack_sem(count: u32, max: u32) -> u64 {
    ((max as u64) << 32) | count as u64
}
#[inline]
fn pack_mutex(owner: u32, count: u32, ownerdead: bool) -> u64 {
    (((count as u64) & 0x7fff_ffff) | ((ownerdead as u64) << 31)) << 32 | owner as u64
}
#[inline]
fn pack_event(signaled: bool, manual: bool) -> u64 {
    ((manual as u64) << 32) | signaled as u64
}

fn nodes_offset() -> usize {
    HEADER_SIZE + SLOT_COUNT as usize * SLOT_SIZE
}

fn region_size() -> usize {
    nodes_offset() + NODE_COUNT as usize * NODE_SIZE
}

struct Region {
    base: *mut u8,
}

unsafe impl Send for Region {}
unsafe impl Sync for Region {}

impl Region {
    fn header(&self) -> &Header {
        unsafe { &*(self.base as *const Header) }
    }

    fn slot_ptr(&self, index: u32) -> *mut Slot {
        unsafe { self.base.add(HEADER_SIZE + index as usize * SLOT_SIZE) as *mut Slot }
    }

    fn node_ptr(&self, index: u32) -> *mut WaitNode {
        unsafe { self.base.add(nodes_offset() + index as usize * NODE_SIZE) as *mut WaitNode }
    }

    /// Get a used slot matching a handle's index+generation.
    /// Lock-free safe: state/generation only change under the region lock
    /// and are read here with acquire loads.
    fn slot(&self, handle: u32) -> Option<*mut Slot> {
        let index = handle & INDEX_MASK;
        let generation = handle >> INDEX_BITS;
        if index >= SLOT_COUNT {
            return None;
        }
        let slot = self.slot_ptr(index);
        let s = unsafe { &*slot };
        if ld32(&s.state) == SLOT_USED && (ld32(&s.generation) & GENERATION_MASK) == generation {
            Some(slot)
        } else {
            None
        }
    }

    /// Like slot(), but a handle whose object was closed no longer resolves: a new wait, a signal or a second close on it is
    /// invalid. Waiters that registered before the close keep using slot() and keep waiting.
    fn slot_open(&self, handle: u32) -> Option<*mut Slot> {
        self.slot(handle).filter(|s| unsafe { ld32(&(**s).closing) } & CLOSING == 0)
    }

    fn object_guard(&self, index: u32) -> Result<ObjectGuard<'_>, i32> {
        if index >= SLOT_COUNT || self.header().poisoned.load(Ordering::Acquire) != 0 {
            return Err(libc::EIO);
        }
        let lock = unsafe { &(*self.slot_ptr(index)).operation_lock as *const region_mutex::Lock as *mut _ };
        let ret = unsafe { region_mutex::lock(lock) };
        if ret != 0 && ret != libc::EOWNERDEAD { return Err(ret); }
        if ret == libc::EOWNERDEAD || self.header().poisoned.load(Ordering::Acquire) != 0 {
            // A killed WAIT_ALL holder may have committed only part of a group.
            // Do not reuse that region; release sleepers without walking lists.
            self.header().poisoned.store(1, Ordering::Release);
            for i in 0..ld32(&self.header().node_fresh).min(NODE_COUNT) {
                let node = unsafe { &*self.node_ptr(i) };
                node.seq.fetch_add(1, Ordering::Release);
                futex_wake(&node.seq);
            }
            unsafe { region_mutex::consistent(lock); region_mutex::unlock(lock); }
            return Err(libc::EIO);
        }
        Ok(ObjectGuard { lock, _region: self })
    }

    fn lock_guard(&self) -> Result<RegionGuard<'_>, i32> {
        let lock = &self.header().lock as *const region_mutex::Lock as *mut _;
        let debug = debug_enabled();
        let mut waited = std::time::Duration::ZERO;
        // Wine suspends threads with SIGUSR1; if it lands while we hold the
        // shared region mutex the holder stays parked and every process
        // (including wineserver) wedges on the lock. Defer it until release.
        // Consumers without a SIGUSR1 handler can set
        // NTSYNC_NO_SIGUSR1_BLOCK=1 to skip the two pthread_sigmask
        // syscalls per lock acquisition.
        let signal_guard = SignalGuard::new();
        let mut tries = 0u32;
        loop {
            tries += 1;
            let ret = if debug {
                // Poll with a timeout so a stuck holder can be logged.
                let mut ts = unsafe { std::mem::zeroed::<libc::timespec>() };
                unsafe { libc::clock_gettime(libc::CLOCK_REALTIME, &mut ts) };
                ts.tv_sec += 5;
                unsafe { region_mutex::timed_lock(lock, &ts) }
            } else {
                unsafe { region_mutex::lock(lock) }
            };
            if ret == 0 || ret == libc::EOWNERDEAD {
                stat_bump(4); // lock_acq
                if tries > 1 {
                    stat_bump(5); // lock_contended
                }
                if ret == libc::EOWNERDEAD || self.header().poisoned.load(Ordering::Acquire) != 0 {
                    // A killed holder can interrupt a linked-list mutation.
                    // Do not walk possibly corrupt metadata: fail closed until
                    // a fresh wineserver creates a new region on next launch.
                    self.header().poisoned.store(1, Ordering::Release);
                    // Wake sleepers without following the interrupted lists.
                    for i in 0..self.header().node_fresh.min(NODE_COUNT) {
                        let node = unsafe { &*self.node_ptr(i) };
                        node.seq.fetch_add(1, Ordering::Release);
                        futex_wake(&node.seq);
                    }
                    unsafe { region_mutex::consistent(lock); region_mutex::unlock(lock); }
                    return Err(libc::EIO);
                }
                if debug {
                    let h = self.header() as *const Header as *mut Header;
                    unsafe {
                        (*h).lock_owner_pid = std::process::id();
                        (*h).lock_owner_tid = current_tid();
                    }
                }
                return Ok(RegionGuard { region: self, _signals: signal_guard });
            }
            if ret == libc::ETIMEDOUT {
                waited += std::time::Duration::from_secs(5);
                let h = self.header();
                debug_log(&format!(
                    "region lock blocked {:?} (pid {} tid {}): held by pid {} tid {}",
                    waited,
                    std::process::id(),
                    current_tid(),
                    h.lock_owner_pid,
                    h.lock_owner_tid,
                ));
                continue;
            }
            if ret != libc::EINTR {
                return Err(ret);
            }
        }
    }
}

struct RegionGuard<'a> {
    region: &'a Region,
    _signals: SignalGuard,
}

impl Drop for RegionGuard<'_> {
    fn drop(&mut self) {
        let h = self.region.header() as *const Header as *mut Header;
        unsafe {
            (*h).lock_owner_pid = 0;
            (*h).lock_owner_tid = 0;
            let lock = &(*h).lock as *const region_mutex::Lock as *mut _;
            region_mutex::unlock(lock);
        }
    }
}

struct ObjectGuard<'a> {
    lock: *mut region_mutex::Lock,
    _region: &'a Region,
}
impl Drop for ObjectGuard<'_> {
    fn drop(&mut self) { unsafe { region_mutex::unlock(self.lock); } }
}

/// A Wine suspend signal must not park a thread while it owns object locks.
/// WAIT_ALL defers once for the whole group; metadata-locked callers already
/// defer it and must not issue another pair of pthread_sigmask syscalls.
pub type SuspendHook = unsafe extern "C" fn();
#[derive(Clone, Copy)]
struct SuspendHooks { enter: SuspendHook, leave: SuspendHook }
static SUSPEND_HOOKS: OnceLock<SuspendHooks> = OnceLock::new();
pub fn set_suspend_hooks(enter: SuspendHook, leave: SuspendHook) -> Result<(), i32> {
    if let Some(h) = SUSPEND_HOOKS.get() {
        return if h.enter as usize == enter as usize && h.leave as usize == leave as usize { Ok(()) } else { Err(libc::EBUSY) };
    }
    SUSPEND_HOOKS.set(SuspendHooks {enter, leave}).map_err(|_| libc::EBUSY)
}
enum SignalGuard { None, Mask(libc::sigset_t), Hook(SuspendHook) }
impl SignalGuard {
    fn new() -> Self {
        if !sigusr1_block_enabled() { return Self::None; }
        #[cfg(test)]
        R10_SIGNAL_DEPTH_TEST.set(R10_SIGNAL_DEPTH_TEST.get()+1);
        if let Some(h) = SUSPEND_HOOKS.get() {
            unsafe { (h.enter)(); }
            return Self::Hook(h.leave);
        }
        unsafe {
            let mut old = std::mem::zeroed(); let mut mask = std::mem::zeroed();
            libc::sigemptyset(&mut mask);libc::sigaddset(&mut mask, libc::SIGUSR1);
            libc::pthread_sigmask(libc::SIG_BLOCK, &mask, &mut old);
            Self::Mask(old)
        }
    }
}
impl Drop for SignalGuard {
    fn drop(&mut self) { unsafe {
        match self {
            Self::Hook(leave) => leave(),
            Self::Mask(old) => { libc::pthread_sigmask(libc::SIG_SETMASK, old, std::ptr::null_mut()); },
            Self::None => {}
        }
        #[cfg(test)]
        if !matches!(self, Self::None) { R10_SIGNAL_DEPTH_TEST.set(R10_SIGNAL_DEPTH_TEST.get()-1); }
    } }
}

/// Never acquire the metadata lock while an object lock is held. Signalers
/// release object locks before walking waiter lists, matching close/sweep's
/// metadata -> object order and avoiding a lock inversion with WAIT_ALL.
fn object_operation<T>(handle: u32, kind: u32, f: impl FnOnce(&Slot) -> Result<(T, bool), Error>) -> Result<T, Error> {
    let r = region()?;
    // Protect state mutation AND its notification against deferred abort.
    // Object gates still drop before metadata is taken by the wake walk.
    let _signals = SignalGuard::new();
    let (value, wake) = {
        let _object = r.object_guard(handle & INDEX_MASK).map_err(Error::Init)?;
        let slot = r.slot_open(handle).filter(|s| unsafe { (**s).obj_type } == kind).ok_or(Error::Invalid)?;
        f(unsafe { &*slot })?
    };
    if wake { wake_waiters_if_any(r, handle & INDEX_MASK); }
    Ok(value)
}


#[cfg(test)]
thread_local! {
    static PRE_SLEEP_DELAY_TEST: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    static PRE_SLEEP_CAPTURE_TEST: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static FUTEX_TIMEOUT_TEST: std::cell::Cell<Option<Duration>> = const { std::cell::Cell::new(None) };
    static FUTEX_DEADLINE_TEST: std::cell::Cell<Option<(i32, u64)>> = const { std::cell::Cell::new(None) };
}
/// FUTEX_WAIT on a waiter node's seq word.
/// Sleep on `addr` while it holds `expected`, at most until `until` on that deadline's own clock. FUTEX_WAIT_BITSET takes an
/// absolute time, and FUTEX_CLOCK_REALTIME makes the kernel measure it on CLOCK_REALTIME: a clock step then moves the wake-up
/// as it does for the kernel ntsync driver's absolute timer (a relative FUTEX_WAIT would keep the length computed before the step).
/// FUTEX_BITSET_MATCH_ANY (all bits); the Android libc crate does not export it.
const FUTEX_BITSET_MATCH_ANY: u32 = u32::MAX;

fn futex_wait(addr: *const AtomicU32, expected: u32, until: Option<Deadline>) -> i32 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let mut op = libc::FUTEX_WAIT_BITSET;
    if let Some(d) = until {
        ts.tv_sec = (d.abs_ns / 1_000_000_000) as _;
        ts.tv_nsec = (d.abs_ns % 1_000_000_000) as _;
        if d.clock == libc::CLOCK_REALTIME { op |= libc::FUTEX_CLOCK_REALTIME; }
    }
    #[cfg(test)]
    if let Some(callback) = R10_TIMEOUT_HOOK_TEST.with(|h| h.borrow_mut().take()) {
        callback(); return libc::ETIMEDOUT;
    }
    #[cfg(test)]
    if PRE_SLEEP_CAPTURE_TEST.get() {
        FUTEX_TIMEOUT_TEST.set(until.map(|d| d.remaining()));
        // What the syscall below would get: its op and absolute time.
        FUTEX_DEADLINE_TEST.set(until.map(|_| (op, ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64)));
        PRE_SLEEP_CAPTURE_TEST.set(false);
        return libc::ETIMEDOUT;
    }
    let ts_ptr = if until.is_some() { &mut ts as *mut _ } else { std::ptr::null_mut() };
    let ret = unsafe {
        libc::syscall(libc::SYS_futex, addr as *const u32, op, expected, ts_ptr, 0, FUTEX_BITSET_MATCH_ANY)
    };
    if ret == 0 {
        0
    } else {
        errno()
    }
}

fn futex_wake(addr: *const AtomicU32) {
    // Each head node is slept on by exactly one waiter, so wake one.
    unsafe {
        libc::syscall(libc::SYS_futex, addr as *const u32, libc::FUTEX_WAKE, 1, 0, 0, 0);
    }
}

/// Pop a node from the free list, or hand out a never-before-used node
/// from the lazy pool. Caller holds the lock.
fn node_alloc(r: &Region) -> Option<u32> {
    let head = r.header().node_free_head;
    let idx = if head != NIL {
        let node = r.node_ptr(head);
        let next = unsafe { (*node).waiter_next };
        unsafe { (*(r.base as *mut Header)).node_free_head = next };
        head
    } else {
        // Free list empty: consume a fresh node. Its memory is a sparse
        // zero page (never written since region creation), so seq = 0,
        // registered = 0 — exactly the state a recycled node is reset to.
        let fresh = r.header().node_fresh;
        if fresh >= NODE_COUNT {
            return None;
        }
        unsafe { st32(&(*(r.base as *mut Header)).node_fresh, fresh + 1) };
        fresh
    };
    let node = r.node_ptr(idx);
    unsafe {
        st32(&(*node).registered, 0);
        (*node).obj = NIL;
        (*node).wait_word = NIL;
        (*node).obj_prev = NIL;
        (*node).obj_next = NIL;
        (*node).waiter_next = NIL;
        (*node).pid = std::process::id();
        (*node).tid = current_tid();
        (*node).handle = 0;
        (*node).owner = 0;
        (*node).flags = 0;
        (*node).completion.store(NIL, Ordering::Relaxed);
    }
    Some(idx)
}

/// Return a node to the free list. Caller holds the lock; the node must
/// already be detached from any object list.
fn node_free(r: &Region, idx: u32) {
    let node = r.node_ptr(idx);
    unsafe {
        if (*node).flags & NODE_REF != 0 {
            (*node).flags &= !NODE_REF;
            // The generation check skips a slot that was freed and reused meanwhile (a dead creator's objects are swept).
            if let Some(slot) = r.slot((*node).handle) {
                let word = ld32(&(*slot).closing);
                if word & REF_MASK != 0 {
                    st32(&(*slot).closing, word - 1);
                }
            }
        }
        // PID zero distinguishes free nodes from allocated but already detached
        // nodes. A dead waiter may have been woken just before it exits.
        (*node).pid = 0;
        (*node).tid = 0;
        (*node).wait_word = NIL;
        (*node).waiter_next = r.header().node_free_head;
        (*(r.base as *mut Header)).node_free_head = idx;
    }
}

/// Detach a node from its object's waiter list (O(1), doubly linked).
/// Caller holds the lock.
fn node_detach(r: &Region, idx: u32) {
    let node = r.node_ptr(idx);
    unsafe {
        if (*node).registered == 0 {
            return;
        }
        let obj = (*node).obj;
        let slot = r.slot_ptr(obj);
        if (*node).obj_prev == NIL {
            st32(&(*slot).waiter_head, (*node).obj_next);
        } else {
            (*r.node_ptr((*node).obj_prev)).obj_next = (*node).obj_next;
        }
        if (*node).obj_next != NIL {
            (*r.node_ptr((*node).obj_next)).obj_prev = (*node).obj_prev;
        }
        st32(&(*node).registered, 0);
        (*node).obj = NIL;
        (*node).obj_prev = NIL;
        (*node).obj_next = NIL;
    }
}

/// Register `node` on object `obj_idx`'s waiter list. Caller holds the lock.
fn node_register(r: &Region, idx: u32, obj_idx: u32, wait_word: u32) {
    let node = r.node_ptr(idx);
    let slot = r.slot_ptr(obj_idx);
    unsafe {
        let old_head = (*slot).waiter_head;
        st32(&(*node).registered, 1);
        (*node).obj = obj_idx;
        (*node).wait_word = wait_word;
        (*node).obj_prev = NIL;
        (*node).obj_next = old_head;
        if old_head != NIL {
            (*r.node_ptr(old_head)).obj_prev = idx;
        }
        st32(&(*slot).waiter_head, idx);
    }
}

/// Wake every waiter registered on an object (kernel: wake_up(&obj->q)).
/// Caller holds the lock. Each waiter is detached and its head seq bumped;
/// it re-checks conditions when scheduled.
fn wake_object(r: &Region, obj_idx: u32) {
    let debug = debug_enabled();
    let slot = r.slot_ptr(obj_idx);
    let mut idx = unsafe { (*slot).waiter_head };
    while idx != NIL {
        let node = r.node_ptr(idx);
        let (next, word) = unsafe { ((*node).obj_next, (*node).wait_word) };
        node_detach(r, idx);
        let head = r.node_ptr(word);
        unsafe {
            if debug {
                (*head).wake_ts.store(monotonic_ns(), Ordering::Relaxed);
            }
            (*head).seq.fetch_add(1, Ordering::Release);
            futex_wake(&(*head).seq);
        }
        idx = next;
    }
}

// Default off: asynchronous termination can abandon a sleeping waiter.
// Keep r9 notification semantics until cancellation/refund is fully supported.
fn wake_handoff_enabled() -> bool {
    #[cfg(test)]
    if R10_FORCE_MUTEX_HANDOFF.get() { return true; }
    static ENABLED: OnceLock<bool> = OnceLock::new();
    *ENABLED.get_or_init(|| std::env::var("NTSYNC_WAKE_HANDOFF").is_ok_and(|s| s == "1"))
}
fn thread_alive(pid: u32, tid: u32) -> bool {
    if pid == 0 || tid == 0 { return false; }
    let result = unsafe { libc::syscall(libc::SYS_tgkill, pid as libc::pid_t, tid as libc::pid_t, 0) };
    !(result != 0 && errno() == libc::ESRCH)
}
fn discard_waiter(r: &Region, head: u32) {
    let mut member = head;
    while member != NIL {
        let next = unsafe { (*r.node_ptr(member)).waiter_next };
        node_detach(r, member); node_free(r, member); member = next;
    }
}
fn discard_dead_waiters(r: &Region, object: u32) {
    let slot = r.slot_ptr(object);
    let mut node = unsafe { (*slot).waiter_head };
    while node != NIL {
        let head = unsafe { (*r.node_ptr(node)).wait_word };
        let (pid,tid) = unsafe { ((*r.node_ptr(head)).pid,(*r.node_ptr(head)).tid) };
        if !thread_alive(pid,tid) {
            discard_waiter(r,head); node=unsafe { (*slot).waiter_head };
        } else { node=unsafe { (*r.node_ptr(node)).obj_next }; }
    }
}

// Forced ownership transfer can convoy very short contended mutex sections.
// Keep r9 mutex scheduling by default; the measured candidate is opt-in.
fn mutex_handoff_enabled() -> bool {
    #[cfg(test)]
    if R10_FORCE_MUTEX_HANDOFF.get() { return true; }
    static ENABLED: OnceLock<bool> = OnceLock::new();
    *ENABLED.get_or_init(|| std::env::var("NTSYNC_MUTEX_HANDOFF").is_ok_and(|s| s == "1"))
}
#[cfg(test)]
thread_local! { static R10_FORCE_MUTEX_HANDOFF: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }

/// Signal operations hand resources to eligible registered waits before waking.
/// Metadata -> sorted object gates is unchanged; invalidation still wakes all.
fn wake_waiters_if_any(r: &Region, obj_idx: u32) {
    #[cfg(test)]
    if R10_CHECK_NOTIFY_SCOPE_TEST.get() { assert!(R10_SIGNAL_DEPTH_TEST.get()>0, "termination guard ended before waiter notification"); }

    // Pair with registration's SC fence. Never rely on an optional Wine
    // suspend hook or a particular ARM instruction selection for wake safety.
    std::sync::atomic::fence(Ordering::SeqCst);
    let slot = r.slot_ptr(obj_idx);
    if unsafe { ld32(&(*slot).waiter_head) } == NIL { return; }
    stat_bump(8);
    if let Ok(_g) = r.lock_guard() {
        if !wake_handoff_enabled() || (unsafe { ld32(&(*slot).obj_type) } == TYPE_MUTEX && !mutex_handoff_enabled()) {
            wake_object(r, obj_idx); return;
        }
        discard_dead_waiters(r, obj_idx);
        let mut node = unsafe { (*slot).waiter_head };
        while node != NIL {
            let (head_index, next) = unsafe { ((*r.node_ptr(node)).wait_word, (*r.node_ptr(node)).obj_next) };
            let head = r.node_ptr(head_index);
            let (owner, flags) = unsafe { ((*head).owner, (*head).flags) };
            let mut handles = [0u32; MAX_WAIT_COUNT];
            let mut count = 0usize;
            let mut alert = 0;
            let mut member = head_index;
            while member != NIL {
                let n = r.node_ptr(member);
                unsafe {
                    if (*n).flags & 2 != 0 { alert = (*n).handle; }
                    else { handles[count] = (*n).handle; count += 1; }
                    member = (*n).waiter_next;
                }
            }
            // Objects first, the alert only when none could be taken (see wait()).
            let mut outcome = try_satisfy(r, &handles[..count], owner, &[0; MAX_WAIT_COUNT], flags & 1 != 0, true);
            if outcome.is_none() && alert != 0 && r.slot(alert).is_some_and(|s|
                unsafe { ld64(&(*s).w0) as u32 != 0 }) {
                outcome = Some(WaitOutcome::Signaled { index: count as u32, owner_dead: false });
            }
            if let Some(WaitOutcome::Signaled { index, owner_dead }) = outcome {
                unsafe { (*head).completion.store(index | ((owner_dead as u32) << 31), Ordering::Release); }
                let mut member = head_index;
                while member != NIL {
                    let next = unsafe { (*r.node_ptr(member)).waiter_next };
                    node_detach(r, member); member = next;
                }
                unsafe {
                    if debug_enabled() { (*head).wake_ts.store(monotonic_ns(), Ordering::Relaxed); }
                    (*head).seq.fetch_add(1, Ordering::Release);
                    futex_wake(&(*head).seq);
                }
                node = unsafe { (*slot).waiter_head };
            } else { node = next; }
        }
    }
}

fn current_tid() -> u32 {
    // Bionic's gettid() reads the thread's cached tid (TCB) instead of entering the kernel (r30, OPT13-09); region_mutex.rs
    // already uses it on Android. Other hosts keep the raw syscall.
    #[cfg(target_os = "android")]
    { unsafe { libc::gettid() as u32 } }
    #[cfg(not(target_os = "android"))]
    { unsafe { libc::syscall(libc::SYS_gettid) as u32 } }
}

fn open_and_map(path: &str) -> Result<Region, i32> {
    // Distinct library layouts never share a region file: the version suffix
    // keeps stale files from older builds out of the way. A leftover file
    // with the wrong size/version is unlinked and recreated, never truncated
    // or zeroed in place - in-place mutation would SIGBUS/corrupt any process
    // that still has it mapped (e.g. a wineserver from an older build sharing
    // the path during a version transition).
    let path = match path.rsplit_once('.') {
        // Keep .shm as the final extension; the layout version goes in the
        // middle: ntsync_userspace.v7.shm
        Some((stem, "shm")) => format!("{stem}.v{VERSION}.shm"),
        _ => format!("{path}.v{VERSION}"),
    };
    if debug_enabled() {
        debug_log(&format!("ntsync shm path: {path} (pid {})", std::process::id()));
    }
    let c_path = CString::new(path).map_err(|_| libc::EINVAL)?;
    for _ in 0..4 {
        match unsafe { try_open_and_map(&c_path) } {
            Ok(region) => return Ok(region),
            Err(libc::ESTALE) => unsafe {
                // Stale or corrupt file; remove it (processes that still have
                // it mapped keep their ghost inode, unharmed) and retry.
                libc::unlink(c_path.as_ptr());
            }
            Err(e) => return Err(e),
        }
    }
    Err(libc::ESTALE)
}

/// Returns ESTALE when the existing file does not match this library's
/// layout; the caller then unlinks and retries.
unsafe fn try_open_and_map(c_path: &CString) -> Result<Region, i32> {
    let fd = libc::open(
        c_path.as_ptr(),
        libc::O_RDWR | libc::O_CREAT | libc::O_CLOEXEC,
        0o600,
    );
    if fd < 0 {
        return Err(errno());
    }
    // Serialize initialization across processes.
    if libc::flock(fd, libc::LOCK_EX) != 0 {
        let e = errno();
        libc::close(fd);
        return Err(e);
    }
    let mut st: libc::stat = std::mem::zeroed();
    if libc::fstat(fd, &mut st) != 0 {
        let e = errno();
        libc::flock(fd, libc::LOCK_UN);
        libc::close(fd);
        return Err(e);
    }
    if st.st_size != 0 && st.st_size != region_size() as i64 {
        libc::flock(fd, libc::LOCK_UN);
        libc::close(fd);
        return Err(libc::ESTALE);
    }
    let need_init = st.st_size == 0;
    if need_init && libc::ftruncate(fd, region_size() as _) != 0 {
        let e = errno();
        libc::flock(fd, libc::LOCK_UN);
        libc::close(fd);
        return Err(e);
    }
    let base = libc::mmap(
        std::ptr::null_mut(),
        region_size(),
        libc::PROT_READ | libc::PROT_WRITE,
        libc::MAP_SHARED,
        fd,
        0,
    );
    if base == libc::MAP_FAILED {
        let e = errno();
        libc::flock(fd, libc::LOCK_UN);
        libc::close(fd);
        return Err(e);
    }
    let region = Region { base: base as *mut u8 };
    if need_init {
        // No bulk zeroing: a freshly ftruncate'd file reads as zeros, and
        // leaving the pages untouched keeps them sparse - no page-cache
        // allocation, no flash writeback for the ~960 KiB nobody may ever
        // use. Only the header and the reserved slot 0 are written; waiter
        // nodes come from the lazy pool (header.node_fresh), so the node
        // area needs no initialization at all. Zeroed memory gives every
        // slot SLOT_FREE/generation 0 and every node seq 0/registered 0,
        // which is exactly the state the recycling paths reset to.
        let header = &mut *(base as *mut Header);
        let e = region_mutex::init(&mut header.lock);
        if e != 0 {
            libc::munmap(base, region_size());
            libc::flock(fd, libc::LOCK_UN);
            libc::close(fd);
            return Err(e);
        }
        #[cfg(not(any(target_os = "android", feature = "bionic-owner-lock-tests")))]
        for i in 0..SLOT_COUNT {
            let e = region_mutex::init(&mut (*region.slot_ptr(i)).operation_lock);
            if e != 0 { libc::munmap(base, region_size()); libc::flock(fd, libc::LOCK_UN); libc::close(fd); return Err(e); }
        }
        header.version = VERSION;
        header.capacity = SLOT_COUNT;
        header.node_capacity = NODE_COUNT;
        header.node_free_head = NIL;
        header.node_fresh = 0;
        header.next_scan = 0;
        header.sweep_ns = 0;
        // Reserve slot 0 (see TYPE_RESERVED). pid 0 keeps sweep_dead away.
        let slot0 = &mut *region.slot_ptr(0);
        slot0.waiter_head = NIL;
        slot0.obj_type = TYPE_RESERVED;
        slot0.pid = 0;
        slot0.state = SLOT_USED;
        // Publish only after the entire header and reserved slot are ready.
        header.magic = MAGIC;
    } else if region.header().magic != MAGIC
        || region.header().version != VERSION
        || region.header().capacity != SLOT_COUNT
        || region.header().node_capacity != NODE_COUNT
    {
        libc::munmap(base, region_size());
        libc::flock(fd, libc::LOCK_UN);
        libc::close(fd);
        return Err(libc::ESTALE);
    }
    libc::flock(fd, libc::LOCK_UN);
    libc::close(fd);
    Ok(region)
}

/// Reclaim the current thread's wait registrations before Linux can reuse its
/// TID. Wine invokes this only after leaving every ntsync critical section.
/// Never initialize the library while a thread is being destroyed.
static EXIT_SKIPPED: AtomicU64 = AtomicU64::new(0);
static EXIT_RETRIED: AtomicU64 = AtomicU64::new(0);

pub fn thread_exit() -> Result<(), Error> {
    let Some(Ok(r)) = REGION.get() else { return Ok(()); };
    if r.header().poisoned.load(Ordering::Acquire) != 0 { return Err(Error::Init(libc::EIO)); }
    let lock = &r.header().lock as *const region_mutex::Lock as *mut _;
    // Wine calls with depth zero. Do not allocate, initialize OnceLock or use
    // a blocking lock from quit_handler. Retry the entire transaction so a
    // busy object gate never sleeps while holding the region metadata lock.
    let mut deadline = 0;
    loop {
        let result = unsafe { region_mutex::try_lock(lock) };
        let attempt = if result == 0 {
            let _metadata = RegionGuard { region: r, _signals: SignalGuard::None };
            cleanup_thread_locked(r, std::process::id(), current_tid())
        } else { Err(Error::Init(result)) };
        match attempt {
            Err(Error::Init(libc::EBUSY)) => {},
            other => return other,
        }
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) } != 0 {
            EXIT_SKIPPED.fetch_add(1, Ordering::Relaxed);
            return Err(Error::Init(libc::EBUSY));
        }
        let now = ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64;
        if deadline == 0 {
            deadline = now.saturating_add(2_000_000);
            EXIT_RETRIED.fetch_add(1, Ordering::Relaxed);
        } else if now >= deadline {
            EXIT_SKIPPED.fetch_add(1, Ordering::Relaxed);
            return Err(Error::Init(libc::EBUSY));
        }
        // Raw syscall avoids any language runtime or TLS initialization.
        unsafe { libc::syscall(libc::SYS_sched_yield); }
    }
}

fn cleanup_thread_locked(r: &Region, pid: u32, tid: u32) -> Result<(), Error> {
    for head_index in 0..r.header().node_fresh {
        let head = r.node_ptr(head_index);
        if unsafe { (*head).pid != pid || (*head).tid != tid || (*head).wait_word != head_index } { continue; }
        let completion = unsafe { (*head).completion.load(Ordering::Acquire) };
        let all = unsafe { (*head).flags & 1 != 0 };
        let owner = unsafe { (*head).owner };
        let mut handles = [0u32; MAX_WAIT_COUNT];
        let mut transient = [false; MAX_WAIT_COUNT];
        let mut count = 0;
        let mut member = head_index;
        while member != NIL {
            let n = unsafe { &*r.node_ptr(member) };
            if n.flags & 2 == 0 {
                if count >= MAX_WAIT_COUNT { return Err(Error::Init(libc::EIO)); }
                handles[count] = n.handle; transient[count] = n.flags & 4 != 0; count += 1;
            }
            member = n.waiter_next;
        }
        // An alert completion owns no regular object. An incomplete waiter has
        // acquired nothing. Refund only a completion which wait() never received.
        let selected = (completion & 0x7fff_ffff) as usize;
        let refund = completion != NIL && selected < count;
        let mut order = [0u32; MAX_WAIT_COUNT];
        for i in 0..count { order[i] = handles[i] & INDEX_MASK; }
        order[..count].sort_unstable();
        let mut gates: [Option<ObjectGuard<'_>>; MAX_WAIT_COUNT] = std::array::from_fn(|_| None);
        if refund {
            let mut last = NIL;
            for (i,index) in order[..count].iter().copied().enumerate() {
                if index != last { let lock = unsafe { &(*r.slot_ptr(index)).operation_lock as *const region_mutex::Lock as *mut _ };
                    let result = unsafe { region_mutex::try_lock(lock) };
                    if result != 0 { return Err(Error::Init(result)); }
                    gates[i] = Some(ObjectGuard { lock, _region: r }); last=index; }
            }
            for i in 0..count {
                if (!all && selected != i) || transient[i] { continue; }
                // A destroyed/reused handle must never receive an old credit.
                let Some(slot) = r.slot(handles[i]) else { continue; };
                let slot = unsafe { &*slot }; let word = ld64(&slot.w0);
                match ld32(&slot.obj_type) {
                    TYPE_SEM => {
                        let count = word as u32; let max = (word >> 32) as u32;
                        if count < max { st64(&slot.w0, pack_sem(count + 1, max)); }
                    }
                    TYPE_EVENT if word >> 32 == 0 => { st64(&slot.w0, word | 1); }
                    TYPE_MUTEX if word as u32 == owner => { st64(&slot.w0, pack_mutex(0, 0, true)); }
                    _ => {}
                }
            }
        }
        // Remove the dying thread first. Notification must not grant it again.
        discard_waiter(r, head_index);
        drop(gates);
        if refund {
            for i in 0..count {
                if (all || selected == i) && !transient[i] && r.slot(handles[i]).is_some() {
                    wake_object(r, handles[i] & INDEX_MASK);
                }
            }
        }
    }
    // Allocation can precede registration. Also reclaim detached/orphan nodes.
    for i in 0..r.header().node_fresh {
        if unsafe { (*r.node_ptr(i)).pid == pid && (*r.node_ptr(i)).tid == tid } {
            node_detach(r,i); node_free(r,i);
        }
    }
    Ok(())
}

static REGION: OnceLock<Result<Region, i32>> = OnceLock::new();

/// Initialize (idempotently) with the shared-memory file at `path`.
/// Called automatically with $TMPDIR/ntsync_userspace.vN.shm if the library
/// is used without an explicit init.
pub fn init(path: Option<&str>) -> Result<(), Error> {
    debug_enabled(); // read NTSYNC_DEBUG and arm the watchdog/stats at startup
    let result = REGION.get_or_init(|| {
        let mapped = resolve_path(path).and_then(|p| open_and_map(&p));
        if mapped.is_ok() && is_wineserver() {
            // One line per session in the diagnostics: how full the shared object table got.
            unsafe { libc::atexit(report_usage_at_exit); }
        }
        mapped
    });
    match result {
        Ok(_) => Ok(()),
        Err(e) => Err(Error::Init(*e)),
    }
}

fn is_wineserver() -> bool {
    std::env::current_exe().ok().and_then(|p| p.file_name().map(|n| n.to_string_lossy().starts_with("wineserver"))).unwrap_or(false)
}

extern "C" fn report_usage_at_exit() {
    if let Some(Ok(r)) = REGION.get() {
        let h = r.header();
        eprintln!("ntsync: objects live {} peak {} of {}, waiter nodes high-water {} of {}, refused creations {}, invalid blocked waits {}",
            h.live_slots.load(Ordering::Relaxed), h.peak_slots.load(Ordering::Relaxed), SLOT_COUNT,
            h.node_fresh.min(NODE_COUNT), NODE_COUNT, h.alloc_failures.load(Ordering::Relaxed),
            h.wait_invalid.load(Ordering::Relaxed));
    }
}

fn region() -> Result<&'static Region, Error> {
    init(None)?;
    match REGION.get() {
        Some(Ok(r)) if r.header().poisoned.load(Ordering::Acquire) == 0 => Ok(r),
        Some(Ok(_)) => Err(Error::Init(libc::EIO)),
        Some(Err(e)) => Err(Error::Init(*e)),
        None => unreachable!(),
    }
}

/// next_scan high bit: set once the allocation cursor has wrapped around
/// the table. Until then, slots at or above the cursor have never been
/// handed out (sparse zero pages), so sweep_dead must not read them —
/// scanning the whole table would fault ~640 KB of untouched slot pages
/// into RSS and defeat the lazy-init layout.
const SCAN_WRAPPED: u32 = 0x8000_0000;

fn alloc_slot(r: &Region) -> Option<u32> {
    // Caller holds the lock.
    let header = r.header();
    let cursor = header.next_scan & !SCAN_WRAPPED;
    for i in 0..SLOT_COUNT {
        let index = (cursor + i) % SLOT_COUNT;
        let slot = r.slot_ptr(index);
        unsafe {
            let reclaim = (*slot).state == SLOT_USED && (*slot).closing == CLOSING && (*slot).waiter_head == NIL;
            if reclaim {
                // A closed object whose last waiter has left: its slot becomes free here (the creator takes the object gate
                // before publishing, so a stale fast-path reader is waited out there).
                free_slot_locked(r, slot);
            }
            if (*slot).state == SLOT_FREE {
                st32(&(*slot).waiter_head, NIL);
                let next = (index + 1) % SLOT_COUNT;
                let mut flags = header.next_scan & SCAN_WRAPPED;
                if next == 0 {
                    flags = SCAN_WRAPPED; // cursor wrapped: table fully seen
                }
                (*(r.base as *mut Header)).next_scan = next | flags;
                return Some(((*slot).generation << INDEX_BITS) | index);
            }
        }
    }
    None
}

/// Publish a freshly allocated slot: state fields first, SLOT_USED last
/// (release) so lock-free readers never see a half-initialized object.
unsafe fn slot_publish(r: &Region, handle: u32, obj_type: u32, w0: u64) {
    let slot = r.slot_ptr(handle & INDEX_MASK);
    st64(&(*slot).w0, w0);
    st64(&(*slot).d, 0);
    st32(&(*slot).obj_type, obj_type);
    (*slot).pid = libc::getpid() as u32;
    st32(&(*slot).closing, 0);
    (&*(&(*slot).state as *const u32 as *const AtomicU32)).store(SLOT_USED, Ordering::Release);
    let live = r.header().live_slots.fetch_add(1, Ordering::Relaxed) + 1;
    r.header().peak_slots.fetch_max(live, Ordering::Relaxed);
}

pub fn create_semaphore(count: u32, max: u32) -> Result<u32, Error> {
    if count > max {
        return Err(Error::Invalid);
    }
    let r = region()?;
    let _g = r.lock_guard().map_err(Error::Init)?;
    let handle = alloc_slot_or_sweep(r).ok_or(Error::Init(libc::ENOMEM))?;
    let _object = r.object_guard(handle & INDEX_MASK).map_err(Error::Init)?;
    unsafe { slot_publish(r, handle, TYPE_SEM, pack_sem(count, max)) };
    Ok(handle)
}

pub fn create_mutex(owner: u32, count: u32) -> Result<u32, Error> {
    if (owner == 0) != (count == 0) || count > 0x7fff_ffff {
        return Err(Error::Invalid);
    }
    let r = region()?;
    let _g = r.lock_guard().map_err(Error::Init)?;
    let handle = alloc_slot_or_sweep(r).ok_or(Error::Init(libc::ENOMEM))?;
    let _object = r.object_guard(handle & INDEX_MASK).map_err(Error::Init)?;
    unsafe { slot_publish(r, handle, TYPE_MUTEX, pack_mutex(owner, count, false)) };
    Ok(handle)
}

pub fn create_event(manual: bool, signaled: bool) -> Result<u32, Error> {
    let r = region()?;
    let _g = r.lock_guard().map_err(Error::Init)?;
    let handle = alloc_slot_or_sweep(r).ok_or(Error::Init(libc::ENOMEM))?;
    let _object = r.object_guard(handle & INDEX_MASK).map_err(Error::Init)?;
    unsafe { slot_publish(r, handle, TYPE_EVENT, pack_event(signaled, manual)) };
    Ok(handle)
}

pub fn close(handle: u32) -> bool {
    if handle & INDEX_MASK == 0 {
        return false; // slot 0 is reserved (TYPE_RESERVED)
    }
    let Ok(r) = region() else { return false };
    let Ok(_g) = r.lock_guard() else { return false };
    let Ok(_object) = r.object_guard(handle & INDEX_MASK) else { return false };
    let Some(slot) = r.slot_open(handle) else { return false };
    unsafe {
        // Waits on the object keep it alive and keep waiting, as with the kernel driver where each holds an fd (Windows:
        // closing a handle does not end a wait on its object). Nothing can signal it any more; the slot is reused once the
        // last of them has returned. The count, not the waiter list, decides: a signaler takes a woken waiter off the list
        // before it runs again, and r25 freed the object in that window (the waiter then failed with -EINVAL). A thread's APC
        // alert event is different: an alert node takes no reference, so a wait that only uses it as the alert still ends at once.
        let word = ld32(&(*slot).closing);
        if word & REF_MASK != 0 {
            st32(&(*slot).closing, word | CLOSING);
            // A wait that uses this event only as its APC alert holds no reference and must end now (it re-validates the
            // alert as open and fails); the waits that hold references re-check and keep waiting.
            wake_object(r, handle & INDEX_MASK);
            return true;
        }
        free_slot_locked(r, slot);
    }
    // Only alert waiters (or none): they re-validate and fail, as before.
    wake_object(r, handle & INDEX_MASK);
    true
}

/// USED -> FREE with a new generation (old handles stop resolving). Caller holds the region lock and the object gate.
unsafe fn free_slot_locked(r: &Region, slot: *mut Slot) {
    // Free first (release pairs with slot_publish); a slot has no waiters when this is called.
    st32(&(*slot).closing, 0);
    (&*(&(*slot).state as *const u32 as *const AtomicU32)).store(SLOT_FREE, Ordering::Release);
    st32(&(*slot).generation, ld32(&(*slot).generation).wrapping_add(1));
    r.header().live_slots.fetch_sub(1, Ordering::Relaxed);
}

/// Release a semaphore, leaving it unchanged on overflow.
pub fn sem_release(handle: u32, count: u32) -> Result<u32, Error> {
    stat_bump(7);
    object_operation(handle, TYPE_SEM, |slot| {
        let w = ld64(&slot.w0); let cur = w as u32; let max = (w >> 32) as u32;
        let sum = cur.checked_add(count).filter(|&s| s <= max).ok_or(Error::Overflow)?;
        st64(&slot.w0, pack_sem(sum, max));
        Ok((cur, true))
    })
}
pub fn event_set(handle: u32) -> Result<u32, Error> {
    stat_bump(7);
    object_operation(handle, TYPE_EVENT, |slot| {
        let w = ld64(&slot.w0); let prev = w as u32;
        if prev == 0 { st64(&slot.w0, w | 1); }
        Ok((prev, prev == 0))
    })
}
pub fn event_reset(handle: u32) -> Result<u32, Error> {
    stat_bump(7);
    object_operation(handle, TYPE_EVENT, |slot| {
        let w = ld64(&slot.w0); let prev = w as u32;
        st64(&slot.w0, w & !1u64);
        Ok((prev, false))
    })
}
pub fn event_pulse(handle: u32) -> Result<u32, Error> {
    let r = region()?;
    let _g = r.lock_guard().map_err(Error::Init)?;
    let slot = r.slot_open(handle).filter(|s| unsafe { (**s).obj_type } == TYPE_EVENT).ok_or(Error::Invalid)?;
    discard_dead_waiters(r, handle & INDEX_MASK);
    // An empty registered list needs no union scan or heap allocation.
    // Reset under the operation gate so concurrent SetEvent/fast waits
    // still linearize correctly. Pulse never creates a persistent credit.
    if unsafe { ld32(&(*slot).waiter_head) } == NIL {
        let _object = r.object_guard(handle & INDEX_MASK).map_err(Error::Init)?;
        let word = unsafe { ld64(&(*slot).w0) };
        unsafe { st64(&(*slot).w0, word & !1u64); }
        return Ok(word as u32);
    }
    // Pulse has no persistent signal credit. Collect the affected waiters'
    // object gates, then lock their union in the same ascending order as
    // WAIT_ALL. No temporary signaled bit is exposed to unregistered pollers.
    // Lock only the objects of the affected waits (their union, sorted and unique), not a scan of the whole table.
    let mut needed: Vec<u32> = Vec::new();
    needed.try_reserve(MAX_WAIT_COUNT).map_err(|_| Error::Init(libc::ENOMEM))?;
    needed.push(handle & INDEX_MASK);
    let mut node = unsafe { (*slot).waiter_head };
    while node != NIL {
        let current = unsafe { &*r.node_ptr(node) };
        let mut member = current.wait_word;
        while member != NIL {
            let n = unsafe { &*r.node_ptr(member) };
            if needed.len() == needed.capacity() {
                needed.try_reserve(needed.len()).map_err(|_| Error::Init(libc::ENOMEM))?;
            }
            needed.push(n.handle & INDEX_MASK);
            member = n.waiter_next;
        }
        node = current.obj_next;
    }
    needed.sort_unstable();
    needed.dedup();
    let mut guards = Vec::new();
    guards.try_reserve_exact(needed.len()).map_err(|_| Error::Init(libc::ENOMEM))?;
    for index in needed.iter().copied() {
        #[cfg(all(test,debug_assertions))]
        R10_PULSE_SCANS.fetch_add(1, Ordering::Relaxed);
        guards.push(r.object_guard(index).map_err(Error::Init)?);
    }
    let prev = unsafe { ld64(&(*slot).w0) as u32 };
    let manual = unsafe { ld64(&(*slot).w0) >> 32 != 0 };
    unsafe { st64(&(*slot).w0, ld64(&(*slot).w0) & !1u64); }
    // Completing a waiter detaches all of its nodes, which can also alter
    // this list. Restart after a grant; advance only after an ineligible wait.
    let mut node = unsafe { (*slot).waiter_head };
    while node != NIL {
        // Copy list metadata before detaching any node. Keep a raw head
        // pointer: node_detach mutates this same WaitNode under the region
        // lock, so a shared reference to the whole node must not span it.
        let (head_index, next_node) = unsafe {
            let current = r.node_ptr(node);
            ((*current).wait_word, (*current).obj_next)
        };
        let head = r.node_ptr(head_index);
        let (owner, flags) = unsafe { ((*head).owner, (*head).flags) };
        let mut handles = [0u32; MAX_WAIT_COUNT];
        let mut count = 0usize;
        let mut alert = 0;
        let mut member = head_index;
        while member != NIL {
            let n = unsafe { &*r.node_ptr(member) };
            if n.flags & 2 != 0 { alert = n.handle; }
            else { handles[count] = n.handle; count += 1; }
            member = n.waiter_next;
        }
        let alert_ready = alert != 0 && (alert == handle
            || r.slot(alert).is_some_and(|s| unsafe { ld64(&(*s).w0) as u32 != 0 }));
        // Objects first, the alert only when none could be taken (see wait()).
        let outcome = match try_satisfy_with_pulse(r, &handles[..count], owner, &[0; MAX_WAIT_COUNT],
            flags & 1 != 0, true, Some(handle)) {
            Some(o) => Some(o),
            None if alert_ready => Some(WaitOutcome::Signaled { index: count as u32, owner_dead: false }),
            None => None,
        };
        if let Some(WaitOutcome::Signaled { index, owner_dead }) = outcome {
            let consumed_pulse = if index == count as u32 { alert == handle }
                else { flags & 1 != 0 || handles[index as usize] == handle };
            unsafe { (*head).completion.store(index | ((owner_dead as u32) << 31), Ordering::Release); }
            let mut member = head_index;
            while member != NIL {
                let next = unsafe { (*r.node_ptr(member)).waiter_next };
                // Pulse grants are transient: cancellation must not turn one
                // into a persistent auto-reset-event signal.
                if consumed_pulse && unsafe { (*r.node_ptr(member)).handle == handle } {
                    unsafe { (*r.node_ptr(member)).flags |= 4; }
                }
                node_detach(r, member);
                member = next;
            }
            unsafe {
                if debug_enabled() { (*head).wake_ts.store(monotonic_ns(), Ordering::Relaxed); }
                (*head).seq.fetch_add(1, Ordering::Release);
                futex_wake(&(*head).seq);
            }
            if !manual && consumed_pulse { break; }
            node = unsafe { (*slot).waiter_head };
        } else { node = next_node; }
    }
    Ok(prev)
}
pub fn mutex_unlock(handle: u32, owner: u32) -> Result<u32, Error> {
    stat_bump(7);
    if owner == 0 { return Err(Error::Invalid); }
    object_operation(handle, TYPE_MUTEX, |slot| {
        let w = ld64(&slot.w0); let hi = (w >> 32) as u32;
        let count = hi & 0x7fff_ffff; let ownerdead = hi >> 31 != 0;
        if w as u32 != owner { return Err(Error::Perm); }
        let freed = count == 1;
        st64(&slot.w0, pack_mutex(if freed { 0 } else { owner }, count - 1, ownerdead));
        Ok((count, freed))
    })
}
pub fn mutex_kill(handle: u32, owner: u32) -> Result<(), Error> {
    stat_bump(7);
    if owner == 0 { return Err(Error::Invalid); }
    object_operation(handle, TYPE_MUTEX, |slot| {
        if ld64(&slot.w0) as u32 != owner { return Err(Error::Perm); }
        st64(&slot.w0, pack_mutex(0, 0, true));
        Ok(((), true))
    })
}
pub fn read_sem(handle: u32) -> Result<(u32, u32), Error> {
    object_operation(handle, TYPE_SEM, |slot| {
        let w = ld64(&slot.w0); Ok(((w as u32, (w >> 32) as u32), false))
    })
}
pub fn read_mutex(handle: u32) -> Result<(u32, u32, bool), Error> {
    object_operation(handle, TYPE_MUTEX, |slot| {
        let w = ld64(&slot.w0); let hi = (w >> 32) as u32;
        Ok(((hi & 0x7fff_ffff, w as u32, hi >> 31 != 0), false))
    })
}
pub fn read_event(handle: u32) -> Result<(bool, bool), Error> {
    object_operation(handle, TYPE_EVENT, |slot| {
        let w = ld64(&slot.w0); Ok((((w >> 32) != 0, (w as u32) != 0), false))
    })
}

/// Check whether an object is acquirable ("locked" in kernel terms) without
/// mutating it. Lock-free.
fn is_locked(slot: &Slot, owner: u32, _entry_seq: u64) -> bool {
    let w = ld64(&slot.w0);
    match ld32(&slot.obj_type) {
        TYPE_SEM => w as u32 > 0,
        TYPE_MUTEX => {
            let cur_owner = w as u32;
            let count = ((w >> 32) as u32) & 0x7fff_ffff;
            (cur_owner == 0 || cur_owner == owner) && count < 0x7fff_ffff
        }
        TYPE_EVENT => w as u32 != 0,
        _ => false,
    }
}

/// Attempt to acquire an object (lock-free CAS). Returns Some(owner_dead)
/// on success, None if not acquirable.
fn try_acquire(slot: &Slot, owner: u32, _entry_seq: u64) -> Option<bool> {
    match ld32(&slot.obj_type) {
        TYPE_SEM => loop {
            let w = ld64(&slot.w0);
            let count = w as u32;
            if count == 0 {
                return None;
            }
            let max = (w >> 32) as u32;
            match cas64(&slot.w0, w, pack_sem(count - 1, max)) {
                Ok(_) => return Some(false),
                Err(_) => continue,
            }
        },
        TYPE_MUTEX => loop {
            let w = ld64(&slot.w0);
            let cur_owner = w as u32;
            let hi = (w >> 32) as u32;
            let count = hi & 0x7fff_ffff;
            let ownerdead = hi >> 31 != 0;
            if count >= 0x7fff_ffff {
                return None;
            }
            let (new, od) = if cur_owner == 0 {
                (pack_mutex(owner, count + 1, false), ownerdead)
            } else if cur_owner == owner {
                (pack_mutex(owner, count + 1, ownerdead), false)
            } else {
                return None;
            };
            match cas64(&slot.w0, w, new) {
                Ok(_) => return Some(od),
                Err(_) => continue,
            }
        },
        TYPE_EVENT => {
            let w = ld64(&slot.w0);
            if w as u32 != 0 {
                if (w >> 32) == 0 {
                    // auto-reset: claim it
                    loop {
                        let w = ld64(&slot.w0);
                        if w as u32 == 0 {
                            // Lost the race; fall through to pulse check.
                            break;
                        }
                        match cas64(&slot.w0, w, w & !1u64) {
                            Ok(_) => return Some(false),
                            Err(_) => continue,
                        }
                    }
                } else {
                    return Some(false);
                }
            }
            None
        }
        _ => None,
    }
}

/// Log to stderr, stdout, and $TMPDIR/ntsync_debug.log. The launcher's
/// logcat only captures its own stdout, so the file is the reliable
/// channel for in-game diagnostics.
fn debug_log(msg: &str) {
    use std::io::Write;
    // Raw writes that never panic: wineserver/game processes may close or
    // redirect stdout/stderr, and println!/eprintln! panic on EBADF, which
    // would silently kill the stats thread.
    let mut line = msg.as_bytes().to_vec();
    line.push(b'\n');
    unsafe {
        let _ = libc::write(2, line.as_ptr() as *const _, line.len());
        let _ = libc::write(1, line.as_ptr() as *const _, line.len());
    }
    if let Some(tmp) = std::env::var_os("TMPDIR") {
        let path = std::path::PathBuf::from(tmp).join("ntsync_debug.log");
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = f.write_all(&line);
        }
    }
}

// ---- Instrumentation (NTSYNC_DEBUG) ----
// Cheap per-process relaxed counters; a background thread dumps them every
// 10s so on-device runs can show where the time actually goes.
mod stats {
    use std::sync::atomic::{AtomicU64, Ordering};

    pub const NAMES: [&str; 15] = [
        "wait_calls",        // total wait_any/wait_all calls
        "wait_fast_ok",      // satisfied without any lock
        "wait_blocked",      // actually slept on a futex
        "futex_sleeps",      // futex_wait syscalls
        "lock_acq",          // region lock acquisitions
        "lock_contended",    // lock acquisitions that looped/EOWNERDEAD
        "cas_retries",       // CAS loop retries (contention on object words)
        "signal_ops",        // release/set/unlock/... calls
        "wake_walks",        // times a signal op found waiters and locked
        "nodes_registered",  // waiter-node registrations
        "wake_lat_cnt",      // wakes with a timestamp sample
        "wake_lat_us_sum",   // total wake latency (signal -> waiter running), us
        "wake_lat_us_max",   // worst wake latency, us
        "spin_wins",         // waits satisfied during the pre-sleep spin
        "spin_exhausted",    // spins that still ended in a futex sleep
    ];
    pub static COUNTERS: [AtomicU64; 15] = [
        AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0),
        AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0),
        AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0),
        AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0),
    ];

    #[inline]
    pub fn bump(i: usize) {
        if super::debug_enabled() { COUNTERS[i].fetch_add(1, Ordering::Relaxed); }
    }
    #[inline]
    pub fn add(i: usize, n: u64) {
        if super::debug_enabled() { COUNTERS[i].fetch_add(n, Ordering::Relaxed); }
    }
}
use stats::{add as stat_add, bump as stat_bump};

fn stats_dump_loop() {
    std::thread::spawn(|| {
        let dump = |line: &mut String| {
            line.clear();
            line.push_str(&format!("ntsync stats (pid {}):", std::process::id()));
            for (i, name) in stats::NAMES.iter().enumerate() {
                let v = stats::COUNTERS[i].load(Ordering::Relaxed);
                line.push_str(&format!(" {name}={v}"));
            }
            line.push_str(&format!(" exit_cleanup_retried={} exit_cleanup_skipped={}",
                EXIT_RETRIED.load(Ordering::Relaxed), EXIT_SKIPPED.load(Ordering::Relaxed)));
            debug_log(line);
        };
        let mut line = String::new();
        std::thread::sleep(Duration::from_secs(1));
        dump(&mut line);
        loop {
            std::thread::sleep(Duration::from_secs(10));
            dump(&mut line);
        }
    });
}

fn debug_enabled() -> bool {
    static ONCE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ONCE.get_or_init(|| {
        let on = std::env::var_os("NTSYNC_DEBUG")
            .map(|v| !v.is_empty() && v != "0")
            .unwrap_or(false);
        if on {
            debug_log(&format!("watchdog armed (pid {})", std::process::id()));
            stats_dump_loop();
        }
        on
    })
}

/// Whether to defer SIGUSR1 around region-lock acquisitions (default on).
/// Only needed when the host process installs a SIGUSR1 handler that can
/// suspend threads (Wine's thread-suspend machinery); everything else can
/// set NTSYNC_NO_SIGUSR1_BLOCK=1 to skip the two pthread_sigmask syscalls
/// per lock acquisition.
fn sigusr1_block_enabled() -> bool {
    static ONCE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ONCE.get_or_init(|| {
        std::env::var_os("NTSYNC_NO_SIGUSR1_BLOCK")
            .map(|v| v.is_empty() || v == "0")
            .unwrap_or(true)
    })
}

/// Dump the state of every object involved in a stuck wait, to distinguish
/// a lost wakeup (object signaled while we sleep) from a never-coming signal.
fn debug_dump_stuck_wait(
    r: &Region,
    handles: &[u32],
    owner: u32,
    alert: u32,
    all: bool,
    start: std::time::Instant,
) {
    let type_name = |t: u32| match t {
        TYPE_EVENT => "event",
        TYPE_MUTEX => "mutex",
        TYPE_SEM => "sem",
        _ => "?",
    };
    debug_log(&format!(
        "stuck wait: {:?} elapsed, {} owner={} alert={:#x}",
        start.elapsed(),
        if all { "all" } else { "any" },
        owner,
        alert,
    ));
    if let Ok(_g) = r.lock_guard() {
        for h in handles.iter().copied().chain((alert != 0).then_some(alert)) {
            match r.slot(h) {
                Some(slot) => unsafe {
                    let s = &*slot;
                    let locked = is_locked(s, owner, ld64(&s.d));
                    debug_log(&format!(
                        "  {:#010x} {} state={} w0={:#x} d={} pid={} pid_alive={} gen={} waiters={} now_signaled={}",
                        h, type_name(s.obj_type), s.state, ld64(&s.w0), ld64(&s.d), s.pid,
                        pid_alive(s.pid), s.generation,
                        s.waiter_head != NIL, locked,
                    ));
                },
                None => debug_log(&format!("  {:#010x} <closed/invalid>", h)),
            }
        }
    }
}

/// An absolute wait deadline on the clock the caller gave it (Wine passes CLOCK_MONOTONIC or CLOCK_REALTIME nanoseconds). The
/// remaining time is always measured against that clock, so time spent before the first sleep (or a preemption between reading
/// the arguments and sleeping) never lengthens the wait, and a REALTIME clock step moves the deadline as it does in the kernel.
#[derive(Clone, Copy, Debug)]
pub struct Deadline {
    clock: libc::clockid_t,
    abs_ns: u64,
}

impl Deadline {
    pub fn at(clock: libc::clockid_t, abs_ns: u64) -> Deadline {
        Deadline { clock, abs_ns }
    }
    /// The deadline `d` from now on CLOCK_MONOTONIC.
    pub fn after(d: Duration) -> Deadline {
        let now = clock_ns(libc::CLOCK_MONOTONIC);
        Deadline { clock: libc::CLOCK_MONOTONIC, abs_ns: now.saturating_add(d.as_nanos().min(u64::MAX as u128) as u64) }
    }
    #[cfg(test)]
    fn remaining(&self) -> Duration {
        Duration::from_nanos(self.abs_ns.saturating_sub(clock_ns(self.clock)))
    }
    fn expired(&self) -> bool {
        clock_ns(self.clock) >= self.abs_ns
    }
}

fn clock_ns(clock: libc::clockid_t) -> u64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(clock, &mut ts) };
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

pub fn wait_any(handles: &[u32], owner: u32, timeout: Option<Duration>, alert: u32) -> WaitOutcome {
    wait(handles, owner, timeout.map(Deadline::after), false, alert)
}

pub fn wait_all(handles: &[u32], owner: u32, timeout: Option<Duration>, alert: u32) -> WaitOutcome {
    wait(handles, owner, timeout.map(Deadline::after), true, alert)
}

pub fn wait_any_until(handles: &[u32], owner: u32, deadline: Option<Deadline>, alert: u32) -> WaitOutcome {
    wait(handles, owner, deadline, false, alert)
}

pub fn wait_all_until(handles: &[u32], owner: u32, deadline: Option<Deadline>, alert: u32) -> WaitOutcome {
    wait(handles, owner, deadline, true, alert)
}

/// Fixed-capacity stack set of waiter node indices: up to MAX_WAIT_COUNT
/// object nodes plus one alert node. Replaces a per-wait heap Vec.
struct NodeSet {
    idx: [u32; MAX_WAIT_COUNT + 1],
    len: usize,
}

impl NodeSet {
    fn new() -> Self {
        NodeSet {
            idx: [0; MAX_WAIT_COUNT + 1],
            len: 0,
        }
    }
    fn push(&mut self, v: u32) {
        self.idx[self.len] = v;
        self.len += 1;
    }
    fn is_empty(&self) -> bool {
        self.len == 0
    }
    fn clear(&mut self) {
        self.len = 0;
    }
    fn iter(&self) -> std::slice::Iter<'_, u32> {
        self.idx[..self.len].iter()
    }
}

impl std::ops::Index<usize> for NodeSet {
    type Output = u32;
    fn index(&self, i: usize) -> &u32 {
        &self.idx[i]
    }
}

/// Detach and free all nodes of a waiter (caller holds the lock).
fn waiter_cleanup(r: &Region, nodes: &mut NodeSet) {
    for &idx in nodes.iter() {
        node_detach(r, idx);
    }
    for &idx in nodes.iter() {
        node_free(r, idx);
    }
    nodes.clear();
}

/// Satisfy under affected object locks. Lock indices are sorted for WAIT_ALL
/// regardless of caller order; disjoint operations never share this gate.
fn try_satisfy(r: &Region, handles: &[u32], owner: u32,
    entry_seqs: &[u64; MAX_WAIT_COUNT], all: bool, metadata_locked: bool) -> Option<WaitOutcome> {
    try_satisfy_with_pulse(r, handles, owner, entry_seqs, all, metadata_locked, None)
}

// Some(pulse) is called only while event_pulse owns the region lock AND
// every affected object gate. It acquires the whole wait at pulse time.
fn try_satisfy_with_pulse(r: &Region, handles: &[u32], owner: u32,
    entry_seqs: &[u64; MAX_WAIT_COUNT], all: bool, metadata_locked: bool,
    pulse: Option<u32>) -> Option<WaitOutcome> {
    if r.header().poisoned.load(Ordering::Acquire) != 0 { return Some(WaitOutcome::Invalid); }
    if all {
        // A read-only failed poll can linearize at any unavailable object.
        // Never acquire a subset just to discover that the group cannot win.
        // A possible success is still revalidated under every operation gate.
        for (i,h) in handles.iter().enumerate() {
            let Some(slot) = r.slot(*h) else { return Some(WaitOutcome::Invalid); };
            if pulse != Some(*h) && !is_locked(unsafe { &*slot }, owner, entry_seqs[i]) { return None; }
        }
    }
    if !all {
        let mut signals = None;
        for (i, h) in handles.iter().enumerate() {
            // A failed read-only probe can linearize here. Acquirable objects
            // are still revalidated and consumed under their operation gate.
            let Some(slot) = r.slot(*h) else { return Some(WaitOutcome::Invalid); };
            if pulse != Some(*h) && !is_locked(unsafe { &*slot }, owner, entry_seqs[i]) {
                continue;
            }
            if !metadata_locked && signals.is_none() {
                signals = Some(SignalGuard::new());
            }
            let _object = if pulse.is_none() {
                match r.object_guard(h & INDEX_MASK) { Ok(g) => Some(g), Err(_) => return Some(WaitOutcome::Invalid) }
            } else { None };
            let Some(slot) = r.slot(*h) else { return Some(WaitOutcome::Invalid); };
            if pulse == Some(*h) { return Some(WaitOutcome::Signaled { index: i as u32, owner_dead: false }); }
            if let Some(owner_dead) = try_acquire(unsafe { &*slot }, owner, entry_seqs[i]) {
                return Some(WaitOutcome::Signaled { index: i as u32, owner_dead });
            }
        }
        return None;
    }
    let _signals = (!metadata_locked).then(SignalGuard::new);
    let mut order = [0u32; MAX_WAIT_COUNT];
    for (i, h) in handles.iter().enumerate() { order[i] = h & INDEX_MASK; }
    order[..handles.len()].sort_unstable();
    let mut guards: [Option<ObjectGuard<'_>>; MAX_WAIT_COUNT] = std::array::from_fn(|_| None);
    let mut last = NIL;
    for (i, index) in order[..handles.len()].iter().copied().enumerate() {
        // Different generations of one slot must fail validation, not lock twice.
        if index == last { continue; }
        if pulse.is_none() {
            guards[i] = match r.object_guard(index) { Ok(g) => Some(g), Err(_) => return Some(WaitOutcome::Invalid) };
        }
        last = index;
    }
    // Validate after taking all gates: close/reuse cannot change a generation.
    for h in handles {
        if r.slot(*h).is_none() { return Some(WaitOutcome::Invalid); }
    }
    if !handles.iter().enumerate().all(|(i,h)| pulse == Some(*h)
        || is_locked(unsafe { &*r.slot_ptr(h & INDEX_MASK) }, owner, entry_seqs[i])) {
        return None;
    }
    let mut owner_dead = false;
    for (i,h) in handles.iter().enumerate() {
        if pulse == Some(*h) { continue; }
        // All conditions were checked while every affected lock was held.
        let Some(od) = try_acquire(unsafe { &*r.slot_ptr(h & INDEX_MASK) }, owner, entry_seqs[i]) else {
            // Defensive: never expose a partial commit if an internal invariant fails.
            r.header().poisoned.store(1, Ordering::Release);
            return Some(WaitOutcome::Invalid);
        };
        owner_dead |= od;
    }
    Some(WaitOutcome::Signaled { index: 0, owner_dead })
}

fn wait(handles: &[u32], owner: u32, deadline: Option<Deadline>, all: bool, alert: u32) -> WaitOutcome {
    if owner == 0 || handles.is_empty() || handles.len() > MAX_WAIT_COUNT {
        return WaitOutcome::Invalid;
    }
    // WaitAll may not name an object twice (kernel ntsync: -EINVAL), and a mutex or semaphore taken
    // "twice" in one atomic grab would be nonsense. WaitAny may: the first signaled entry wins, and
    // Windows allows the same handle (or two handles of one object) more than once. Checked without allocating.
    if all {
        for (i, h) in handles.iter().enumerate() {
            if handles[..i].contains(h) {
                return WaitOutcome::Invalid;
            }
        }
    }
    let Ok(r) = region() else {
        return WaitOutcome::Invalid;
    };
    stat_bump(0); // wait_calls

    let debug = debug_enabled();
    let start = debug.then(std::time::Instant::now);
    let mut last_dump = start;

    let mut entry_seqs = [0u64; MAX_WAIT_COUNT];
    let n_nodes = handles.len() + (alert != 0) as usize;
    let mut nodes = NodeSet::new();

    // ---- Fast path: validate, then acquire under affected object gates. ----
    // A new wait needs open handles: a closed object only stays alive for the waiters it already had.
    let fast_valid = handles.iter().enumerate().all(|(i, h)| {
        match r.slot_open(*h) {
            Some(slot) => unsafe {
                let s = &*slot;
                let kind = ld32(&s.obj_type);
                entry_seqs[i] = if kind == TYPE_EVENT { ld64(&s.d) } else { 0 };
                matches!(kind, TYPE_SEM | TYPE_MUTEX | TYPE_EVENT)
            },
            None => false,
        }
    }) && (alert == 0
        || matches!(r.slot_open(alert), Some(s) if unsafe { ld32(&(*s).obj_type) } == TYPE_EVENT));
    if fast_valid {
        // Objects first, the alert only when none could be taken: kernel ntsync, Wine's server and NT all
        // satisfy a wait that can be satisfied before they let a pending APC end it (the alert event is
        // only tested, never acquired).
        if let Some(outcome) = try_satisfy(r, handles, owner, &entry_seqs, all, false) {
            stat_bump(1); // wait_fast_ok
            return outcome;
        }
        if alert != 0 {
            let Some(slot) = r.slot(alert) else { return WaitOutcome::Invalid; };
            let slot = unsafe { &*slot };
            if ld64(&slot.w0) as u32 != 0 {
                return WaitOutcome::Signaled {
                    index: handles.len() as u32,
                    owner_dead: false,
                };
            }
        }
        // A deadline already behind us is a poll (Wine's zero timeout, or an absolute time that has passed).
        if deadline.is_some_and(|d| d.expired()) {
            return WaitOutcome::Timeout;
        }
    }
    // Fall through to the blocking path; the locked section below
    // re-validates authoritatively (a close racing the fast path fails it).

    loop {
        // Take the region lock for the validate/register cycle. Registration
        // and signalers' wake walks are mutually exclusive, and we re-check
        // acquirability under the lock before sleeping, so no wakeup is lost.
        let Ok(_g) = r.lock_guard() else {
            if let Ok(g) = r.lock_guard() {
                waiter_cleanup(r, &mut nodes);
                drop(g);
            }
            return WaitOutcome::Invalid;
        };

        if !nodes.is_empty() {
            let completion = unsafe { (*r.node_ptr(nodes[0])).completion.load(Ordering::Acquire) };
            if completion != NIL {
                waiter_cleanup(r, &mut nodes);
                return WaitOutcome::Signaled { index: completion & 0x7fff_ffff,
                    owner_dead: completion >> 31 != 0 };
            }
        }

        // Authoritative validation (also catches objects closed mid-wait).
        // Before registration (first pass) the handles must be open; once registered, an object closed meanwhile stays valid
        // for this waiter (it keeps waiting, as with the kernel driver).
        let registered = !nodes.is_empty();
        let resolve = |h: u32| if registered { r.slot(h) } else { r.slot_open(h) };
        let mut valid = true;
        for (i, h) in handles.iter().enumerate() {
            match resolve(*h) {
                Some(slot) => unsafe {
                    let s = &*slot;
                    if !matches!(s.obj_type, TYPE_SEM | TYPE_MUTEX | TYPE_EVENT) {
                        valid = false;
                        break;
                    }
                    if nodes.is_empty() && s.obj_type == TYPE_EVENT {
                        entry_seqs[i] = ld64(&s.d);
                    }
                },
                None => {
                    valid = false;
                    break;
                }
            }
        }
        if valid && alert != 0 {
            // The alert takes no reference, so a closed alert event ends the wait even while other waits keep it alive.
            valid = matches!(r.slot_open(alert), Some(s) if unsafe { (*s).obj_type } == TYPE_EVENT);
        }
        if !valid {
            if registered {
                r.header().wait_invalid.fetch_add(1, Ordering::Relaxed);
            }
            waiter_cleanup(r, &mut nodes);
            return WaitOutcome::Invalid;
        }

        // Allocate nodes on first block, then (re)register one node per
        // object *before* the acquire attempt below. Signalers change object
        // state under an object gate and only afterwards walk waiter lists,
        // so registration must precede our check: a state change that landed
        // before the check is seen by it, and one that lands after finds our
        // nodes on the list (registration and wake walks are mutually
        // exclusive under the lock). Check-then-register would race the CAS
        // and lose the wakeup.
        if nodes.is_empty() {
            for _ in 0..n_nodes {
                match node_alloc(r) {
                    Some(idx) => nodes.push(idx),
                    None => {
                        waiter_cleanup(r, &mut nodes);
                        return WaitOutcome::Invalid; // waiter pool exhausted
                    }
                }
            }
            for (i, &node) in nodes.iter().enumerate() {
                unsafe {
                    let n = &mut *r.node_ptr(node);
                    n.handle = if i < handles.len() { handles[i] } else { alert };
                    n.owner = owner;
                    n.flags = (all as u32) | if i == handles.len() { 2 } else { 0 };
                    n.waiter_next = if i + 1 < n_nodes { nodes[i + 1] } else { NIL };
                    if i < handles.len() {
                        let slot = r.slot_ptr(handles[i] & INDEX_MASK);
                        let word = ld32(&(*slot).closing);
                        if word & REF_MASK != REF_MASK {
                            st32(&(*slot).closing, word + 1);
                            n.flags |= NODE_REF;
                        }
                    }
                }
            }
        }
        let head = nodes[0];
        for (i, h) in handles.iter().enumerate() {
            if unsafe { (*r.node_ptr(nodes[i])).registered } == 0 {
                node_register(r, nodes[i], h & INDEX_MASK, head);
            }
        }
        if alert != 0 {
            let an = nodes[handles.len()];
            if unsafe { (*r.node_ptr(an)).registered } == 0 {
                node_register(r, an, alert & INDEX_MASK, head);
            }
        }

        // Make the registration globally visible before checking object
        // state: an Acquire load alone lets the registration store linger in
        // the store buffer (StoreLoad reordering), so a signaler's lock-free
        // waiter_head read could still see NIL after its state CAS. The
        // fence pairs with the signaler's CAS-then-read order: if our check
        // observes pre-CAS state, the signaler's read is guaranteed to
        // observe our registration.
        std::sync::atomic::fence(Ordering::SeqCst);

        // Authoritative acquire attempt.
        if let Some(outcome) = try_satisfy(r, handles, owner, &entry_seqs, all, true) {
            waiter_cleanup(r, &mut nodes);
            return outcome;
        }

        // Alertable wait: nothing could be taken, so the alert event ends the wait if it is signaled.
        // It is only tested, never acquired (wineserver resets it when the APC queue empties) - kernel
        // ntsync_wait_args.alert contract. Tested after registration, so a signal landing now is seen
        // here or finds our nodes on the alert's list.
        if alert != 0 {
            let slot = r.slot(alert).expect("validated above");
            if ld64(unsafe { &(*slot).w0 }) as u32 != 0 {
                waiter_cleanup(r, &mut nodes);
                return WaitOutcome::Signaled {
                    index: handles.len() as u32,
                    owner_dead: false,
                };
            }
        }

        // Timeout check before going to sleep.
        match deadline {
            Some(dl) => {
                if dl.expired() {
                    waiter_cleanup(r, &mut nodes);
                    return WaitOutcome::Timeout;
                }
            }
            None => {},
        };

        stat_bump(2); // wait_blocked
        stat_add(9, n_nodes as u64); // nodes_registered
        // Sample the head seq under the lock: any signaler that detaches us
        // after this point bumps the seq, so futex_wait below either blocks
        // or fails immediately with EAGAIN - never a missed wakeup.
        let seq = unsafe { (*r.node_ptr(head)).seq.load(Ordering::Acquire) };
        drop(_g);

        // Optional bounded spin (default off). Registered waits must inspect
        // completion under metadata before acquiring, because a signaler can
        // already have consumed a member on their behalf. Pulse event waits
        // retain their no-spin path.
        let mut spin = spin_budget();
        if spin > 0 && (alert != 0 || handles.iter().any(|h|
            r.slot(*h).is_some_and(|s| unsafe { ld32(&(*s).obj_type) == TYPE_EVENT }))) {
            spin = 0;
        }
        if spin > 0 {
            // Reaching the end of the loop without winning means the
            // spin did not pay off - penalize below. The detached-mid-
            // spin break is neutral: a signaler DID target us, we just
            // lost the CAS race for the state; that is a timing race,
            // not evidence that spinning is useless for this thread.
            let mut neutral = false;
            for _ in 0..spin {
                #[cfg(test)]
                if let Some(callback) = R10_SPIN_HOOK_TEST.with(|h| h.borrow_mut().take()) { callback(); }

                if alert != 0 {
                    let Some(s) = r.slot(alert) else {
                        // Close can race the lock-free spin phase. Return a
                        // normal invalid wait instead of panicking across FFI.
                        if let Ok(g) = r.lock_guard() { waiter_cleanup(r, &mut nodes); drop(g); }
                        return WaitOutcome::Invalid;
                    };
                    if ld64(unsafe { &(*s).w0 }) as u32 != 0 {
                        stat_bump(13); // spin_wins (alert)
                        spin_credit_reward();
                        if let Ok(g) = r.lock_guard() {
                            waiter_cleanup(r, &mut nodes);
                            drop(g);
                        }
                        return WaitOutcome::Signaled {
                            index: handles.len() as u32,
                            owner_dead: false,
                        };
                    }
                }
                // Read-only peek first: a failed try_satisfy still CASes
                // the object words, and N spinners CASing one word turns
                // the cache line into a ping-pong that slows the very
                // owner we are waiting for. Only attempt the real acquire
                // when the load-only check says it could succeed (any
                // object for WAIT_ANY, all for WAIT_ALL).
                let peek = handles.iter().enumerate().any(|(i, h)| {
                    is_locked(unsafe { &*r.slot_ptr(h & INDEX_MASK) }, owner, entry_seqs[i])
                }) && (!all || handles.iter().enumerate().all(|(i, h)| {
                    is_locked(unsafe { &*r.slot_ptr(h & INDEX_MASK) }, owner, entry_seqs[i])
                }));
                if peek {
                    // A signaler may already have consumed another member on
                    // this waiter's behalf. Serialize completion vs. spin acquire.
                    if let Ok(g) = r.lock_guard() {
                        let completion = unsafe { (*r.node_ptr(head)).completion.load(Ordering::Acquire) };
                        let outcome = if completion != NIL {
                            Some(WaitOutcome::Signaled { index: completion & 0x7fff_ffff,
                                owner_dead: completion >> 31 != 0 })
                        } else { try_satisfy(r, handles, owner, &entry_seqs, all, true) };
                        if let Some(outcome) = outcome {
                            stat_bump(13);
                            waiter_cleanup(r, &mut nodes);
                            drop(g);
                            spin_credit_reward();
                            return outcome;
                        }
                    }
                }
                // A signaler detached us mid-spin: the wake was directed at
                // us but the state went to someone else (or the object was
                // closed). Stop spinning; futex_wait returns EAGAIN and the
                // locked section re-registers or fails validation.
                if unsafe { ld32(&(*r.node_ptr(head)).registered) } == 0 {
                    neutral = true;
                    break;
                }
                std::hint::spin_loop();
            }
            if !neutral {
                stat_bump(14); // spin_exhausted
                spin_credit_penalize();
            }
        }

        // Going to sleep: a thread with slack, so this is the right place
        // for the rate-limited dead-process sweep. Deliberately NOT hooked
        // into the lock-free signal ops, so a scan never lands on a
        // latency-critical signaler (e.g. a render thread's SetEvent).
        #[cfg(test)]
        {
            let delay = PRE_SLEEP_DELAY_TEST.replace(0);
            if delay != 0 { std::thread::sleep(Duration::from_millis(delay)); }
        }
        auto_sweep_dead(r);

        if deadline.is_some_and(|dl| dl.expired()) {
            continue;
        }

        // The sleep ends at the absolute deadline on its own clock (r29). With NTSYNC_DEBUG=1 each sleep is capped at 5 s so a
        // stuck wait periodically dumps the object states to stderr.
        let slice = if debug {
            Some(match deadline {
                Some(dl) => Deadline { clock: dl.clock, abs_ns: dl.abs_ns.min(clock_ns(dl.clock).saturating_add(5_000_000_000)) },
                None => Deadline::after(Duration::from_secs(5)),
            })
        } else {
            deadline
        };

        stat_bump(3); // futex_sleeps
        let ret = futex_wait(unsafe { &(*r.node_ptr(head)).seq }, seq, slice);
        if ret == 0 && debug {
            // Woken by a signaler: measure signal-to-running latency.
            let head_node = unsafe { &*r.node_ptr(head) };
            let ts = head_node.wake_ts.load(Ordering::Relaxed);
            if ts != 0 {
                let lat_us = monotonic_ns().saturating_sub(ts) / 1000;
                stat_bump(10); // wake_lat_cnt
                stat_add(11, lat_us); // wake_lat_us_sum
                stats::COUNTERS[12].fetch_max(lat_us, Ordering::Relaxed); // wake_lat_us_max
            }
        }
        match ret {
            0 | libc::EAGAIN => {}
            x if x == libc::ETIMEDOUT => {
                // A signal may have landed just as the deadline expired; do
                // not decide here. Fall through to detach + loop, where the
                // locked section re-attempts the acquire authoritatively
                // before the deadline check returns Timeout.
                //
                // Also a good moment for an opportunistic sweep: a wait that
                // keeps timing out may be blocked on an object whose creator
                // process died (the kernel driver reaps those via fd release;
                // userspace must scan). Rate-limited region-wide.
                auto_sweep_dead(r);
                if debug && deadline.is_none_or(|dl| !dl.expired()) && last_dump.is_some_and(|t| t.elapsed() >= Duration::from_secs(5)) {
                    debug_dump_stuck_wait(r, handles, owner, alert, all, start.unwrap());
                    last_dump = Some(std::time::Instant::now());
                }
            }
            _ => {} // EINTR and friends: loop and re-check
        }
        // Detach our nodes that are still registered (timeout/EINTR wake);
        // signalers already detached the ones they woke. Peek lock-free
        // first: on the normal signal-wake path nothing is registered, so
        // we skip the robust-mutex + sigmask round entirely. A stale `1`
        // read just means a harmless extra lock; a detached node's `0` is
        // stable (only we re-register our own nodes, after this point).
        let any_registered = nodes
            .iter()
            .any(|&idx| unsafe { ld32(&(*r.node_ptr(idx)).registered) } != 0);
        if any_registered {
            if let Ok(_g) = r.lock_guard() {
                for &idx in nodes.iter() {
                    node_detach(r, idx);
                }
            }
        }
    }
}

/// Free all objects created by processes that no longer exist, and purge
/// waiter nodes left behind by dead waiters. Userspace has no fd-close-on-
/// death hook, so callers (e.g. a launcher or wineserver replacement)
/// should run this after a process exits.
fn pid_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    let ret = unsafe { libc::kill(pid as i32, 0) };
    !(ret != 0 && errno() == libc::ESRCH)
}

/// Body of sweep_dead with the region lock already held. Returns the
/// number of objects freed.
fn sweep_dead_locked(r: &Region) -> u32 {
    sweep_dead_locked_checks(r, pid_alive, thread_alive)
}
#[cfg(test)]
fn sweep_dead_locked_check(r: &Region, check: impl FnMut(u32) -> bool) -> u32 {
    sweep_dead_locked_checks(r, check, |_,_| true)
}
fn sweep_dead_locked_checks(r: &Region, check: impl FnMut(u32) -> bool,
    check_thread: impl FnMut(u32,u32) -> bool) -> u32 {
    sweep_dead_locked_range(r, check, check_thread, 0..NODE_COUNT, 0..SLOT_COUNT)
}

/// The sweep restricted to node and slot index ranges (clamped to what was ever handed out).
fn sweep_dead_locked_range(r: &Region, mut check: impl FnMut(u32) -> bool,
    mut check_thread: impl FnMut(u32,u32) -> bool, nodes: std::ops::Range<u32>, slots: std::ops::Range<u32>) -> u32 {
    // Cache only within this locked sweep; never retain stale liveness across
    // passes. EPERM and other non-ESRCH results still count as alive.
    let mut live_threads = std::collections::HashMap::new();
    let mut alive = std::collections::HashMap::new();
    let mut pid_alive = |pid| *alive.entry(pid).or_insert_with(|| check(pid));
    // Purge nodes of dead waiters first so freed objects have empty lists.
    // Only nodes below node_fresh were ever handed out; the rest have
    // never been touched (registered == 0 by virtue of sparse zero pages).
    let node_hi = r.header().node_fresh.min(NODE_COUNT).min(nodes.end);
    for i in nodes.start..node_hi {
        let node = r.node_ptr(i);
        unsafe {
            if (*node).pid != 0 && (!pid_alive((*node).pid) ||
                !*live_threads.entry(((*node).pid,(*node).tid)).or_insert_with(|| check_thread((*node).pid,(*node).tid))) {
                // Includes allocated nodes detached by a signal before death.
                // The dead process cannot perform its normal waiter cleanup.
                // A slice limits the search, never a waiter group: freeing only the nodes inside the slice left the rest of the
                // chain linked from a freed node, and a later discard walked into a node already reused by a live waiter (r26).
                let head = (*node).wait_word;
                if head != NIL {
                    discard_waiter(r, head);
                } else {
                    node_detach(r, i);
                    node_free(r, i);
                }
            }
        }
    }
    let mut freed = 0;
    // Only slots below the allocation cursor were ever handed out — until
    // the cursor wraps (SCAN_WRAPPED), higher slots are sparse zero pages
    // that must not be faulted in just to discover pid == 0.
    let header = r.header();
    let slot_hi = if header.next_scan & SCAN_WRAPPED != 0 {
        SLOT_COUNT
    } else {
        header.next_scan
    }.min(slots.end);
    for index in slots.start..slot_hi {
        let slot = r.slot_ptr(index);
        unsafe {
            if (*slot).state == SLOT_USED && (*slot).pid != 0 && !pid_alive((*slot).pid) {
                let Ok(_object) = r.object_guard(index) else { return freed; };
                wake_object(r, index);
                free_slot_locked(r, slot);
                freed += 1;
            }
        }
    }
    freed
}

/// How often the library runs sweep_dead() opportunistically on its own.
/// The kernel driver reaps a dead process's objects via fd release;
/// userspace has no such hook, so without this a crashed process's
/// objects linger until some caller runs ntsync_sweep_dead().
/// NTSYNC_SWEEP_INTERVAL_SEC overrides the 30 s default; 0 disables.
fn sweep_interval_ns() -> u64 {
    static INTERVAL: OnceLock<u64> = OnceLock::new();
    *INTERVAL.get_or_init(|| {
        let secs = std::env::var("NTSYNC_SWEEP_INTERVAL_SEC")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(30);
        secs * 1_000_000_000
    })
}

/// Max spin iterations per blocking wait. NTSYNC_SPIN_ITERS sets it;
/// default 0 (disabled) so behavior is unchanged unless explicitly
/// opted in. Capped at 100k to bound silly values. Each iteration is a
/// handful of atomic loads (~10-20ns), so e.g. 200 iters costs ~2-4us
/// of CPU in exchange for every avoided sleep/wake pair.
fn spin_max_iters() -> u32 {
    static ITERS: OnceLock<u32> = OnceLock::new();
    *ITERS.get_or_init(|| {
        std::env::var("NTSYNC_SPIN_ITERS")
            .ok()
            .and_then(|v| v.parse::<u32>().ok())
            .unwrap_or(0)
            .min(100_000)
    })
}

// Adaptive spin credit (per thread): spinning pays off only for waiters
// whose waits are usually satisfied quickly (mutex handoffs); threads
// whose waits routinely time out (e.g. a 1ms vsync poll) would burn CPU
// for nothing. Track outcomes like JVM adaptive spinning: a win adds
// credit, an exhausted spin costs double, and only threads in credit
// spin at all. Starts at 1 so every thread gets one chance to prove it
// wins. Purely thread-local: ~1ns overhead.
thread_local! {
    static SPIN_CREDIT: std::cell::Cell<i32> = const { std::cell::Cell::new(1) };
    static SPIN_PROBE: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// Iterations this thread may spin now. Threads in credit get the full
/// env-capped budget. Threads out of credit only probe every 8th blocked
/// wait with a small budget, so a workload that changed back to short
/// waits can re-earn credit without always-spin losers (e.g. a 1ms
/// vsync poll) paying a spin tax on every wait forever.
fn spin_budget() -> u32 {
    #[cfg(test)]
    if let Some(n) = R10_SPIN_BUDGET_TEST.get() { return n; }

    let max = spin_max_iters();
    if max == 0 {
        return 0;
    }
    SPIN_CREDIT.with(|c| {
        if c.get() > 0 {
            max
        } else {
            SPIN_PROBE.with(|p| {
                let n = p.get().wrapping_add(1);
                p.set(n);
                if n % 8 == 0 { probe_budget(max) } else { 0 }
            })
        }
    })
}

/// The occasional probe of a thread out of credit: an eighth of the cap, at least 8 and at most 64 iterations, and never
/// more than the cap itself (r30, OPT13-06: a cap of 1..7 used to probe with 8).
fn probe_budget(max: u32) -> u32 {
    (max / 8).clamp(8, 64).min(max)
}

fn spin_credit_reward() {
    SPIN_CREDIT.with(|c| c.set((c.get() + 1).min(16)));
}

fn spin_credit_penalize() {
    SPIN_CREDIT.with(|c| c.set((c.get() - 2).max(-8)));
}

/// Rate-limited, region-wide opportunistic sweep. Called only from wait
/// paths (before sleeping, and on timeout): threads with slack, so a
/// scan never lands on a latency-critical signaler. Costs ~1ns in the
/// common case: a thread-local counter gates the (vDSO, ~20ns) clock
/// read to every 256th call, and the region-wide interval check is then
/// one relaxed atomic load. The region lock serializes concurrent
/// sweepers; sweep_ns is re-checked under it.
thread_local! {
    static SWEEP_CALLS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

fn auto_sweep_dead(r: &Region) {
    // Thread-local counter: ~1ns per call, no cacheline sharing. Sweep
    // promptness is unaffected: under any real load 256 signal ops pass
    // in microseconds, and idle threads don't need to sweep.
    let n = SWEEP_CALLS.get();
    SWEEP_CALLS.set(n.wrapping_add(1));
    if n & 0xFF != 0 {
        return;
    }
    let interval = sweep_interval_ns();
    if interval == 0 {
        return;
    }
    let cell = unsafe { &*(&r.header().sweep_ns as *const u64 as *const AtomicU64) };
    let now = monotonic_ns();
    if now.saturating_sub(cell.load(Ordering::Relaxed)) < interval {
        return;
    }
    if let Ok(_g) = r.lock_guard() {
        let now = monotonic_ns();
        if now.saturating_sub(cell.load(Ordering::Relaxed)) < interval {
            return; // another process just swept
        }
        // A bounded slice per pass (the region lock is held meanwhile); successive passes walk the whole table.
        // A full table and the explicit sweep_dead still sweep everything at once.
        static NODE_CURSOR: AtomicU32 = AtomicU32::new(0);
        static SLOT_CURSOR: AtomicU32 = AtomicU32::new(0);
        const NODE_CHUNK: u32 = 2048;
        const SLOT_CHUNK: u32 = 8192;
        let header = r.header();
        let node_hi = header.node_fresh.min(NODE_COUNT);
        let slot_hi = if header.next_scan & SCAN_WRAPPED != 0 { SLOT_COUNT } else { header.next_scan & !SCAN_WRAPPED };
        let slice = |cursor: &AtomicU32, hi: u32, chunk: u32| {
            if hi <= chunk {
                return 0..hi;
            }
            let start = cursor.load(Ordering::Relaxed) % hi;
            let end = (start + chunk).min(hi);
            cursor.store(if end == hi { 0 } else { end }, Ordering::Relaxed);
            start..end
        };
        let nodes = slice(&NODE_CURSOR, node_hi, NODE_CHUNK);
        let slots = slice(&SLOT_CURSOR, slot_hi, SLOT_CHUNK);
        let freed = sweep_dead_locked_range(r, pid_alive, thread_alive, nodes, slots);
        cell.store(now, Ordering::Release);
        if freed > 0 && debug_enabled() {
            debug_log(&format!("auto-sweep freed {freed} dead objects"));
        }
    }
}

/// alloc_slot, but on a full table reap dead processes' objects first and
/// retry once. Caller holds the region lock.
fn alloc_slot_or_sweep(r: &Region) -> Option<u32> {
    let handle = alloc_slot(r).or_else(|| {
        if sweep_dead_locked(r) > 0 {
            alloc_slot(r)
        } else {
            None
        }
    });
    if handle.is_none() {
        let n = r.header().alloc_failures.fetch_add(1, Ordering::Relaxed) + 1;
        if n == 1 || n % 1000 == 0 {
            eprintln!("ntsync: object table full ({} objects), creation refused (count {})", SLOT_COUNT, n);
        }
    }
    handle
}

pub fn sweep_dead() -> Result<u32, Error> {
    let r = region()?;
    let _g = r.lock_guard().map_err(Error::Init)?;
    Ok(sweep_dead_locked(r))
}

/// Tests: force the auto-sweep rate limiter to consider the interval
/// elapsed, so the next signal op sweeps regardless of wall time.
#[cfg(test)]
pub(crate) fn test_reset_sweep_clock() {
    SWEEP_CALLS.set(0);
    if let Ok(r) = region() {
        let cell = unsafe { &*(&r.header().sweep_ns as *const u64 as *const AtomicU64) };
        cell.store(0, Ordering::Release);
    }
}

#[cfg(test)]
mod region_failure_tests {
    use super::*;
    #[test]
    fn interrupted_metadata_operation_fails_closed_instead_of_hanging() { unsafe {
        let path = std::env::temp_dir().join(format!("wow-ntsync-poison-{}-{}.shm", std::process::id(), monotonic_ns()));
        let r = open_and_map(path.to_str().unwrap()).unwrap();
        let mut pipe = [0; 2]; assert_eq!(libc::pipe(pipe.as_mut_ptr()), 0);
        let pid = libc::fork(); assert!(pid >= 0);
        if pid == 0 {
            libc::close(pipe[0]);
            let _g = r.lock_guard().unwrap();
            libc::write(pipe[1], b"1".as_ptr().cast(), 1);
            loop { libc::pause(); }
        }
        libc::close(pipe[1]); let mut byte = 0u8;
        assert_eq!(libc::read(pipe[0], (&mut byte as *mut u8).cast(), 1), 1);
        libc::kill(pid, libc::SIGKILL); let mut status = 0; libc::waitpid(pid, &mut status, 0);
        let start = std::time::Instant::now();
        assert!(matches!(r.lock_guard(), Err(libc::EIO)));
        assert!(start.elapsed() < Duration::from_secs(2));
        assert_eq!(r.header().poisoned.load(Ordering::Acquire), 1);
        assert!(matches!(r.lock_guard(), Err(libc::EIO)));
        libc::close(pipe[0]); libc::munmap(r.base.cast(), region_size());
        let mapped = path.with_file_name(format!("{}.v{}.shm", path.file_stem().unwrap().to_str().unwrap(), VERSION));
        std::fs::remove_file(mapped).unwrap();
    } }
}

#[cfg(test)]
mod r5_correctness_tests {
    use super::*;
    #[test]
    fn debug_off_leaves_diagnostic_counters_untouched() {
        assert!(!debug_enabled(), "run this suite with NTSYNC_DEBUG=0");
        let before: Vec<u64> = stats::COUNTERS.iter().map(|c| c.load(Ordering::Relaxed)).collect();
        let path=std::env::temp_dir().join(format!("r5-counters-{}.shm",std::process::id()));
        init(Some(path.to_str().unwrap())).unwrap();
        { let h = create_semaphore(1, 1).unwrap();
            for _ in 0..100 {
                assert!(matches!(wait_any(&[h], 123, Some(Duration::ZERO), 0), WaitOutcome::Signaled {..}));
                sem_release(h, 1).unwrap();
            }
            assert!(close(h));
        }
        let after: Vec<u64> = stats::COUNTERS.iter().map(|c| c.load(Ordering::Relaxed)).collect();
        assert_eq!(before, after);
    }
    #[test]
    fn object_guard_death_poisons_instead_of_reusing_partial_commit() { unsafe {
        let path = std::env::temp_dir().join(format!("r5-object-death-{}-{}.shm", std::process::id(), monotonic_ns()));
        let r = open_and_map(path.to_str().unwrap()).unwrap();
        let mut pipe = [0;2]; assert_eq!(libc::pipe(pipe.as_mut_ptr()), 0);
        let pid = libc::fork(); assert!(pid >= 0);
        if pid == 0 {
            libc::close(pipe[0]);
            let _signals = SignalGuard::new();
            let _g = r.object_guard(1).unwrap();
            libc::write(pipe[1], b"1".as_ptr().cast(), 1);
            loop { libc::pause(); }
        }
        libc::close(pipe[1]);let mut byte=0u8;
        assert_eq!(libc::read(pipe[0], (&mut byte as *mut u8).cast(), 1),1);
        libc::kill(pid,libc::SIGKILL);let mut status=0;libc::waitpid(pid,&mut status,0);
        let start=std::time::Instant::now();
        assert!(matches!(r.object_guard(1),Err(libc::EIO)));
        assert!(start.elapsed()<Duration::from_secs(2));
        assert_eq!(r.header().poisoned.load(Ordering::Acquire),1);
        assert!(matches!(r.object_guard(2),Err(libc::EIO)));
        libc::close(pipe[0]);libc::munmap(r.base.cast(),region_size());
        let mapped=path.with_file_name(format!("{}.v{}.shm",path.file_stem().unwrap().to_str().unwrap(),VERSION));
        std::fs::remove_file(mapped).unwrap();
    } }
    #[test]
    fn object_operation_signal_mask_is_restored() { unsafe {
        let mut before=std::mem::zeroed();libc::pthread_sigmask(libc::SIG_SETMASK,std::ptr::null(),&mut before);
        { let _g=SignalGuard::new(); let mut during=std::mem::zeroed();
          libc::pthread_sigmask(libc::SIG_SETMASK,std::ptr::null(),&mut during);
          assert_eq!(libc::sigismember(&during,libc::SIGUSR1),1); }
        let mut after=std::mem::zeroed();libc::pthread_sigmask(libc::SIG_SETMASK,std::ptr::null(),&mut after);
        assert_eq!(libc::sigismember(&after,libc::SIGUSR1),libc::sigismember(&before,libc::SIGUSR1));
    } }
}


#[cfg(test)]
mod r6_sweep_tests {
    use super::*;
    fn with_region(test: impl FnOnce(&Region)) {
        let path=std::env::temp_dir().join(format!("r6-sweep-{}-{}.shm",std::process::id(),monotonic_ns()));
        let r=open_and_map(path.to_str().unwrap()).unwrap();
        { let _guard=r.lock_guard().unwrap();test(&r); }
        unsafe { libc::munmap(r.base.cast(),region_size()); }
        let mapped=path.with_file_name(format!("{}.v{}.shm",path.file_stem().unwrap().to_str().unwrap(),VERSION));
        std::fs::remove_file(mapped).unwrap();
    }
    #[test]
    fn dead_registered_and_detached_waiters_are_recycled_once() {
        with_region(|r| {
            let a=node_alloc(r).unwrap();let b=node_alloc(r).unwrap();
            let live=node_alloc(r).unwrap();
            unsafe { (*r.node_ptr(a)).pid=12345;(*r.node_ptr(b)).pid=12345; }
            node_register(r,a,0,a);
            sweep_dead_locked_check(r,|pid| pid!=12345);
            assert_eq!(unsafe { (*r.node_ptr(a)).registered },0);
            assert_eq!(unsafe { (*r.node_ptr(a)).pid },0);
            assert_eq!(unsafe { (*r.node_ptr(b)).pid },0);
            // A second sweep must not add the same free nodes again.
            sweep_dead_locked_check(r,|pid| pid!=12345);
            let x=node_alloc(r).unwrap();let y=node_alloc(r).unwrap();let z=node_alloc(r).unwrap();
            assert!(x==a || x==b);assert!(y==a || y==b);
            assert_ne!(x,y);assert_ne!(z,x);assert_ne!(z,y);assert_ne!(z,live);
        });
    }
    #[test]
    fn liveness_checked_once_per_pid_per_pass_without_cross_pass_cache() {
        with_region(|r| {
            for _ in 0..1000 { let n=node_alloc(r).unwrap();unsafe { (*r.node_ptr(n)).pid=12345; } }
            let mut checks=0;sweep_dead_locked_check(r,|_| { checks+=1;true });assert_eq!(checks,1);
            checks=0;sweep_dead_locked_check(r,|_| { checks+=1;false });assert_eq!(checks,1);
            assert_eq!(unsafe { (*r.node_ptr(0)).pid },0);
        });
    }
}

#[cfg(test)]
mod wait_any_contention_regressions {
    use super::*;
    #[test]
    fn unavailable_object_does_not_block_a_ready_later_object() {
        for (kind, state) in [(TYPE_EVENT, pack_event(false, false)),
            (TYPE_SEM, pack_sem(0, 1)), (TYPE_MUTEX, pack_mutex(99, 1, false))] {
            let path = std::env::temp_dir().join(format!("wow-wait-any-{}-{}.shm", std::process::id(), monotonic_ns()));
            let r = std::sync::Arc::new(open_and_map(path.to_str().unwrap()).unwrap());
            let (a, b) = { let _g = r.lock_guard().unwrap();
                let a = alloc_slot(&r).unwrap(); let b = alloc_slot(&r).unwrap();
                unsafe { slot_publish(&r, a, kind, state); slot_publish(&r, b, TYPE_SEM, pack_sem(1, 1)); }
                (a, b)
            };
            let held = r.object_guard(a & INDEX_MASK).unwrap();
            let rr = r.clone(); let (tx, rx) = std::sync::mpsc::channel();
            let t = std::thread::spawn(move || {
                tx.send(try_satisfy(&rr, &[a, b], 7, &[0; MAX_WAIT_COUNT], false, false)).unwrap();
            });
            let early = rx.recv_timeout(Duration::from_millis(150));
            drop(held); t.join().unwrap();
            assert!(matches!(early, Ok(Some(WaitOutcome::Signaled { index: 1, owner_dead: false }))),
                "WAIT_ANY stalled behind an unavailable object's operation lock: {early:?}");
            assert_eq!(ld64(unsafe { &(*r.slot(b).unwrap()).w0 }) as u32, 0);
            unsafe { libc::munmap(r.base.cast(), region_size()); }
            std::fs::remove_file(path.with_file_name(format!("{}.v{}.shm", path.file_stem().unwrap().to_str().unwrap(), VERSION))).unwrap();
        }
    }
    #[test]
    fn available_object_still_requires_its_operation_lock() {
        let path = std::env::temp_dir().join(format!("wow-wait-lock-{}-{}.shm", std::process::id(), monotonic_ns()));
        let r = std::sync::Arc::new(open_and_map(path.to_str().unwrap()).unwrap());
        let h = { let _g = r.lock_guard().unwrap(); let h = alloc_slot(&r).unwrap();
            unsafe { slot_publish(&r, h, TYPE_SEM, pack_sem(1, 1)); } h };
        let held = r.object_guard(h & INDEX_MASK).unwrap();
        let rr = r.clone(); let (tx, rx) = std::sync::mpsc::channel();
        let t = std::thread::spawn(move || {tx.send(try_satisfy(&rr, &[h], 7, &[0; MAX_WAIT_COUNT], false, false)).unwrap();});
        assert!(rx.recv_timeout(Duration::from_millis(30)).is_err());
        drop(held);
        assert!(matches!(rx.recv_timeout(Duration::from_secs(2)), Ok(Some(WaitOutcome::Signaled { index: 0, .. }))));
        t.join().unwrap();
        unsafe { libc::munmap(r.base.cast(), region_size()); }
        std::fs::remove_file(path.with_file_name(format!("{}.v{}.shm", path.file_stem().unwrap().to_str().unwrap(), VERSION))).unwrap();
    }
}

#[cfg(test)]
mod deadline_regression_tests {
    use super::*;
    #[test]
    fn partial_pre_sleep_work_is_deducted_from_futex_timeout() {
        init(Some(std::env::temp_dir().join(format!("wow-deadline-{}.shm", std::process::id())).to_str().unwrap())).unwrap();
        let event = create_event(false, false).unwrap();
        PRE_SLEEP_DELAY_TEST.set(100);
        PRE_SLEEP_CAPTURE_TEST.set(true);
        FUTEX_TIMEOUT_TEST.set(None);
        let result = wait_any(&[event], 1, Some(Duration::from_millis(500)), 0);
        let requested = FUTEX_TIMEOUT_TEST.get();
        PRE_SLEEP_CAPTURE_TEST.set(false);
        assert!(close(event));
        assert_eq!(result, WaitOutcome::Timeout);
        assert!(requested.is_none_or(|d| d <= Duration::from_millis(401)), "stale partial timeout: {requested:?}");
    }
    #[test]
    fn signal_during_pre_sleep_work_is_preserved_at_expired_deadline() {
        init(Some(std::env::temp_dir().join(format!("wow-deadline-{}.shm", std::process::id())).to_str().unwrap())).unwrap();
        let event = create_event(false, false).unwrap();
        let sender = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(10));
            event_set(event).unwrap();
        });
        PRE_SLEEP_DELAY_TEST.set(50);
        assert!(matches!(wait_any(&[event], 1, Some(Duration::from_millis(20)), 0), WaitOutcome::Signaled { index: 0, .. }));
        sender.join().unwrap();
        assert!(close(event));
    }
    #[test]
    fn expired_timeout_is_not_reused_after_pre_sleep_work() {
        init(Some(std::env::temp_dir().join(format!("wow-deadline-{}.shm", std::process::id())).to_str().unwrap())).unwrap();
        let event = create_event(false, false).unwrap();
        PRE_SLEEP_DELAY_TEST.set(50);
        PRE_SLEEP_CAPTURE_TEST.set(true);
        FUTEX_TIMEOUT_TEST.set(None);
        let result = wait_any(&[event], 1, Some(Duration::from_millis(20)), 0);
        let requested = FUTEX_TIMEOUT_TEST.get();
        PRE_SLEEP_CAPTURE_TEST.set(false);
        assert!(close(event));
        assert_eq!(result, WaitOutcome::Timeout);
        assert!(requested.is_none_or(|d| d.is_zero()), "stale futex timeout: {requested:?}");
    }
}

#[cfg(test)]
mod r10_handoff_tests {
 use super::*;
 fn fixture(f: impl FnOnce(&Region)) {
  let path=std::env::temp_dir().join(format!("r10-handoff-{}-{}.shm",std::process::id(),monotonic_ns()));
  let r=open_and_map(path.to_str().unwrap()).unwrap();f(&r);
  unsafe {libc::munmap(r.base.cast(),region_size());}
  std::fs::remove_file(path.with_file_name(format!("{}.v{}.shm",path.file_stem().unwrap().to_str().unwrap(),VERSION))).unwrap();
 }
 fn object(r:&Region,kind:u32,state:u64)->u32 {let h=alloc_slot(r).unwrap();unsafe {slot_publish(r,h,kind,state);}h}
 fn waiter(r:&Region,handles:&[u32],owner:u32,all:bool)->u32 {
  let nodes=handles.iter().map(|&h|{let n=node_alloc(r).unwrap();unsafe {
   (*r.node_ptr(n)).handle=h;(*r.node_ptr(n)).owner=owner;(*r.node_ptr(n)).flags=all as u32;
  }n}).collect::<Vec<_>>();
  for i in 0..nodes.len() {unsafe {(*r.node_ptr(nodes[i])).waiter_next=nodes.get(i+1).copied().unwrap_or(NIL);}
   node_register(r,nodes[i],handles[i]&INDEX_MASK,nodes[0]);}
  nodes[0]
 }
 fn completed(r:&Region,n:u32)->u32 {unsafe {(*r.node_ptr(n)).completion.load(Ordering::Acquire)}}
 #[test] fn single_credit_grants_one_waiter(){
  R10_FORCE_MUTEX_HANDOFF.set(true);
  for (kind,state) in [(TYPE_EVENT,pack_event(true,false)),(TYPE_SEM,pack_sem(1,3)),(TYPE_MUTEX,pack_mutex(0,0,true))] {fixture(|r|{
   let (h,ns)={let _g=r.lock_guard().unwrap();let h=object(r,kind,state);let ns=(1..=32).map(|i|waiter(r,&[h],i,false)).collect::<Vec<_>>();(h,ns)};
   wake_waiters_if_any(r,h&INDEX_MASK);
   assert_eq!(ns.iter().filter(|&&n|completed(r,n)!=NIL).count(),1,"kind {kind}");
   assert_eq!(ns.iter().filter(|&&n|unsafe {(*r.node_ptr(n)).seq.load(Ordering::Acquire)!=0}).count(),1);
   assert_eq!(ns.iter().filter(|&&n|unsafe {(*r.node_ptr(n)).registered!=0}).count(),31);
   let w=unsafe{ld64(&(*r.slot(h).unwrap()).w0)};
   if kind==TYPE_MUTEX {let winner=*ns.iter().find(|&&n|completed(r,n)!=NIL).unwrap();assert_eq!(completed(r,winner),1<<31);assert_eq!(w as u32,unsafe{(*r.node_ptr(winner)).owner});}
   else {assert_eq!(w as u32,0);}
  });}
  R10_FORCE_MUTEX_HANDOFF.set(false);
 }

 #[test] fn default_mutex_keeps_the_measured_r9_scheduling(){fixture(|r|{
  assert!(!mutex_handoff_enabled());let(h,ns)={let _g=r.lock_guard().unwrap();let h=object(r,TYPE_MUTEX,pack_mutex(0,0,false));let ns=(1..=8).map(|i|waiter(r,&[h],i,false)).collect::<Vec<_>>();(h,ns)};
  wake_waiters_if_any(r,h&INDEX_MASK);assert!(ns.iter().all(|&n|completed(r,n)==NIL));assert!(ns.iter().all(|&n|unsafe{(*r.node_ptr(n)).seq.load(Ordering::Acquire)==1}));
 });}
 #[test] fn registered_wait_does_not_consume_two_credits_during_spin(){
  R10_FORCE_MUTEX_HANDOFF.set(true);
  init(Some(std::env::temp_dir().join(format!("r10-spin-{}.shm",std::process::id())).to_str().unwrap())).unwrap();
  for _ in 0..64 {
   let a=create_semaphore(0,1).unwrap();let b=create_semaphore(0,1).unwrap();
   let t=std::thread::spawn(move||wait_any(&[a,b],7,Some(Duration::from_secs(2)),0));
   let r=region().unwrap();let end=std::time::Instant::now()+Duration::from_secs(1);
   while unsafe{ld32(&(*r.slot(b).unwrap()).waiter_head)}==NIL{assert!(std::time::Instant::now()<end);std::thread::yield_now();}
   sem_release(b,1).unwrap();sem_release(a,1).unwrap();
   assert!(matches!(t.join().unwrap(),WaitOutcome::Signaled {index:1,..}));
   assert_eq!(read_sem(a).unwrap().0,1);assert_eq!(read_sem(b).unwrap().0,0);
   assert!(close(a));assert!(close(b));
  }
 }
 #[test] fn wait_all_never_consumes_a_partial_group(){R10_FORCE_MUTEX_HANDOFF.set(true);fixture(|r|{
  let(a,b,n)={let _g=r.lock_guard().unwrap();let a=object(r,TYPE_SEM,pack_sem(1,1));let b=object(r,TYPE_SEM,pack_sem(0,1));(a,b,waiter(r,&[a,b],9,true))};
  wake_waiters_if_any(r,a&INDEX_MASK);assert_eq!(completed(r,n),NIL);assert_eq!(unsafe {ld64(&(*r.slot(a).unwrap()).w0)} as u32,1);
  {let _g=r.object_guard(b&INDEX_MASK).unwrap();unsafe{st64(&(*r.slot(b).unwrap()).w0,pack_sem(1,1));}}
  wake_waiters_if_any(r,b&INDEX_MASK);assert_eq!(completed(r,n),0);
  for h in [a,b]{assert_eq!(unsafe {ld64(&(*r.slot(h).unwrap()).w0)} as u32,0);}
 });}
 #[test] fn manual_event_completes_every_waiter(){R10_FORCE_MUTEX_HANDOFF.set(true);fixture(|r|{
  let(h,ns)={let _g=r.lock_guard().unwrap();let h=object(r,TYPE_EVENT,pack_event(true,true));let ns=(1..=8).map(|i|waiter(r,&[h],i,false)).collect::<Vec<_>>();(h,ns)};
  wake_waiters_if_any(r,h&INDEX_MASK);assert!(ns.iter().all(|&n|completed(r,n)==0));assert_eq!(unsafe {ld64(&(*r.slot(h).unwrap()).w0)} as u32,1);
 });}
 #[test] fn invalidation_wakes_all_without_granting_resources(){fixture(|r|{
  let _g=r.lock_guard().unwrap();let h=object(r,TYPE_SEM,pack_sem(0,1));let ns=(1..=8).map(|i|waiter(r,&[h],i,false)).collect::<Vec<_>>();
  wake_object(r,h&INDEX_MASK);assert!(ns.iter().all(|&n|completed(r,n)==NIL));assert!(ns.iter().all(|&n|unsafe{(*r.node_ptr(n)).seq.load(Ordering::Acquire)==1}));
 });}
}

#[cfg(test)]
mod r10_pulse_sweep_tests {
 use super::*;
 #[test] #[cfg(debug_assertions)]
 fn empty_pulse_skips_the_full_slot_scan(){
  init(Some(std::env::temp_dir().join(format!("r10-pulse-{}.shm",std::process::id())).to_str().unwrap())).unwrap();let h=create_event(false,false).unwrap();R10_PULSE_SCANS.store(0,Ordering::Relaxed);
  event_pulse(h).unwrap();let n=R10_PULSE_SCANS.load(Ordering::Relaxed);close(h);assert_eq!(n,0);
 }
 #[test] #[ignore="perf simulation"]
 fn perf_r10_empty_event_pulse(){
  init(Some(std::env::temp_dir().join(format!("r10-pulse-{}.shm",std::process::id())).to_str().unwrap())).unwrap();let h=create_event(false,false).unwrap();let start=std::time::Instant::now();
  for _ in 0..20000 {event_pulse(h).unwrap();}
  println!("empty pulse: {} ns/op",start.elapsed().as_nanos()/20000);close(h);
 }
 #[test] #[ignore="perf simulation"]
 fn perf_r10_full_sweep(){
  let path=std::env::temp_dir().join(format!("r10-sweep-{}-{}.shm",std::process::id(),monotonic_ns()));let r=open_and_map(path.to_str().unwrap()).unwrap();
  {let _g=r.lock_guard().unwrap();for _ in 1..SLOT_COUNT {let h=alloc_slot(&r).unwrap();unsafe{slot_publish(&r,h,TYPE_SEM,pack_sem(0,1));}}
   for _ in 0..NODE_COUNT {node_alloc(&r).unwrap();}
   let start=std::time::Instant::now();for _ in 0..500 {assert_eq!(sweep_dead_locked(&r),0);}
   println!("full sweep live single PID, {} slots/{} nodes: {} ns/pass",SLOT_COUNT-1,NODE_COUNT,start.elapsed().as_nanos()/500);
   let mut probes=0;sweep_dead_locked_check(&r,|_|{probes+=1;true});assert_eq!(probes,1);
  }
  unsafe{libc::munmap(r.base.cast(),region_size());}
  std::fs::remove_file(path.with_file_name(format!("{}.v{}.shm",path.file_stem().unwrap().to_str().unwrap(),VERSION))).unwrap();
 }
}
#[cfg(all(test,debug_assertions))]
static R10_PULSE_SCANS: AtomicU32 = AtomicU32::new(0);

#[cfg(test)]
thread_local! {
 static R10_SPIN_BUDGET_TEST: std::cell::Cell<Option<u32>> = const { std::cell::Cell::new(None) };
 static R10_SPIN_HOOK_TEST: std::cell::RefCell<Option<Box<dyn FnOnce()>>> = const { std::cell::RefCell::new(None) };
 static R10_TIMEOUT_HOOK_TEST: std::cell::RefCell<Option<Box<dyn FnOnce()>>> = const { std::cell::RefCell::new(None) };
}
#[cfg(test)]
mod r10_spin_timeout_tests {
 use super::*;
 fn initialize(){init(Some(std::env::temp_dir().join(format!("r10-spin-timeout-{}.shm",std::process::id())).to_str().unwrap())).unwrap();}
 #[test]
 fn semaphore_handoff_during_spin_consumes_exactly_one_credit(){
  initialize();R10_FORCE_MUTEX_HANDOFF.set(true);let h=create_semaphore(0,2).unwrap();
  // Suite-local override avoids the cached process-wide environment from
  // unrelated tests. Isolated runs also set NTSYNC_SPIN_ITERS=100/250/500.
  let spin=std::env::var("NTSYNC_SPIN_ITERS").ok().and_then(|s|s.parse().ok()).unwrap_or(250);
  assert!(spin>0);R10_SPIN_BUDGET_TEST.set(Some(spin));
  R10_SPIN_HOOK_TEST.with(|hook|*hook.borrow_mut()=Some(Box::new(move||{
   // This runs inside the production spin loop, before its acquire probe.
   std::thread::spawn(move||{R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,2).unwrap();}).join().unwrap();
   assert_eq!(read_sem(h).unwrap().0,1,"handoff should consume one of two released credits");
  })));
  let result=wait_any(&[h],7,Some(Duration::from_secs(1)),0);R10_SPIN_BUDGET_TEST.set(None);
  assert_eq!(result,WaitOutcome::Signaled{index:0,owner_dead:false});
  assert_eq!(read_sem(h).unwrap().0,1,"spin acquired again after completion");assert!(close(h));
 }
 #[test]
 fn handoff_before_futex_timeout_wins_over_timeout_without_losing_credit(){
  initialize();R10_FORCE_MUTEX_HANDOFF.set(true);let h=create_semaphore(0,1).unwrap();R10_SPIN_BUDGET_TEST.set(Some(0));
  R10_TIMEOUT_HOOK_TEST.with(|hook|*hook.borrow_mut()=Some(Box::new(move||{
   std::thread::spawn(move||{R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,1).unwrap();}).join().unwrap();
   assert_eq!(read_sem(h).unwrap().0,0);
   std::thread::sleep(Duration::from_millis(30)); // deadline expires before ETIMEDOUT is delivered
  })));
  let result=wait_any(&[h],7,Some(Duration::from_millis(20)),0);R10_SPIN_BUDGET_TEST.set(None);
  assert_eq!(result,WaitOutcome::Signaled{index:0,owner_dead:false});
  assert_eq!(read_sem(h).unwrap().0,0);assert!(close(h));
 }
}

#[cfg(test)]
thread_local! {
 static R10_SIGNAL_DEPTH_TEST: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
 static R10_CHECK_NOTIFY_SCOPE_TEST: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}
#[cfg(test)]
mod r10_notify_scope_tests {
 use super::*;
 #[test] fn termination_remains_deferred_through_signal_notification(){
  init(Some(std::env::temp_dir().join(format!("r10-notify-{}.shm",std::process::id())).to_str().unwrap())).unwrap();
  R10_FORCE_MUTEX_HANDOFF.set(true);
  let h=create_semaphore(0,1).unwrap();let r=region().unwrap();
  let node={let _g=r.lock_guard().unwrap();let n=node_alloc(r).unwrap();unsafe{(*r.node_ptr(n)).handle=h;(*r.node_ptr(n)).owner=7;}node_register(r,n,h&INDEX_MASK,n);n};
  R10_CHECK_NOTIFY_SCOPE_TEST.set(true);
  sem_release(h,1).unwrap();R10_CHECK_NOTIFY_SCOPE_TEST.set(false);
  assert_eq!(R10_SIGNAL_DEPTH_TEST.get(),0);
  assert_eq!(unsafe{(*r.node_ptr(node)).completion.load(Ordering::Acquire)},0);
  {let _g=r.lock_guard().unwrap();node_free(r,node);}
  assert!(close(h));
 }
}

#[cfg(test)]
mod r11_dead_waiter_tests {
 use super::*;
 fn initialize(){init(Some(std::env::temp_dir().join(format!("r11-dead-waiter-{}.shm",std::process::id())).to_str().unwrap())).unwrap();}
 fn registered(r:&Region,h:u32){let end=std::time::Instant::now()+Duration::from_secs(2);while unsafe{ld32(&(*r.slot(h).unwrap()).waiter_head)}==NIL{assert!(std::time::Instant::now()<end);std::thread::yield_now();}}
 fn check(dead_process:bool){
  initialize();let h=create_semaphore(0,1).unwrap();let live=std::thread::spawn(move||wait_any(&[h],17,Some(Duration::from_millis(350)),0));let r=region().unwrap();registered(r,h);
  let prior=unsafe{ld32(&(*r.slot(h).unwrap()).waiter_head)};
  if dead_process {unsafe{
   let pid=libc::fork();assert!(pid>=0);
   if pid==0 {R10_TIMEOUT_HOOK_TEST.with(|hook|*hook.borrow_mut()=Some(Box::new(||libc::_exit(0))));let _=wait_any(&[h],23,None,0);libc::_exit(9);}
   let mut status=0;assert_eq!(libc::waitpid(pid,&mut status,0),pid);assert_eq!(status,0);
  }}else{
   // A panic at the production futex entry simulates aborting a sleeping
   // Wine thread with its nodes left registered. The host PID remains alive.
   let dead=std::thread::spawn(move||{R10_TIMEOUT_HOOK_TEST.with(|hook|*hook.borrow_mut()=Some(Box::new(||panic!("simulate Wine thread abort outside critical guards"))));wait_any(&[h],23,None,0)});
   assert!(dead.join().is_err());
  }
  let stale=unsafe{ld32(&(*r.slot(h).unwrap()).waiter_head)};assert_ne!(stale,prior);
  sem_release(h,1).unwrap();let result=live.join().unwrap();
  // Cleanup the synthetic abandoned node after observing production behavior.
  {let _g=r.lock_guard().unwrap();node_detach(r,stale);if unsafe{(*r.node_ptr(stale)).pid}!=0{node_free(r,stale);}}
  assert!(close(h));assert_eq!(result,WaitOutcome::Signaled{index:0,owner_dead:false},"stale waiter consumed the only semaphore credit");
 }
 #[test]fn terminated_thread_in_live_process_does_not_steal_signal(){check(false);}
 #[test]fn dead_process_before_exit_sweep_does_not_steal_signal(){check(true);}
}

#[cfg(test)]
mod r11_pulse_sweep_tests {
 use super::*;
 fn init_test(){init(Some(std::env::temp_dir().join(format!("r11-pulse-sweep-{}.shm",std::process::id())).to_str().unwrap())).unwrap();}
 #[test] fn dead_thread_pulse_wait_all_cannot_consume_a_live_semaphore(){
  init_test();let e=create_event(false,false).unwrap();let s=create_semaphore(1,1).unwrap();
  let(head,tail)=std::thread::spawn(move||{let r=region().unwrap();let _g=r.lock_guard().unwrap();let a=node_alloc(r).unwrap();let b=node_alloc(r).unwrap();unsafe{(*r.node_ptr(a)).handle=e;(*r.node_ptr(a)).owner=7;(*r.node_ptr(a)).flags=1;(*r.node_ptr(a)).waiter_next=b;(*r.node_ptr(b)).handle=s;(*r.node_ptr(b)).owner=7;(*r.node_ptr(b)).flags=1;}node_register(r,a,e&INDEX_MASK,a);node_register(r,b,s&INDEX_MASK,a);(a,b)}).join().unwrap();
  event_pulse(e).unwrap();let count=read_sem(s).unwrap().0;
  {let r=region().unwrap();let _g=r.lock_guard().unwrap();for n in [head,tail]{node_detach(r,n);if unsafe{(*r.node_ptr(n)).pid}!=0{node_free(r,n);}}}
  close(e);close(s);assert_eq!(count,1,"dead WAIT_ALL pulse waiter consumed semaphore");
 }
 #[test] fn sweep_reclaims_dead_threads_without_killing_the_process(){
  init_test();let n=std::thread::spawn(||{let r=region().unwrap();let _g=r.lock_guard().unwrap();node_alloc(r).unwrap()}).join().unwrap();
  let r=region().unwrap();let _g=r.lock_guard().unwrap();sweep_dead_locked(r);let pid=unsafe{(*r.node_ptr(n)).pid};if pid!=0{node_free(r,n);}assert_eq!(pid,0,"PID-only sweep retained a dead thread's waiter");
 }
}

#[cfg(test)]
mod r11_thread_exit_tests {
 use super::*;
 fn setup(){init(Some(std::env::temp_dir().join(format!("r11-exit-{}.shm",std::process::id())).to_str().unwrap())).unwrap();}
 fn registered(handles:&[u32],all:bool)->u32 {
  let r=region().unwrap();let _g=r.lock_guard().unwrap();let mut head=NIL;let mut previous=NIL;
  for h in handles {let n=node_alloc(r).unwrap();unsafe{(*r.node_ptr(n)).handle=*h;(*r.node_ptr(n)).owner=91;(*r.node_ptr(n)).flags=all as u32;}
   if head==NIL{head=n;}else{unsafe{(*r.node_ptr(previous)).waiter_next=n;}}previous=n;node_register(r,n,h&INDEX_MASK,head);
  }head
 }
 #[test]fn short_metadata_contention_retries_and_refunds() {
  setup();let h=create_semaphore(0,1).unwrap();registered(&[h],false);
  R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,1).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);
  let ready=std::sync::Arc::new(std::sync::Barrier::new(2));let other=ready.clone();
  let start=EXIT_RETRIED.load(Ordering::Relaxed);
  let t=std::thread::spawn(move||{let r=region().unwrap();let _guard=r.lock_guard().unwrap();other.wait();
    while EXIT_RETRIED.load(Ordering::Relaxed)==start {std::thread::yield_now();}
  });ready.wait();thread_exit().unwrap();t.join().unwrap();assert_eq!(read_sem(h).unwrap().0,1);close(h);
 }
 #[test]fn held_metadata_has_bounded_retry_and_records_skip() {
  setup();let r=region().unwrap();let _guard=r.lock_guard().unwrap();let before=EXIT_SKIPPED.load(Ordering::Relaxed);
  let start=std::time::Instant::now();assert!(matches!(thread_exit(),Err(Error::Init(libc::EBUSY))));
  assert!(start.elapsed()<Duration::from_millis(100));assert_eq!(EXIT_SKIPPED.load(Ordering::Relaxed),before+1);
 }
 #[test]fn pending_nodes_are_gone_while_tid_is_still_alive(){setup();let a=create_semaphore(0,1).unwrap();let b=create_event(false,false).unwrap();let n=registered(&[a,b],true);thread_exit().unwrap();thread_exit().unwrap();let r=region().unwrap();assert_eq!(unsafe{(*r.node_ptr(n)).pid},0);assert!(thread_alive(std::process::id(),current_tid()));assert_eq!(unsafe{(*r.slot(a).unwrap()).waiter_head},NIL);assert_eq!(read_sem(a).unwrap().0,0);close(a);close(b);}
 #[test]fn unreceived_semaphore_completion_is_refunded_once(){setup();let h=create_semaphore(0,3).unwrap();registered(&[h],false);R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,1).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);assert_eq!(read_sem(h).unwrap().0,0);thread_exit().unwrap();assert_eq!(read_sem(h).unwrap().0,1);thread_exit().unwrap();assert_eq!(read_sem(h).unwrap().0,1);close(h);}
 #[test]fn unreceived_auto_event_completion_is_refunded(){setup();let h=create_event(false,false).unwrap();registered(&[h],false);R10_FORCE_MUTEX_HANDOFF.set(true);event_set(h).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);thread_exit().unwrap();assert!(read_event(h).unwrap().1);close(h);}
 #[test]fn pulse_wait_all_refunds_semaphore_without_persisting_pulse(){setup();let e=create_event(false,false).unwrap();let s=create_semaphore(1,1).unwrap();registered(&[e,s],true);event_pulse(e).unwrap();assert_eq!(read_sem(s).unwrap().0,0);thread_exit().unwrap();assert_eq!(read_sem(s).unwrap().0,1);assert!(!read_event(e).unwrap().1);close(e);close(s);}
 #[test]fn pulse_wait_any_does_not_create_an_event_credit(){setup();let e=create_event(false,false).unwrap();registered(&[e],false);event_pulse(e).unwrap();thread_exit().unwrap();assert!(!read_event(e).unwrap().1);close(e);}
 #[test]fn wait_all_refunds_group_and_abandons_mutex(){setup();let m=create_mutex(0,0).unwrap();let s=create_semaphore(0,1).unwrap();registered(&[m,s],true);R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(s,1).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);thread_exit().unwrap();assert_eq!(read_sem(s).unwrap().0,1);assert_eq!(read_mutex(m).unwrap(),(0,0,true));close(m);close(s);}
 #[test]fn current_thread_cleanup_keeps_other_threads_nodes(){setup();let h=create_semaphore(0,1).unwrap();let barriers=std::sync::Arc::new(std::sync::Barrier::new(2));let other=barriers.clone();let t=std::thread::spawn(move||{let n=registered(&[h],false);other.wait();other.wait();let r=region().unwrap();assert_ne!(unsafe{(*r.node_ptr(n)).pid},0);thread_exit().unwrap();});barriers.wait();registered(&[h],false);thread_exit().unwrap();barriers.wait();t.join().unwrap();close(h);}
 #[test]fn closed_generation_never_receives_old_completion(){setup();let h=create_semaphore(0,1).unwrap();registered(&[h],false);R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,1).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);close(h);let next=create_semaphore(0,1).unwrap();thread_exit().unwrap();assert_eq!(read_sem(next).unwrap().0,0);close(next);}
}

#[cfg(test)] mod r11_refund_bound_tests {
 use super::*;
 #[test]fn exit_cleanup_does_not_reenter_metadata_lock(){init(None).unwrap();let r=region().unwrap();let _g=r.lock_guard().unwrap();assert!(matches!(thread_exit(),Err(Error::Init(libc::EBUSY))));}
 #[test]fn late_release_refund_is_capped_without_poison(){
  init(None).unwrap();let h=create_semaphore(0,1).unwrap();let r=region().unwrap();
  {let _g=r.lock_guard().unwrap();let n=node_alloc(r).unwrap();unsafe{(*r.node_ptr(n)).handle=h;(*r.node_ptr(n)).owner=77;}node_register(r,n,h&INDEX_MASK,n);}
  R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,1).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);
  sem_release(h,1).unwrap();thread_exit().unwrap();assert_eq!(read_sem(h).unwrap().0,1);assert_eq!(r.header().poisoned.load(Ordering::Acquire),0);close(h);
 }
 #[test]fn refunded_mutex_next_owner_gets_abandoned(){
  init(None).unwrap();let h=create_mutex(15,1).unwrap();let r=region().unwrap();
  {let _g=r.lock_guard().unwrap();let n=node_alloc(r).unwrap();unsafe{(*r.node_ptr(n)).handle=h;(*r.node_ptr(n)).owner=77;}node_register(r,n,h&INDEX_MASK,n);}
  R10_FORCE_MUTEX_HANDOFF.set(true);mutex_unlock(h,15).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);thread_exit().unwrap();
  assert_eq!(wait_any(&[h],91,Some(Duration::ZERO),0),WaitOutcome::Signaled{index:0,owner_dead:true});close(h);
 }
}

#[cfg(test)]
mod r25_close_tests {
    use super::*;
    use std::time::{Duration, Instant};
    fn setup() {
        static INIT: std::sync::Once = std::sync::Once::new();
        INIT.call_once(|| {
            let path = std::env::temp_dir().join(format!("r25-close-{}.shm", std::process::id()));
            let _ = std::fs::remove_file(&path);
            init(Some(path.to_str().unwrap())).unwrap();
        });
    }
    fn wait_registered(h: u32) {
        let r = region().unwrap();
        let end = Instant::now() + Duration::from_secs(3);
        while unsafe { ld32(&(*r.slot_ptr(h & INDEX_MASK)).waiter_head) } == NIL {
            assert!(Instant::now() < end, "waiter never registered"); std::thread::yield_now();
        }
    }
    #[test]
    fn a_wait_survives_the_close_of_its_object() {
        setup();
        let e = create_event(true, false).unwrap();
        let waiter = std::thread::spawn(move || { let t = Instant::now(); (wait_any(&[e], 5, Some(Duration::from_millis(400)), 0), t.elapsed()) });
        wait_registered(e);
        assert!(close(e));
        let (outcome, took) = waiter.join().unwrap();
        assert!(matches!(outcome, WaitOutcome::Timeout), "the wait goes on until its timeout, got {:?}", outcome);
        assert!(took >= Duration::from_millis(350), "it was not cut short ({:?})", took);
    }
    #[test]
    fn a_closed_handle_is_invalid_for_new_waits_signals_and_a_second_close() {
        setup();
        let e = create_event(true, false).unwrap();
        assert!(close(e));
        assert!(matches!(wait_any(&[e], 5, Some(Duration::ZERO), 0), WaitOutcome::Invalid));
        assert!(event_set(e).is_err());
        assert!(!close(e));
    }
    #[test]
    fn while_its_waiter_keeps_it_the_closed_object_still_refuses_new_users() {
        setup();
        let e = create_event(true, false).unwrap();
        let waiter = std::thread::spawn(move || wait_any(&[e], 5, Some(Duration::from_millis(400)), 0));
        wait_registered(e);
        assert!(close(e));
        assert!(matches!(wait_any(&[e], 6, Some(Duration::ZERO), 0), WaitOutcome::Invalid), "a new wait on the closed handle");
        assert!(event_set(e).is_err(), "nobody can signal it any more");
        assert!(matches!(waiter.join().unwrap(), WaitOutcome::Timeout));
    }
    #[test]
    fn a_wait_any_on_a_closed_and_an_open_object_is_satisfied_by_the_open_one() {
        setup();
        let e = create_event(true, false).unwrap();
        let s = create_semaphore(0, 1).unwrap();
        let waiter = std::thread::spawn(move || wait_any(&[e, s], 5, Some(Duration::from_secs(5)), 0));
        wait_registered(s);
        assert!(close(e));
        std::thread::sleep(Duration::from_millis(50));
        sem_release(s, 1).unwrap();
        assert!(matches!(waiter.join().unwrap(), WaitOutcome::Signaled { index: 1, .. }));
        assert!(close(s));
    }
    #[test]
    fn the_slot_of_a_closed_object_is_reused_once_its_last_waiter_left() {
        setup();
        let r = region().unwrap();
        let e = create_event(true, false).unwrap();
        let waiter = std::thread::spawn(move || wait_any(&[e], 5, Some(Duration::from_millis(100)), 0));
        wait_registered(e);
        assert!(close(e));
        assert!(matches!(waiter.join().unwrap(), WaitOutcome::Timeout));
        let index = e & INDEX_MASK;
        assert_eq!(unsafe { ld32(&(*r.slot_ptr(index)).closing) } & CLOSING, CLOSING, "kept until reclaimed");
        let reused = {
            let _g = r.lock_guard().unwrap();
            unsafe { (*(r.base as *mut Header)).next_scan = index; }
            alloc_slot(r).unwrap()
        };
        assert_eq!(reused & INDEX_MASK, index, "the closed slot is handed out again");
        assert_ne!(reused, e, "with a new generation");
        assert!(r.slot(e).is_none(), "the old handle no longer resolves");
        unsafe { slot_publish(r, reused, TYPE_EVENT, pack_event(true, false)); }
        assert!(close(reused));
    }
    #[test]
    fn closing_a_threads_alert_event_still_ends_its_wait_at_once() {
        setup();
        let s = create_semaphore(0, 1).unwrap();
        let alert = create_event(true, false).unwrap();
        let waiter = std::thread::spawn(move || { let t = Instant::now(); (wait_any(&[s], 5, Some(Duration::from_secs(5)), alert), t.elapsed()) });
        wait_registered(alert);
        assert!(close(alert));
        let (outcome, took) = waiter.join().unwrap();
        assert!(matches!(outcome, WaitOutcome::Invalid), "unchanged thread-teardown behaviour, got {:?}", outcome);
        assert!(took < Duration::from_secs(2));
        assert!(close(s));
    }
    #[test]
    fn the_usage_counters_follow_creation_and_closing() {
        setup();
        let r = region().unwrap();
        let before = r.header().live_slots.load(Ordering::Relaxed);
        let hs: Vec<u32> = (0..10).map(|_| create_semaphore(0, 1).unwrap()).collect();
        assert!(r.header().live_slots.load(Ordering::Relaxed) >= before + 10);
        assert!(r.header().peak_slots.load(Ordering::Relaxed) >= before + 10);
        for h in hs { assert!(close(h)); }
        assert!(r.header().live_slots.load(Ordering::Relaxed) <= before + 1, "closing gives the objects back");
    }
}

#[cfg(test)]
mod r24_precedence_tests {
    use super::*;
    use std::time::Duration;
    fn setup() {
        static INIT: std::sync::Once = std::sync::Once::new();
        INIT.call_once(|| {
            let path = std::env::temp_dir().join(format!("r24-precedence-{}.shm", std::process::id()));
            let _ = std::fs::remove_file(&path);
            init(Some(path.to_str().unwrap())).unwrap();
        });
    }
    const ZERO: Option<Duration> = Some(Duration::ZERO);
    #[test]
    fn wait_any_accepts_the_same_object_twice_and_wait_all_still_refuses_it() {
        setup();
        let s = create_semaphore(1, 1).unwrap();
        assert!(matches!(wait_any(&[s, s], 7, ZERO, 0), WaitOutcome::Signaled { index: 0, .. }));
        assert!(matches!(wait_any(&[s, s], 7, ZERO, 0), WaitOutcome::Timeout), "the single credit was taken once");
        sem_release(s, 1).unwrap();
        assert!(matches!(wait_all(&[s, s], 7, ZERO, 0), WaitOutcome::Invalid), "WaitAll names an object once");
        assert!(matches!(wait_any(&[s], 7, ZERO, 0), WaitOutcome::Signaled { index: 0, .. }), "and refused without consuming anything");
        assert!(close(s));
    }
    #[test]
    fn wait_any_takes_a_signaled_object_before_a_signaled_alert() {
        setup();
        let s = create_semaphore(1, 1).unwrap();
        let alert = create_event(true, true).unwrap();
        assert!(matches!(wait_any(&[s], 7, ZERO, alert), WaitOutcome::Signaled { index: 0, .. }), "the object, not the alert");
        assert!(matches!(wait_any(&[s], 7, ZERO, alert), WaitOutcome::Signaled { index: 1, .. }), "nothing to take: the alert ends the wait");
        assert!(close(s)); assert!(close(alert));
    }
    #[test]
    fn wait_all_takes_every_object_before_a_signaled_alert() {
        setup();
        let a = create_semaphore(1, 1).unwrap();
        let b = create_semaphore(1, 1).unwrap();
        let alert = create_event(true, true).unwrap();
        assert!(matches!(wait_all(&[a, b], 7, ZERO, alert), WaitOutcome::Signaled { index: 0, .. }));
        assert!(matches!(wait_all(&[a, b], 7, ZERO, alert), WaitOutcome::Signaled { index: 2, .. }), "group not complete: the alert");
        sem_release(a, 1).unwrap();
        assert!(matches!(wait_all(&[a, b], 7, ZERO, alert), WaitOutcome::Signaled { index: 2, .. }), "one missing: nothing consumed, the alert");
        assert!(matches!(wait_any(&[a], 7, ZERO, 0), WaitOutcome::Signaled { index: 0, .. }), "the credit of a was not taken by the failed WaitAll");
        assert!(close(a)); assert!(close(b)); assert!(close(alert));
    }
    #[test]
    fn an_unsignaled_alert_does_not_hide_an_object() {
        setup();
        let s = create_semaphore(1, 1).unwrap();
        let alert = create_event(true, false).unwrap();
        assert!(matches!(wait_any(&[s], 7, ZERO, alert), WaitOutcome::Signaled { index: 0, .. }));
        assert!(matches!(wait_any(&[s], 7, ZERO, alert), WaitOutcome::Timeout));
        assert!(close(s)); assert!(close(alert));
    }
    #[test]
    fn a_blocked_alertable_wait_ends_with_the_alert_when_it_is_set() {
        setup();
        let s = create_semaphore(0, 1).unwrap();
        let alert = create_event(true, false).unwrap();
        let waiter = std::thread::spawn(move || wait_any(&[s], 9, Some(Duration::from_secs(10)), alert));
        std::thread::sleep(Duration::from_millis(100));
        event_set(alert).unwrap();
        assert!(matches!(waiter.join().unwrap(), WaitOutcome::Signaled { index: 1, .. }));
        assert!(close(s)); assert!(close(alert));
    }
}

#[cfg(test)] mod r11_signal_safety_tests {
 use super::*;
 struct CountAllocator;
 static TRACK: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
 static ALLOCS: AtomicU32 = AtomicU32::new(0);
 unsafe impl std::alloc::GlobalAlloc for CountAllocator {
  unsafe fn alloc(&self,l:std::alloc::Layout)->*mut u8{if TRACK.load(Ordering::Relaxed){ALLOCS.fetch_add(1,Ordering::Relaxed);}std::alloc::GlobalAlloc::alloc(&std::alloc::System,l)}
  unsafe fn dealloc(&self,p:*mut u8,l:std::alloc::Layout){std::alloc::GlobalAlloc::dealloc(&std::alloc::System,p,l)}
 }
 #[global_allocator] static ALLOCATOR:CountAllocator=CountAllocator;
 #[test] fn completed_pulse_exit_cleanup_has_zero_heap_allocations(){
  init(None).unwrap();let e=create_event(false,false).unwrap();let s=create_semaphore(1,1).unwrap();let r=region().unwrap();
  {let _g=r.lock_guard().unwrap();let a=node_alloc(r).unwrap();let b=node_alloc(r).unwrap();unsafe{(*r.node_ptr(a)).handle=e;(*r.node_ptr(a)).owner=71;(*r.node_ptr(a)).flags=1;(*r.node_ptr(a)).waiter_next=b;(*r.node_ptr(b)).handle=s;(*r.node_ptr(b)).owner=71;(*r.node_ptr(b)).flags=1;}node_register(r,a,e&INDEX_MASK,a);node_register(r,b,s&INDEX_MASK,a);}
  event_pulse(e).unwrap();ALLOCS.store(0,Ordering::Relaxed);TRACK.store(true,Ordering::Relaxed);
  let result=crate::ffi::ntsync_thread_exit();TRACK.store(false,Ordering::Relaxed);
  assert_eq!(result,0);assert_eq!(ALLOCS.load(Ordering::Relaxed),0);assert_eq!(read_sem(s).unwrap().0,1);close(e);close(s);
 }
 #[test] fn held_object_gate_leaves_completion_for_later_cleanup(){
  init(None).unwrap();let h=create_semaphore(0,1).unwrap();let r=region().unwrap();
  {let _g=r.lock_guard().unwrap();let n=node_alloc(r).unwrap();unsafe{(*r.node_ptr(n)).handle=h;(*r.node_ptr(n)).owner=71;}node_register(r,n,h&INDEX_MASK,n);}
  R10_FORCE_MUTEX_HANDOFF.set(true);sem_release(h,1).unwrap();R10_FORCE_MUTEX_HANDOFF.set(false);
  {let _gate=r.object_guard(h&INDEX_MASK).unwrap();assert!(matches!(thread_exit(),Err(Error::Init(libc::EBUSY))));}
  thread_exit().unwrap();assert_eq!(read_sem(h).unwrap().0,1);close(h);
 }
}

#[cfg(test)]
mod r27_group_sweep_tests {
    use super::*;
    fn setup() {
        init(Some(std::env::temp_dir().join(format!("r27-sweep-{}.shm", std::process::id())).to_str().unwrap())).unwrap();
    }
    /// A dead waiter's two nodes, registered on `a` and `b`; returns (head, tail).
    fn dead_group(r: &Region, a: u32, b: u32, dead_pid: u32) -> (u32, u32) {
        let h = node_alloc(r).unwrap();
        let t = node_alloc(r).unwrap();
        unsafe {
            let hn = &mut *r.node_ptr(h); hn.pid = dead_pid; hn.tid = dead_pid; hn.handle = a; hn.flags = 1; hn.waiter_next = t;
            let tn = &mut *r.node_ptr(t); tn.pid = dead_pid; tn.tid = dead_pid; tn.handle = b; tn.flags = 1; tn.waiter_next = NIL;
        }
        node_register(r, h, a & INDEX_MASK, h);
        node_register(r, t, b & INDEX_MASK, h);
        (h, t)
    }
    #[test]
    fn a_sweep_slice_holding_only_part_of_a_dead_waiter_frees_the_whole_group() {
        setup();
        let r = region().unwrap();
        let a = create_event(true, false).unwrap();
        let b = create_event(true, false).unwrap();
        let dead_pid = 0x3fff_fff0;
        let _g = r.lock_guard().unwrap();
        let (h, t) = dead_group(r, a, b, dead_pid);
        sweep_dead_locked_range(r, |pid| pid != dead_pid, |_, _| true, t..t + 1, 0..0);
        assert_eq!(unsafe { (*r.node_ptr(h)).pid }, 0, "the head outside the slice is freed with its group");
        assert_eq!(unsafe { (*r.node_ptr(t)).pid }, 0);
        assert_eq!(unsafe { ld32(&(*r.slot_ptr(a & INDEX_MASK)).waiter_head) }, NIL);
        assert_eq!(unsafe { ld32(&(*r.slot_ptr(b & INDEX_MASK)).waiter_head) }, NIL);
    }
    #[test]
    fn a_partly_swept_dead_group_never_steals_a_live_waiters_pulse() {
        setup();
        let r = region().unwrap();
        let a = create_event(true, false).unwrap();
        let b = create_event(true, false).unwrap();
        let live = create_event(false, false).unwrap();
        let dead_pid = 0x3fff_fff1;
        {
            let _g = r.lock_guard().unwrap();
            let (_h, t) = dead_group(r, a, b, dead_pid);
            sweep_dead_locked_range(r, |pid| pid != dead_pid, |_, _| true, t..t + 1, 0..0);
        }
        let waiter = std::thread::spawn(move || wait_any(&[live], 700, Some(Duration::from_millis(500)), 0));
        let end = std::time::Instant::now() + Duration::from_secs(2);
        while unsafe { ld32(&(*r.slot_ptr(live & INDEX_MASK)).waiter_head) } == NIL {
            assert!(std::time::Instant::now() < end);
            std::thread::yield_now();
        }
        event_pulse(a).unwrap();
        event_pulse(live).unwrap();
        assert_eq!(waiter.join().unwrap(), WaitOutcome::Signaled { index: 0, owner_dead: false });
    }
}

#[cfg(test)]
mod r28_deadline_alert_tests {
    use super::*;
    fn setup() {
        init(Some(std::env::temp_dir().join(format!("r28-deadline-{}.shm", std::process::id())).to_str().unwrap())).unwrap();
    }
    fn tid() -> u32 { unsafe { libc::gettid() as u32 } }

    #[test]
    fn absolute_deadline_is_not_lengthened_by_a_delay_before_the_wait() {
        setup();
        let e = create_event(false, false).unwrap();
        for clock in [libc::CLOCK_MONOTONIC, libc::CLOCK_REALTIME] {
            // The caller computed "30 ms from now", then was held up for 40 ms before the wait began (OPT12-01).
            let deadline = Deadline::at(clock, clock_ns(clock) + 30_000_000);
            std::thread::sleep(Duration::from_millis(40));
            let start = std::time::Instant::now();
            assert!(matches!(wait_any_until(&[e], tid(), Some(deadline), 0), WaitOutcome::Timeout));
            assert!(start.elapsed() < Duration::from_millis(10), "waited {:?} past an expired deadline", start.elapsed());
            // A deadline still ahead is waited out, not much less and not much more.
            let deadline = Deadline::at(clock, clock_ns(clock) + 30_000_000);
            let start = std::time::Instant::now();
            assert!(matches!(wait_any_until(&[e], tid(), Some(deadline), 0), WaitOutcome::Timeout));
            let waited = start.elapsed();
            assert!(waited >= Duration::from_millis(29) && waited < Duration::from_millis(60), "waited {:?}", waited);
        }
        close(e);
    }

    #[test]
    fn closing_an_alert_event_ends_the_alert_wait_even_while_another_wait_holds_it() {
        setup();
        let alert = create_event(true, false).unwrap();
        let other = create_event(false, false).unwrap();
        // A plain wait on the alert event keeps it alive after the close (it holds a reference) ...
        let holder = std::thread::spawn(move || wait_any(&[alert], unsafe { libc::gettid() as u32 }, Some(Duration::from_millis(400)), 0));
        // ... while a second wait uses it only as its APC alert (no reference).
        let alerted = std::thread::spawn(move || {
            let start = std::time::Instant::now();
            let outcome = wait_any(&[other], unsafe { libc::gettid() as u32 }, Some(Duration::from_millis(2000)), alert);
            (outcome, start.elapsed())
        });
        std::thread::sleep(Duration::from_millis(100));
        assert!(close(alert));
        let (outcome, waited) = alerted.join().unwrap();
        assert!(matches!(outcome, WaitOutcome::Invalid), "alert wait ended with {:?}", outcome);
        assert!(waited < Duration::from_millis(1000), "alert wait took {:?}", waited);
        // The referencing wait was not ended by the close: it runs to its own timeout.
        assert!(matches!(holder.join().unwrap(), WaitOutcome::Timeout));
        close(other);
    }
}

#[cfg(test)]
mod r29_absolute_sleep_tests {
    use super::*;
    fn setup() { init(Some(std::env::temp_dir().join(format!("r29-sleep-{}.shm", std::process::id())).to_str().unwrap())).unwrap(); }
    fn tid() -> u32 { unsafe { libc::gettid() as u32 } }

    /// The futex sleep gets the caller's absolute deadline on the caller's clock, unchanged: a REALTIME deadline is slept on
    /// CLOCK_REALTIME by the kernel (FUTEX_CLOCK_REALTIME), so a clock step moves the wake-up instead of a length computed earlier.
    #[test]
    fn the_sleep_uses_the_absolute_deadline_on_its_own_clock() {
        setup();
        let e = create_event(false, false).unwrap();
        for clock in [libc::CLOCK_MONOTONIC, libc::CLOCK_REALTIME] {
            let deadline = Deadline::at(clock, clock_ns(clock) + 200_000_000);
            PRE_SLEEP_CAPTURE_TEST.set(true);
            FUTEX_DEADLINE_TEST.set(None);
            assert!(matches!(wait_any_until(&[e], tid(), Some(deadline), 0), WaitOutcome::Timeout));
            let op = libc::FUTEX_WAIT_BITSET | if clock == libc::CLOCK_REALTIME { libc::FUTEX_CLOCK_REALTIME } else { 0 };
            assert_eq!(FUTEX_DEADLINE_TEST.get(), Some((op, deadline.abs_ns)), "clock {clock}");
        }
        // No deadline: an unbounded sleep.
        // (The captured sleep returns at once; the wait then really sleeps until the event below ends it.)
        let t = std::thread::spawn(move || { PRE_SLEEP_CAPTURE_TEST.set(true); FUTEX_DEADLINE_TEST.set(Some((0, 1)));
            let outcome = wait_any(&[e], tid(), None, 0); (outcome, FUTEX_DEADLINE_TEST.get()) });
        std::thread::sleep(Duration::from_millis(100));
        event_set(e).unwrap();
        let (outcome, captured) = t.join().unwrap();
        assert!(matches!(outcome, WaitOutcome::Signaled { index: 0, .. }));
        assert_eq!(captured, None);
        assert!(close(e));
    }

    /// The real kernel call: an absolute REALTIME (FUTEX_CLOCK_REALTIME) and MONOTONIC sleep both end at their deadline, and a
    /// signal still ends them early.
    #[test]
    fn absolute_bitset_sleeps_end_on_time_and_wake_on_signal() {
        setup();
        let e = create_event(false, false).unwrap();
        for clock in [libc::CLOCK_REALTIME, libc::CLOCK_MONOTONIC] {
            let start = std::time::Instant::now();
            assert!(matches!(wait_any_until(&[e], tid(), Some(Deadline::at(clock, clock_ns(clock) + 50_000_000)), 0), WaitOutcome::Timeout));
            let waited = start.elapsed();
            assert!(waited >= Duration::from_millis(49) && waited < Duration::from_millis(90), "clock {clock}: waited {waited:?}");
            let setter = std::thread::spawn(move || { std::thread::sleep(Duration::from_millis(20)); event_set(e).unwrap(); });
            let start = std::time::Instant::now();
            assert!(matches!(wait_any_until(&[e], tid(), Some(Deadline::at(clock, clock_ns(clock) + 2_000_000_000)), 0), WaitOutcome::Signaled { index: 0, .. }));
            assert!(start.elapsed() < Duration::from_millis(500));
            setter.join().unwrap();
        }
        assert!(close(e));
    }
}

#[cfg(test)]
mod r30_spin_probe_tests {
    use super::*;
    #[test]
    fn the_probe_never_exceeds_the_cap() {
        for cap in 1..=7 { assert_eq!(probe_budget(cap), cap, "cap {cap}"); }
        assert_eq!(probe_budget(8), 8);
        assert_eq!(probe_budget(64), 8);
        assert_eq!(probe_budget(250), 31);
        assert_eq!(probe_budget(1000), 64);
        for cap in 1..=2000 { assert!(probe_budget(cap) <= cap); }
    }
    #[test]
    fn the_tid_is_the_threads_own() {
        let main = current_tid();
        assert_eq!(main, unsafe { libc::syscall(libc::SYS_gettid) as u32 });
        let other = std::thread::spawn(current_tid).join().unwrap();
        assert_ne!(main, other);
    }
}

#[cfg(test)]
mod r31_stress_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    fn setup() { init(Some(std::env::temp_dir().join(format!("r31-stress-{}.shm", std::process::id())).to_str().unwrap())).unwrap(); }
    fn tid() -> u32 { unsafe { libc::gettid() as u32 } }
    struct Rng(u64);
    impl Rng { fn next(&mut self) -> u64 { self.0 ^= self.0 << 13; self.0 ^= self.0 >> 7; self.0 ^= self.0 << 17; self.0 } }

    /// Semaphore units are conserved under many concurrent WaitAny / WaitAll consumers and releasers.
    #[test]
    fn semaphore_units_are_conserved_under_concurrent_waits() {
        setup();
        let sems: Arc<Vec<u32>> = Arc::new((0..6).map(|_| create_semaphore(0, 1_000_000).unwrap()).collect());
        let released = Arc::new(AtomicU64::new(0));
        let acquired = Arc::new(AtomicU64::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let mut threads = Vec::new();
        for p in 0..4u64 {
            let (sems, released) = (sems.clone(), released.clone());
            threads.push(std::thread::spawn(move || {
                let mut rng = Rng(0x9E3779B97F4A7C15 ^ (p + 1));
                for _ in 0..4000 {
                    let h = sems[(rng.next() % sems.len() as u64) as usize];
                    sem_release(h, 1).unwrap();
                    released.fetch_add(1, Ordering::Relaxed);
                    if rng.next() % 64 == 0 { std::thread::yield_now(); }
                }
            }));
        }
        for c in 0..6u64 {
            let (sems, acquired, stop) = (sems.clone(), acquired.clone(), stop.clone());
            threads.push(std::thread::spawn(move || {
                let mut rng = Rng(0xD1B54A32D192ED03 ^ (c + 11));
                let me = tid();
                while !stop.load(Ordering::Acquire) {
                    let n = 1 + (rng.next() % 3) as usize;
                    let start = (rng.next() % sems.len() as u64) as usize;
                    let picked: Vec<u32> = (0..n).map(|k| sems[(start + k) % sems.len()]).collect();
                    if rng.next() % 2 == 0 {
                        if let WaitOutcome::Signaled { .. } = wait_any(&picked, me, Some(Duration::from_millis(5)), 0) {
                            acquired.fetch_add(1, Ordering::Relaxed);
                        }
                    } else if let WaitOutcome::Signaled { .. } = wait_all(&picked, me, Some(Duration::from_millis(5)), 0) {
                        acquired.fetch_add(n as u64, Ordering::Relaxed);
                    }
                }
            }));
        }
        let producers: Vec<_> = threads.drain(..4).collect();
        for t in producers { t.join().unwrap(); }
        std::thread::sleep(Duration::from_millis(200));
        stop.store(true, Ordering::Release);
        for t in threads { t.join().unwrap(); }
        let remaining: u64 = sems.iter().map(|h| read_sem(*h).unwrap().0 as u64).sum();
        let (r, a) = (released.load(Ordering::Relaxed), acquired.load(Ordering::Relaxed));
        for h in sems.iter() { assert!(close(*h)); }
        assert_eq!(r, a + remaining, "released {r} acquired {a} remaining {remaining}");
        assert!(a > 0);
    }

    /// A mutex is never held by two threads at once, under contention with timeouts.
    #[test]
    fn a_mutex_is_never_held_by_two_threads() {
        setup();
        let m = create_mutex(0, 0).unwrap();
        let holders = Arc::new(AtomicUsize::new(0));
        let overlaps = Arc::new(AtomicUsize::new(0));
        let entries = Arc::new(AtomicUsize::new(0));
        let deadline = Instant::now() + Duration::from_millis(600);
        let threads: Vec<_> = (0..6).map(|_| {
            let (holders, overlaps, entries) = (holders.clone(), overlaps.clone(), entries.clone());
            std::thread::spawn(move || {
                let me = tid();
                while Instant::now() < deadline {
                    if let WaitOutcome::Signaled { .. } = wait_any(&[m], me, Some(Duration::from_millis(3)), 0) {
                        if holders.fetch_add(1, Ordering::AcqRel) != 0 { overlaps.fetch_add(1, Ordering::Relaxed); }
                        entries.fetch_add(1, Ordering::Relaxed);
                        std::hint::spin_loop();
                        holders.fetch_sub(1, Ordering::AcqRel);
                        mutex_unlock(m, me).unwrap();
                    }
                }
            })
        }).collect();
        for t in threads { t.join().unwrap(); }
        assert!(close(m));
        assert_eq!(overlaps.load(Ordering::Relaxed), 0);
        assert!(entries.load(Ordering::Relaxed) > 100);
    }

    /// An auto-reset event never wakes more waiters than it was set.
    #[test]
    fn an_auto_reset_event_wakes_at_most_once_per_set() {
        setup();
        let e = create_event(false, false).unwrap();
        let woken = Arc::new(AtomicU64::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let waiters: Vec<_> = (0..5).map(|_| {
            let (woken, stop) = (woken.clone(), stop.clone());
            std::thread::spawn(move || {
                let me = tid();
                while !stop.load(Ordering::Acquire) {
                    if let WaitOutcome::Signaled { .. } = wait_any(&[e], me, Some(Duration::from_millis(3)), 0) {
                        woken.fetch_add(1, Ordering::Relaxed);
                    }
                }
            })
        }).collect();
        let mut sets = 0u64;
        for i in 0..3000u64 {
            event_set(e).unwrap();
            sets += 1;
            if i % 7 == 0 { std::thread::sleep(Duration::from_micros(50)); }
        }
        std::thread::sleep(Duration::from_millis(100));
        stop.store(true, Ordering::Release);
        for t in waiters { t.join().unwrap(); }
        let still_set = read_event(e).unwrap().1 as u64;
        assert!(close(e));
        let w = woken.load(Ordering::Relaxed);
        assert!(w + still_set <= sets, "woken {w} + still set {still_set} > sets {sets}");
        assert!(w > 0);
    }
}
