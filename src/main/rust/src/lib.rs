mod fs_monitor;

use jni::{Executor, JNIEnv};

use jni::objects::{GlobalRef, JClass, JMethodID, JObject, JString};

use jni::sys::{jboolean, jlong, jobject, jstring, jvalue, JNI_FALSE, JNI_TRUE};

use crate::fs_monitor::{Event, NativeEventStream};

struct Runnable {
    recv: GlobalRef,
    class: GlobalRef, // just making sure we don't lose reference to this Runnable class
    method: JMethodID,
    executor: Executor,
}



impl Runnable {
    pub fn new<'local>(env: &mut JNIEnv<'local>, local_recv: JObject<'local>) -> Result<Self, jni::errors::Error> {
        let recv = env.new_global_ref(local_recv)?;
        let class = env.new_global_ref(env.get_object_class(&recv)?)?;
        let method = env.get_method_id(&class, "run", "()V")?;
        let executor = Executor::new(Into::into(env.get_java_vm()?));

        Ok(Self {
            recv,
            class,
            method,
            executor
        })
    }

    pub fn run<'local>(&self) {
        self.executor.with_attached(|env: &mut JNIEnv<'_>| -> Result<(), jni::errors::Error> {
            unsafe{
                env.call_method_unchecked(
                    self.recv.as_obj(),
                    self.method,
                    jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                    &[])?;
                Ok(())
            }
        });
    }
}


#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_engineering_swat_watch_impl_mac_jni_FileSystemEvents_start<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    path: JString<'local>,
    signal: JObject<'local>,
) -> jlong {

    let signaller = Runnable::new(&mut env, signal).expect("We should be able to build a runnable reference");

    let mut mon = NativeEventStream::new(
      env.get_string(&path).expect("Should not fail to get string").into(),
      move || signaller.run()
    );
    mon.start();

    Box::into_raw(Box::new(mon)) as jlong
}


#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_engineering_swat_watch_impl_mac_jni_FileSystemEvents_stop<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    stream: jlong,
) {
    let mon_ptr = stream as *mut NativeEventStream;
    let mon = unsafe { Box::from_raw(mon_ptr) };
    mon.stop();
    // after this the mon will be released, as we took it back into the box
}


struct ArrayList<'local> {
    pub value: JObject<'local>,
    add_method: JMethodID
}

impl<'local> ArrayList<'local> {
    pub fn new(mut env: JNIEnv<'local>) -> Result<Self, jni::errors::Error> {
        let class = env.find_class("java/util/ArrayList")?;
        let value = env.new_object(&class, "()V", &[])?;
        let add_method = env.get_method_id(&class, "add", "(Ljava/lang/Object;)Z")?;
        Ok(Self {
            value,
            add_method
        })
    }

    pub fn add(&self, mut env: JNIEnv<'local>, val: JObject<'local>) -> Result<(), jni::errors::Error> {
        unsafe{
            env.call_method_unchecked(
                &self.value,
                &self.add_method,
                jni::signature::ReturnType::Primitive(jni::signature::Primitive::Boolean),
                &[jvalue { l: val.as_raw() }]
            )?;
            Ok(())
        }
    }
}

#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_engineering_swat_watch_impl_mac_jni_FileSystemEvents_pollEvents<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    stream: jlong,
) -> JObject<'local> {
    let mon_ptr = stream as *mut NativeEventStream;
    let mon = unsafe { &mut *mon_ptr };

    let result = ArrayList::new(env).expect("We should be able to allocate array list");
    // TODO: translate events to `WatchKey` instances (most likely our own version of them?)
    // and fill them into the result
    // get the events from mon.poll_all();
    return result.value;
}

#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_engineering_swat_watch_impl_mac_jni_FileSystemEvents_anyEvents<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    stream: jlong,
) -> jboolean {
    let mon_ptr = stream as *mut NativeEventStream;
    let mon = unsafe { &mut *mon_ptr };

    if mon.is_empty() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
