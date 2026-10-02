# The instrumentation APK loads this shared public tracing entry point by name.
# Production remains fully optimized; this exception applies only to releaseCheck.
-keep class androidx.tracing.Trace { *; }

# AndroidJUnitRunner/TestDirCalculator and ActivityScenario's shared runtime API.
# Exact missing method-owner classes observed by comparing both optimized APK DEX tables.
# Kotlin multifile facade superclasses supply inherited static methods, so retain those
# two facade chains as well. No app classes or Compose internals are exempted from R8.
-keep class androidx.concurrent.futures.CallbackToFutureAdapter { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.LazyKt__LazyKt { *; }
-keep class kotlin.LazyKt__LazyJVMKt { *; }
-keep class kotlin.ResultKt { *; }
-keep class kotlin.coroutines.ContinuationKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt { *; }
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt { *; }
-keep class kotlin.io.CloseableKt { *; }
-keep interface kotlin.reflect.KClass { *; }
-keep class kotlin.time.DurationKt { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.CancellableContinuation$DefaultImpls { *; }
-keep interface kotlinx.coroutines.Deferred { *; }
-keep class kotlinx.coroutines.ExecutorsKt { *; }
-keep class kotlinx.coroutines.Job$DefaultImpls { *; }
-keep class kotlinx.coroutines.TimeoutKt { *; }
