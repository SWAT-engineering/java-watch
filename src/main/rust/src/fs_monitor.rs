//
// BSD 2-Clause License
//
// Copyright (c) 2023, Swat.engineering
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//

#![cfg(target_os = "macos")]
#![deny(
    trivial_numeric_casts,
    unstable_features,
    unused_import_braces,
    unused_qualifications
)]

use std::{
    ffi::CStr,
    ptr,
    fs,
    os::raw::{c_char, c_void},
    sync::atomic::{AtomicBool, Ordering},
};
use dispatch2::ffi::{dispatch_object_t, dispatch_queue_t, DISPATCH_QUEUE_SERIAL, dispatch_queue_create};
use core_foundation::{
    array::CFArray,
    base::{kCFAllocatorDefault, TCFType},
    string::CFString,
};
use fsevent_sys::{self as fse};

pub enum Kind { // Ordinals need to be consistent with enum `Kind` in Java
    OVERFLOW=0,
    CREATE=1,
    DELETE=2,
    MODIFY=3,
}

pub struct NativeEventStream {
    since_when: fse::FSEventStreamEventId,
    closed: AtomicBool,
    path : CFArray<CFString>,
    queue: dispatch_queue_t,
    stream: Option<fse::FSEventStreamRef>,
    info: *mut ContextInfo,
}

impl NativeEventStream {
    pub fn new(path: String, handler: impl Fn(Kind, &String) + 'static) -> Self {
        Self {
            since_when: unsafe { fse::FSEventsGetCurrentEventId() },
            closed: AtomicBool::new(false),
            path: CFArray::from_CFTypes(&[CFString::new(&path)]),
            queue: unsafe { dispatch_queue_create(ptr::null(), DISPATCH_QUEUE_SERIAL) },
            stream: None,
            info: Box::into_raw(Box::new(ContextInfo{ handler: Box::new(handler) })),
        }
    }

    pub fn start(&mut self) {
        unsafe {
            let stream = fse::FSEventStreamCreate(
                kCFAllocatorDefault,
                callback,
                &fse::FSEventStreamContext {
                    version: 0,
                    info: self.info as *mut _,
                    retain: None,
                    release: Some(release_context),
                    copy_description: None
                },
                self.path.as_concrete_TypeRef(),
                self.since_when,
                0.15,
                FLAGS);

            self.stream = Some(stream);

            fse::FSEventStreamSetDispatchQueue(stream, self.queue);
            fse::FSEventStreamStart(stream);
        };
    }

    pub fn stop(&self) {
        if self.closed.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
            return; // The stream has already been closed
        }
        match self.stream {
            Some(stream) => unsafe{
                fse::FSEventStreamStop(stream);
                fse::FSEventStreamSetDispatchQueue(stream, ptr::null_mut());
                fse::FSEventStreamInvalidate(stream);
                dispatch2::ffi::dispatch_release(self.queue as dispatch_object_t);
                fse::FSEventStreamRelease(stream);
            }
            None => unsafe {
                dispatch2::ffi::dispatch_release(self.queue as dispatch_object_t);
            }
        };
    }
}

struct ContextInfo {
    handler: Box<dyn Fn(Kind, &String)>,
}

const FLAGS : fse::FSEventStreamCreateFlags
        = fse::kFSEventStreamCreateFlagNoDefer
        | fse::kFSEventStreamCreateFlagWatchRoot
        | fse::kFSEventStreamCreateFlagFileEvents;

extern "C" fn release_context(info: *mut c_void) {
  let ctx_ptr = info as *mut ContextInfo;
  unsafe{ drop(Box::from_raw( ctx_ptr)); }
}

extern "C" fn callback(
    _stream_ref: fse::FSEventStreamRef,
    info: *mut c_void,
    num_events: usize,
    event_paths: *mut c_void,
    event_flags: *const fse::FSEventStreamEventFlags,
    _event_ids: *const fse::FSEventStreamEventId,
) {
    let info = unsafe{ &mut *(info as *mut ContextInfo) };
    let handler = info.handler.as_ref();

    let event_paths = event_paths as *const *const c_char;
    for i in 0..num_events {
        // TODO: We're currently going from C strings to Rust strings to JNI
        // strings. If possible, go directly from C strings to JNI strings.
        let path = unsafe { CStr::from_ptr(*event_paths.add(i)).to_str().unwrap().to_string() };
        let flags: fse::FSEventStreamEventFlags = unsafe { *event_flags.add(i) };

        // Note: Multiple "physical" native events might be coalesced into a
        // single "logical" native event, so the following series of checks
        // should be if-statements (instead of if/else-statements).
        if flags & fse::kFSEventStreamEventFlagItemCreated != 0 {
            handler(Kind::CREATE, &path);
        }
        if flags & fse::kFSEventStreamEventFlagItemRemoved != 0 {
            handler(Kind::DELETE, &path);
        }
        if flags & (fse::kFSEventStreamEventFlagItemModified | fse::kFSEventStreamEventFlagItemInodeMetaMod) != 0 {
            handler(Kind::MODIFY, &path);
        }
        if flags & fse::kFSEventStreamEventFlagMustScanSubDirs != 0 {
            handler(Kind::OVERFLOW, &path);
        }
        if flags & fse::kFSEventStreamEventFlagItemRenamed != 0 {
            // For now, check if the file exists to determine if the event
            // pertains to the target of the rename (if it exists) or to the
            // source (else). This is an approximation. It might be more
            // accurate to maintain an internal index (but getting the
            // concurrency right requires care).
            if fs::exists(&path).unwrap_or(false) {
                handler(Kind::CREATE, &path);
            } else {
                handler(Kind::DELETE, &path);
            }
        }
    }
}
