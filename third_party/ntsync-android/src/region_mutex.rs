// SPDX-License-Identifier: LGPL-3.0-only
//! Bionic-compatible process-shared lock. Android does not expose POSIX
//! robust mutexes and its app seccomp filter excludes robust-list syscalls.
//! Use atomic PID/TID ownership and shared futex wakeups instead. A killed
//! holder returns EOWNERDEAD; core poisons the region rather than trusting
//! a possibly half-mutated waiter list. The next wineserver starts fresh.
use std::ptr;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};

#[repr(C, align(8))]
pub struct Lock { owner: AtomicU64, sequence: AtomicU32, sleepers: AtomicU32 }
pub unsafe fn init(lock: *mut Lock) -> i32 {
    ptr::write(lock, Lock { owner: AtomicU64::new(0), sequence: AtomicU32::new(0), sleepers: AtomicU32::new(0) });
    0
}
fn identity() -> u64 {
    let pid = unsafe { libc::getpid() } as u64;
    #[cfg(target_os = "android")]
    let tid = unsafe { libc::gettid() } as u64; // Bionic reads its cached thread ID.
    #[cfg(not(target_os = "android"))]
    let tid = unsafe { libc::syscall(libc::SYS_gettid) } as u64;
    (pid << 32) | tid
}
unsafe fn acquire(lock: *mut Lock, deadline: Option<libc::timespec>) -> i32 {
    let me = identity(); let l = &*lock;
    let mut spin = 256; // bounded once per acquire; never an idle busy loop
    let mut probe_owner = true; // preserve immediate dead-owner recovery
    loop {
        let sequence = l.sequence.load(Ordering::Acquire);
        let owner = match l.owner.compare_exchange(0, me, Ordering::Acquire, Ordering::Relaxed) {
            Ok(_) => return 0, Err(owner) => owner,
        };
        if owner == me { return libc::EDEADLK; }
        if spin != 0 { spin -= 1; std::hint::spin_loop(); continue; }
        // A wake or EAGAIN is not evidence of death. Probe initially and
        // after a heartbeat, rather than issuing tgkill on every retry.
        if probe_owner {
            probe_owner = false;
            #[cfg(test)]
            PROBES.fetch_add(1, Ordering::Relaxed);
            if libc::syscall(libc::SYS_tgkill, (owner >> 32) as libc::pid_t, owner as libc::pid_t, 0) != 0
                && super::errno() == libc::ESRCH {
                if l.owner.compare_exchange(owner, me, Ordering::Acquire, Ordering::Relaxed).is_ok() {
                    return libc::EOWNERDEAD;
                }
                probe_owner = true;
                continue;
            }
        }
        // Heartbeat bounds dead-owner detection; ordinary contention wakes
        // immediately on release. No periodic background thread is created.
        let mut timeout = libc::timespec { tv_sec: 0, tv_nsec: 100_000_000 };
        if let Some(end) = deadline {
            let mut now = libc::timespec { tv_sec: 0, tv_nsec: 0 };
            libc::clock_gettime(libc::CLOCK_REALTIME, &mut now);
            let mut sec = end.tv_sec - now.tv_sec;
            let mut ns = end.tv_nsec - now.tv_nsec;
            if ns < 0 { ns += 1_000_000_000; sec -= 1; }
            if sec < 0 || sec == 0 && ns == 0 { return libc::ETIMEDOUT; }
            if sec == 0 && ns < timeout.tv_nsec { timeout.tv_nsec = ns; }
        }
        l.sleepers.fetch_add(1, Ordering::AcqRel);
        // Close the register-vs-unlock race before entering the kernel.
        let ret = if l.owner.load(Ordering::Acquire) != 0 {
            libc::syscall(libc::SYS_futex, &l.sequence, libc::FUTEX_WAIT, sequence, &timeout, 0, 0)
        } else { 0 };
        let e = if ret < 0 { super::errno() } else { 0 };
        l.sleepers.fetch_sub(1, Ordering::AcqRel);
        if e == libc::ETIMEDOUT { probe_owner = true; }
        if e != 0 && e != libc::EAGAIN && e != libc::EINTR && e != libc::ETIMEDOUT { return e; }
    }
}
/// Signal-handler cleanup never waits, spins, probes owners or allocates.
pub unsafe fn try_lock(lock: *mut Lock) -> i32 {
    match (*lock).owner.compare_exchange(0, identity(), Ordering::Acquire, Ordering::Relaxed) {
        Ok(_) => 0, Err(_) => libc::EBUSY,
    }
}
pub unsafe fn lock(lock: *mut Lock) -> i32 { acquire(lock, None) }
pub unsafe fn timed_lock(lock: *mut Lock, ts: *const libc::timespec) -> i32 { acquire(lock, Some(*ts)) }
pub unsafe fn consistent(_: *mut Lock) -> i32 { 0 }
pub unsafe fn unlock(lock: *mut Lock) -> i32 {
    let l = &*lock;
    if l.owner.load(Ordering::Relaxed) != identity() { return libc::EPERM; }
    l.owner.store(0, Ordering::Release);
    l.sequence.fetch_add(1, Ordering::Release);
    if l.sleepers.load(Ordering::Acquire) != 0 {
        // The 100 ms heartbeat also guarantees progress if the woken
        // successor dies before acquiring; ordinary unlock wakes one.
        #[cfg(test)]
        LAST_WAKE.store(1, Ordering::Relaxed);
        libc::syscall(libc::SYS_futex, &l.sequence, libc::FUTEX_WAKE, 1, 0, 0, 0);
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn killed_holder_is_detected_without_root_or_robust_list_syscalls() { unsafe {
        let lock = libc::mmap(ptr::null_mut(), 4096, libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_ANONYMOUS | libc::MAP_SHARED, -1, 0) as *mut Lock;
        assert_ne!(lock as *mut libc::c_void, libc::MAP_FAILED);
        assert_eq!(init(lock), 0);
        let mut pipe = [0; 2]; assert_eq!(libc::pipe(pipe.as_mut_ptr()), 0);
        let pid = libc::fork(); assert!(pid >= 0);
        if pid == 0 {
            libc::close(pipe[0]);
            if self::lock(lock) != 0 { libc::_exit(3); }
            libc::write(pipe[1], b"1".as_ptr().cast(), 1);
            loop { libc::pause(); }
        }
        libc::close(pipe[1]);
        let mut byte = 0u8; assert_eq!(libc::read(pipe[0], (&mut byte as *mut u8).cast(), 1), 1);
        assert_eq!(libc::kill(pid, libc::SIGKILL), 0);
        let mut status = 0; libc::waitpid(pid, &mut status, 0);
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        libc::clock_gettime(libc::CLOCK_REALTIME, &mut ts); ts.tv_sec += 2;
        assert_eq!(timed_lock(lock, &ts), libc::EOWNERDEAD);
        assert_eq!(unlock(lock), 0);
        assert_eq!(self::lock(lock), 0); assert_eq!(unlock(lock), 0);
        libc::close(pipe[0]); libc::munmap(lock.cast(), 4096);
    } }
    #[test]
    fn dead_thread_in_live_process_is_detected() { unsafe {
        let lock = Box::into_raw(Box::new(std::mem::zeroed::<Lock>()));
        let address = lock as usize;
        std::thread::spawn(move || { assert_eq!(self::lock(address as *mut Lock), 0); }).join().unwrap();
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        libc::clock_gettime(libc::CLOCK_REALTIME, &mut ts); ts.tv_sec += 2;
        assert_eq!(timed_lock(lock, &ts), libc::EOWNERDEAD);
        assert_eq!(unlock(lock), 0); drop(Box::from_raw(lock));
    } }
}

#[cfg(test)]
mod contention_tests {
    use super::*;
    #[test]
    fn eight_threads_serialize_40000_updates() {
        struct State { lock: Lock, value: std::cell::UnsafeCell<u64> }
        unsafe impl Sync for State {}
        let state = std::sync::Arc::new(State {
            lock: Lock { owner: AtomicU64::new(0), sequence: AtomicU32::new(0), sleepers: AtomicU32::new(0) },
            value: std::cell::UnsafeCell::new(0),
        });
        let threads: Vec<_> = (0..8).map(|_| {
            let s = state.clone(); std::thread::spawn(move || {
                let p = &s.lock as *const Lock as *mut Lock;
                for _ in 0..5000 { unsafe {
                    assert_eq!(lock(p), 0); *s.value.get() += 1; assert_eq!(unlock(p), 0);
                } }
            })
        }).collect();
        for t in threads { t.join().unwrap(); }
        assert_eq!(unsafe { *state.value.get() }, 40000);
    }
}

#[cfg(test)]
mod heartbeat_tests {
    use super::*;
    use std::sync::Arc;
    fn new_lock() -> Arc<Lock> { unsafe {
        let p = Arc::new(std::mem::zeroed());
        assert_eq!(init(Arc::as_ptr(&p) as *mut Lock), 0); p
    } }
    fn deadline(ms: i64) -> libc::timespec { unsafe {
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        libc::clock_gettime(libc::CLOCK_REALTIME, &mut ts);
        ts.tv_nsec += ms * 1_000_000;
        ts.tv_sec += ts.tv_nsec / 1_000_000_000; ts.tv_nsec %= 1_000_000_000; ts
    } }
    #[test]
    fn live_owner_is_not_stolen_when_short_deadline_expires() {
        let p = new_lock(); unsafe { assert_eq!(lock(Arc::as_ptr(&p) as *mut Lock), 0); }
        let q = p.clone();
        let r = std::thread::spawn(move || unsafe {
            timed_lock(Arc::as_ptr(&q) as *mut Lock, &deadline(20))
        }).join().unwrap();
        assert_eq!(r, libc::ETIMEDOUT);
        unsafe { assert_eq!(unlock(Arc::as_ptr(&p) as *mut Lock), 0); }
    }
    #[test]
    fn dead_owner_is_recovered_even_at_an_expired_deadline() {
        let p = new_lock(); let q = p.clone();
        std::thread::spawn(move || unsafe {
            assert_eq!(lock(Arc::as_ptr(&q) as *mut Lock), 0);
        }).join().unwrap();
        unsafe {
            assert_eq!(timed_lock(Arc::as_ptr(&p) as *mut Lock, &deadline(0)), libc::EOWNERDEAD);
            assert_eq!(unlock(Arc::as_ptr(&p) as *mut Lock), 0);
        }
    }
    #[test]
    fn normal_unlock_wakes_a_waiter_before_the_heartbeat() {
        let p = new_lock(); unsafe { assert_eq!(lock(Arc::as_ptr(&p) as *mut Lock), 0); }
        let q = p.clone();
        let t = std::thread::spawn(move || unsafe {
            assert_eq!(timed_lock(Arc::as_ptr(&q) as *mut Lock, &deadline(1000)), 0);
            assert_eq!(unlock(Arc::as_ptr(&q) as *mut Lock), 0);
        });
        while p.sleepers.load(Ordering::Acquire) == 0 { std::thread::yield_now(); }
        unsafe { assert_eq!(unlock(Arc::as_ptr(&p) as *mut Lock), 0); }
        t.join().unwrap();
    }
}

#[cfg(test)]
static LAST_WAKE: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static PROBES: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
mod r10_lock_tests {
 use super::*;
 use std::sync::Arc;
 fn new_lock()->Arc<Lock>{let p=Arc::new(unsafe{std::mem::zeroed()});unsafe{init(Arc::as_ptr(&p) as *mut Lock);}p}
 #[test] fn unlock_requests_only_one_kernel_wake(){
  let p=new_lock();unsafe{assert_eq!(lock(Arc::as_ptr(&p) as *mut Lock),0);}
  let q=p.clone();let t=std::thread::spawn(move||unsafe{assert_eq!(lock(Arc::as_ptr(&q) as *mut Lock),0);assert_eq!(unlock(Arc::as_ptr(&q) as *mut Lock),0);});
  while p.sleepers.load(Ordering::Acquire)==0{std::thread::yield_now();}
  LAST_WAKE.store(0,Ordering::Relaxed);unsafe{assert_eq!(unlock(Arc::as_ptr(&p) as *mut Lock),0);}
  let n=LAST_WAKE.load(Ordering::Relaxed);t.join().unwrap();assert_eq!(n,1);
 }
 #[test] fn spurious_wakes_do_not_probe_the_live_owner_each_time(){
  let p=new_lock();unsafe{assert_eq!(lock(Arc::as_ptr(&p) as *mut Lock),0);}PROBES.store(0,Ordering::Relaxed);
  let q=p.clone();let t=std::thread::spawn(move||unsafe{assert_eq!(lock(Arc::as_ptr(&q) as *mut Lock),0);assert_eq!(unlock(Arc::as_ptr(&q) as *mut Lock),0);});
  for _ in 0..25{while p.sleepers.load(Ordering::Acquire)==0{std::thread::yield_now();}
   unsafe{libc::syscall(libc::SYS_futex,&p.sequence,libc::FUTEX_WAKE,1,0,0,0);}std::thread::sleep(std::time::Duration::from_millis(1));}
  let probes=PROBES.load(Ordering::Relaxed);unsafe{assert_eq!(unlock(Arc::as_ptr(&p) as *mut Lock),0);}t.join().unwrap();assert!(probes<=2,"{probes} probes for 25 non-heartbeat wakes");
 }
 #[test] fn heartbeat_recovers_when_the_designated_wakeup_is_lost(){
  // Simulate a dying successor: suppress the release notification while a
  // real waiter is blocked, then require heartbeat progress without a wake.
  let p=new_lock();unsafe{assert_eq!(lock(Arc::as_ptr(&p) as *mut Lock),0);}
  let q=p.clone();let start=std::time::Instant::now();let t=std::thread::spawn(move||unsafe{
   assert_eq!(lock(Arc::as_ptr(&q) as *mut Lock),0);assert_eq!(unlock(Arc::as_ptr(&q) as *mut Lock),0);
  });
  while p.sleepers.load(Ordering::Acquire)==0{std::thread::yield_now();}
  p.owner.store(0,Ordering::Release);p.sequence.fetch_add(1,Ordering::Release);
  t.join().unwrap();assert!(start.elapsed()<std::time::Duration::from_secs(1));
 }
}
