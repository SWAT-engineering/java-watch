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

mod fs_monitor;

use jni::{Executor, JNIEnv};
use jni::objects::{GlobalRef, JClass, JMethodID, JObject, JString, JValue};
use jni::sys::{jint, jlong};

use crate::fs_monitor::{Kind, NativeEventStream};

#[allow(dead_code)]
struct HandlerExecutor {
    executor: Executor,
    obj: GlobalRef,
    method: JMethodID,
    path: String,
    class: GlobalRef, // Ensure the reference to the class (of `obj`) isn't lost
}

impl HandlerExecutor {
    pub fn new<'local>(env: &mut JNIEnv<'local>, path: String, obj: JObject<'local>) -> Result<Self, jni::errors::Error> {
        let executor = Executor::new(Into::into(env.get_java_vm()?));
        let obj = env.new_global_ref(obj)?;
        let class = env.new_global_ref(env.get_object_class(&obj)?)?;
        let method = env.get_method_id(
            &class, "handle", "(ILjava/lang/String;Ljava/lang/String;)V")?;

        Ok(Self { executor, obj, method, path, class })
    }

    pub fn execute<'local>(&self, kind: Kind, path: &String) {
        self.executor.with_attached(|env: &mut JNIEnv<'_>| -> Result<(), jni::errors::Error> {
            unsafe {
                env.call_method_unchecked(
                    self.obj.as_obj(),
                    self.method,
                    jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                    &[
                        JValue::from(kind as jint).as_jni(),
                        JValue::from(&env.new_string(&self.path).unwrap()).as_jni(),
                        JValue::from(&env.new_string(&path).unwrap()).as_jni(),
                    ]
                )?;
            }
            Ok(())
        }).unwrap();
    }
}

#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_engineering_swat_watch_impl_mac_NativeLibrary_start<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    path: JString<'local>,
    handler: JObject<'local>,
) -> jlong
{
    let path: String = env.get_string(&path).expect("Should not fail to get string").into();
    let handler_executor = HandlerExecutor::new(&mut env, path.clone(), handler).unwrap();
    let handler = move |kind: Kind, path: &String| handler_executor.execute(kind, path);
    let mut mon = NativeEventStream::new(path, handler);
    mon.start();
    Box::into_raw(Box::new(mon)) as jlong
}

#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_engineering_swat_watch_impl_mac_NativeLibrary_stop<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    stream: jlong,
)
{
    let mon_ptr = stream as *mut NativeEventStream;
    let mon = unsafe { Box::from_raw(mon_ptr) };
    mon.stop();
    // After this, the mon will be released, as it has been taken out of the box
}
