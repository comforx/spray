/*
 * Copyright (C) 2011, 2012 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.io.pipelines

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import annotation.tailrec
import collection.mutable.ArrayBuffer
import cc.spray.io.pipelines.SslBufferPool._


/**
 * A ByteBuffer pool reduces the number of ByteBuffer allocations in the SslTlsSupport.
 * The reason why SslTlsSupport requires a buffer pool is because the
 * current SSLEngine implementation always requires a 17KiB buffer for
 * every 'wrap' and 'unwrap' operation.  In most cases, the actual size of the
 * required buffer is much smaller than that, and therefore allocating a 17KiB
 * buffer for every 'wrap' and 'unwrap' operation wastes a lot of memory
 * bandwidth, resulting in application performance degradation.
 *
 * This implementation is very loosely based on the one from Netty.
 */
object SslBufferPool {

  // we are using Nettys default values:
  // 16665 + 1024 (room for compressed data) + 1024 (for OpenJDK compatibility)
  private val MaxPacketSize = 16665 + 2048

  private val Unlocked = 0
  private val Locked = 1

  private[this] val state = new AtomicInteger(Unlocked)
  private[this] val pool = new AtomicReference(List.empty[ByteBuffer])

  /**
   * Returns the size of the current buffer pool.
   */
  def size: Int = pool.get.size

  @tailrec
  def acquire(): ByteBuffer = {
    if (state.compareAndSet(Unlocked, Locked)) {
      try {
        pool.get match {
          case Nil => newBuffer // we have no more buffer available, so we need to create a new one
          case buf :: tail =>
            pool.set(tail)
            buf
        }
      } finally {
        state.set(Unlocked)
      }
    } else acquire() // spin while locked
  }

  @tailrec
  def release(buf: ByteBuffer) {
    if (state.compareAndSet(Unlocked, Locked)) {
      try {
        buf.clear() // ensure that we never have dirty buffers in the pool
        pool.set(buf :: pool.get)
      } finally {
        state.set(Unlocked)
      }
    } else release(buf) // spin while locked
  }

  private def newBuffer = ByteBuffer.allocate(MaxPacketSize)
}
