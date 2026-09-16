package com.cabin.carlink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A session owns a binding to Cabin's private engine process. Close ends that process. */
internal class CarlinkEngineConnection private constructor(private val context: Context) : Closeable {
    private val ready = CompletableFuture<IBinder>()
    private val dead = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private var bound = false
    @Volatile private var host: IBinder? = null
    lateinit var engine: IBinder
        private set
    private val death = IBinder.DeathRecipient { dead.countDown() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed.get()) return
            host = service
            try {
                service.linkToDeath(death, 0)
                ready.complete(service)
            } catch (error: Exception) { ready.completeExceptionally(error) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            ready.completeExceptionally(IllegalStateException("Carlink engine process stopped"))
            dead.countDown()
        }
        override fun onNullBinding(name: ComponentName) {
            ready.completeExceptionally(IllegalStateException("Carlink engine binding was refused"))
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (bound) context.unbindService(connection)
        // Prevent a retry from binding to the retiring process before it has exited.
        if (host != null && Looper.myLooper() != Looper.getMainLooper()) dead.await(5, TimeUnit.SECONDS)
        host?.let { runCatching { it.unlinkToDeath(death, 0) } }
    }

    companion object {
        fun open(context: Context): CarlinkEngineConnection {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Start Carlink off the main thread" }
            val session = CarlinkEngineConnection(context.applicationContext)
            val initializer = Executors.newSingleThreadExecutor()
            try {
                session.bound = session.context.bindService(
                    Intent(session.context, CarlinkEngineService::class.java), session.connection, Context.BIND_AUTO_CREATE)
                check(session.bound) { "Cannot bind Cabin's Carlink engine" }
                val host = session.ready.get(10, TimeUnit.SECONDS)
                session.engine = initializer.submit<IBinder> { create(host) }.get(15, TimeUnit.SECONDS)
                return session
            } catch (error: Exception) {
                session.close()
                throw IllegalStateException(error.cause?.message ?: error.message ?: "Carlink engine startup timed out", error)
            } finally { initializer.shutdownNow() }
        }

        internal fun create(host: IBinder): IBinder {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(CarlinkEngineService.DESCRIPTOR)
                check(host.transact(CarlinkEngineService.CREATE, request, reply, 0)) { "Carlink engine host rejected startup" }
                reply.readException()
                return checkNotNull(reply.readStrongBinder()) { "Carlink engine returned no receiver" }
            } finally { request.recycle(); reply.recycle() }
        }
    }
}
