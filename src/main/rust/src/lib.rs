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
