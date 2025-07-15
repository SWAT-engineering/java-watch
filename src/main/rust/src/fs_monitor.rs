#![cfg(target_os = "macos")]
#![deny(
    trivial_numeric_casts,
    unstable_features,
    unused_import_braces,
    unused_qualifications
)]

use crossbeam_channel as chan;

use std::{os::raw::{c_char, c_void}, sync::atomic::{AtomicBool, Ordering}};

use dispatch2::ffi::{dispatch_object_t, dispatch_queue_t, DISPATCH_QUEUE_SERIAL};
use core_foundation::{
    array::CFArray,
    base::{kCFAllocatorDefault, TCFType},
    string::CFString,
};
use fsevent_sys::{self as fs, kFSEventStreamCreateFlagFileEvents, kFSEventStreamCreateFlagNoDefer, kFSEventStreamCreateFlagWatchRoot};
use std::{
    ffi::CStr, ptr
};

struct CallbackContext {
    new_events: Box<dyn Fn()>,
    channel: chan::Sender<Event>
}

pub struct NativeEventStream {
    since_when: fs::FSEventStreamEventId,
    closed: AtomicBool,
    path : CFArray<CFString>,
    queue: dispatch_queue_t,
    stream: Option<fs::FSEventStreamRef>,
    receiver: chan::Receiver<Event>,
    sender_heap: *mut CallbackContext,
}


pub struct Event {
    pub id: fs::FSEventStreamEventId,
    pub flags: fs::FSEventStreamEventFlags,
    pub path: String
}



const FLAGS : fs::FSEventStreamCreateFlags
        = kFSEventStreamCreateFlagNoDefer
        | kFSEventStreamCreateFlagWatchRoot
        | kFSEventStreamCreateFlagFileEvents;


impl NativeEventStream {
    pub fn new(path: String, new_events: impl Fn() + 'static) -> Self {
        let (s, r) : (chan::Sender<Event>, chan::Receiver<Event>) = chan::unbounded();
        eprintln!("New watch requested for: {}", &path);
        Self {
            since_when: unsafe { fs::FSEventsGetCurrentEventId() },
            closed: AtomicBool::new(false),
            path: CFArray::from_CFTypes(&[CFString::new(&path)]),
            queue: unsafe { dispatch2::ffi::dispatch_queue_create(ptr::null(), DISPATCH_QUEUE_SERIAL)},
            stream: None,
            receiver: r,
            sender_heap: Box::into_raw(Box::new(CallbackContext{ new_events: Box::new(new_events), channel: s }))
        }
    }

    fn build_context(&mut self) -> *const fsevent_sys::FSEventStreamContext {
        eprintln!("ctx sending: {?}", self.sender_heap);
        &fs::FSEventStreamContext {
            version: 0,
            info: self.sender_heap as *mut _,
            retain: None,
            release: None,
            copy_description: None
        }
    }

    pub fn start(&mut self) {
        unsafe {
            eprintln!("Creating stream: {}", self.since_when);
            let stream = fs::FSEventStreamCreate(
                kCFAllocatorDefault,
                callback,
                self.build_context(),
                self.path.as_concrete_TypeRef(),
                self.since_when,
                0.15,
                FLAGS);

            self.stream = Some(stream);

            eprintln!("Connecting stream with queue");
            fs::FSEventStreamSetDispatchQueue(stream, self.queue);
            eprintln!("Starting stream");
            fs::FSEventStreamStart(stream);
        };
    }
    pub fn stop(&self) {
        if self.closed.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
            // we weren't the first one to close it
            return;
        }
        match self.stream {
            Some(stream) => unsafe{
                fs::FSEventStreamStop(stream);
                fs::FSEventStreamSetDispatchQueue(stream, ptr::null_mut());
                fs::FSEventStreamInvalidate(stream);
                dispatch2::ffi::dispatch_release(self.queue as dispatch_object_t);
                fs::FSEventStreamRelease(stream);
                drop(Box::from(self.sender_heap));
            }
            None => unsafe {
                dispatch2::ffi::dispatch_release(self.queue as dispatch_object_t);
            }
        };
    }

    pub(crate) fn poll_all(&self) -> chan::TryIter<Event> {
        self.receiver.try_iter()
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.receiver.is_empty()
    }

}

extern "C" fn callback(
    _stream_ref: fs::FSEventStreamRef,
    info: *mut c_void,
    num_events: usize,
    event_paths: *mut c_void,
    event_flags: *const fs::FSEventStreamEventFlags,
    event_ids: *const fs::FSEventStreamEventId,
) {
    let ctx = unsafe{ &mut *(info as *mut CallbackContext) };
    eprintln!("ctx restored: {?}", ctx);

    let event_paths = event_paths as *const *const c_char;

    for i in 0..num_events {
        unsafe{
            ctx.channel.send(Event {
                id: *event_ids.add(i),
                flags: *event_flags.add(i),
                path: CStr::from_ptr(*event_paths.add(i))
                    .to_str()
                    .expect("Invalid UTF8 string.")
                    .to_string()
            });
        }
    }

    if num_events > 0 {
        eprintln!("Sending message to java");
        (ctx.new_events)();
    }
}
