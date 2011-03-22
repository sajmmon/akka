/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.AkkaException
import akka.actor.{Actor, EventHandler}
import akka.routing.Dispatcher
import akka.japi.{ Procedure, Function => JFunc }

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent. {ConcurrentLinkedQueue, TimeUnit, Callable}
import java.util.concurrent.TimeUnit.{NANOSECONDS => NANOS, MILLISECONDS => MILLIS}
import java.util.concurrent.atomic. {AtomicBoolean, AtomicInteger}
import annotation.tailrec

class FutureTimeoutException(message: String) extends AkkaException(message)

object Futures {

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T]): Future[T] =
    Future(body.call)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], timeout: Long): Future[T] =
    Future(body.call, timeout)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], dispatcher: MessageDispatcher): Future[T] =
    Future(body.call)(dispatcher)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], timeout: Long, dispatcher: MessageDispatcher): Future[T] =
    Future(body.call, timeout)(dispatcher)

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T](futures: Iterable[Future[T]], timeout: Long = Long.MaxValue): Future[T] = {
    val futureResult = new DefaultCompletableFuture[T](timeout)

    val completeFirst: Future[T] => Unit = _.value.foreach(futureResult complete _)
    for(f <- futures) f onComplete completeFirst

    futureResult
  }

  /**
   * Java API
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T <: AnyRef](futures: java.lang.Iterable[Future[T]], timeout: Long): Future[T] =
    firstCompletedOf(scala.collection.JavaConversions.asScalaIterable(futures),timeout)

  /**
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T,R](zero: R, timeout: Long = Actor.TIMEOUT)(futures: Iterable[Future[T]])(foldFun: (R, T) => R): Future[R] = {
    if(futures.isEmpty) {
      new AlreadyCompletedFuture[R](Right(zero))
    } else {
      val result = new DefaultCompletableFuture[R](timeout)
      val results = new ConcurrentLinkedQueue[T]()
      val allDone = futures.size

      val aggregate: Future[T] => Unit = f => if (!result.isCompleted) { //TODO: This is an optimization, is it premature?
        f.value.get match {
          case r: Right[Throwable, T] =>
            results add r.b
            if (results.size == allDone) { //Only one thread can get here
              try {
                result completeWithResult scala.collection.JavaConversions.asScalaIterable(results).foldLeft(zero)(foldFun)
              } catch {
                case e: Exception =>
                  EventHandler.error(e, this, e.getMessage)
                  result completeWithException e
              } finally {
                results.clear
              }
            }
          case l: Left[Throwable, T] =>
            result completeWithException l.a
            results.clear
        }
      }

      futures foreach { _ onComplete aggregate }
      result
    }
  }

  /**
   * Java API
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T <: AnyRef, R <: AnyRef](zero: R, timeout: Long, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] =
    fold(zero, timeout)(scala.collection.JavaConversions.asScalaIterable(futures))( fun.apply _ )

  /**
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T, R >: T](futures: Iterable[Future[T]], timeout: Long = Actor.TIMEOUT)(op: (R,T) => T): Future[R] = {
    if (futures.isEmpty)
      new AlreadyCompletedFuture[R](Left(new UnsupportedOperationException("empty reduce left")))
    else {
      val result = new DefaultCompletableFuture[R](timeout)
      val seedFound = new AtomicBoolean(false)
      val seedFold: Future[T] => Unit = f => {
        if (seedFound.compareAndSet(false, true)) { //Only the first completed should trigger the fold
          f.value.get match {
            case r: Right[Throwable, T] =>
              result.completeWith(fold(r.b, timeout)(futures.filterNot(_ eq f))(op))
            case l: Left[Throwable, T] =>
              result.completeWithException(l.a)
          }
        }
      }
      for(f <- futures) f onComplete seedFold //Attach the listener to the Futures
      result
    }
  }

  /**
   * Java API
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], timeout: Long, fun: akka.japi.Function2[R, T, T]): Future[R] =
    reduce(scala.collection.JavaConversions.asScalaIterable(futures), timeout)(fun.apply _)

  import scala.collection.mutable.Builder
  import scala.collection.generic.CanBuildFrom

  def sequence[A, M[_] <: Traversable[_]](in: M[Future[A]], timeout: Long = Actor.TIMEOUT)(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] =
    in.foldLeft(new DefaultCompletableFuture[Builder[A, M[A]]](timeout).completeWithResult(cbf(in)): Future[Builder[A, M[A]]])((fr, fa) => for (r <- fr; a <- fa.asInstanceOf[Future[A]]) yield (r += a)).map(_.result)

  def traverse[A, B, M[_] <: Traversable[_]](in: M[A], timeout: Long = Actor.TIMEOUT)(fn: A => Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Future[M[B]] =
    in.foldLeft(new DefaultCompletableFuture[Builder[B, M[B]]](timeout).completeWithResult(cbf(in)): Future[Builder[B, M[B]]]) { (fr, a) =>
      val fb = fn(a.asInstanceOf[A])
      for (r <- fr; b <-fb) yield (r += b)
    }.map(_.result)

  //Deprecations


  /**
   * (Blocking!)
   */
  @deprecated("Will be removed after 1.1, if you must block, use: futures.foreach(_.await)")
  def awaitAll(futures: List[Future[_]]): Unit = futures.foreach(_.await)

  /**
   *  Returns the First Future that is completed (blocking!)
   */
  @deprecated("Will be removed after 1.1, if you must block, use: firstCompletedOf(futures).await")
  def awaitOne(futures: List[Future[_]], timeout: Long = Long.MaxValue): Future[_] = firstCompletedOf[Any](futures, timeout).await


  /**
   * Applies the supplied function to the specified collection of Futures after awaiting each future to be completed
   */
  @deprecated("Will be removed after 1.1, if you must block, use: futures map { f => fun(f.await) }")
  def awaitMap[A,B](in: Traversable[Future[A]])(fun: (Future[A]) => B): Traversable[B] =
    in map { f => fun(f.await) }

  /**
   * Returns Future.resultOrException of the first completed of the 2 Futures provided (blocking!)
   */
  @deprecated("Will be removed after 1.1, if you must block, use: firstCompletedOf(List(f1,f2)).await.resultOrException")
  def awaitEither[T](f1: Future[T], f2: Future[T]): Option[T] = firstCompletedOf[T](List(f1,f2)).await.resultOrException
}

object Future {
  /**
   * This method constructs and returns a Future that will eventually hold the result of the execution of the supplied body
   * The execution is performed by the specified Dispatcher.
   */
  def apply[T](body: => T, timeout: Long = Actor.TIMEOUT)(implicit dispatcher: MessageDispatcher): Future[T] = {
    val f = new DefaultCompletableFuture[T](timeout)
    dispatcher.dispatchFuture(FutureInvocation(f.asInstanceOf[CompletableFuture[Any]], () => body))
    f
  }
}

sealed trait Future[+T] {

  /**
   * Returns the result of this future after waiting for it to complete,
   * this method will throw any throwable that this Future was completed with
   * and will throw a java.util.concurrent.TimeoutException if there is no result
   * within the Futures timeout
   */
  def apply(): T = this.await.resultOrException.get

  /**
   * Java API for apply()
   */
  def get: T = apply()

  /**
   * Blocks the current thread until the Future has been completed or the
   * timeout has expired. In the case of the timeout expiring a
   * FutureTimeoutException will be thrown.
   */
  def await : Future[T]

  /**
   * Blocks the current thread until the Future has been completed. Use
   * caution with this method as it ignores the timeout and will block
   * indefinitely if the Future is never completed.
   */
  def awaitBlocking : Future[T]

  /**
   * Tests whether this Future has been completed.
   */
  final def isCompleted: Boolean = value.isDefined

  /**
   * Tests whether this Future's timeout has expired.
   *
   * Note that an expired Future may still contain a value, or it may be
   * completed with a value.
   */
  def isExpired: Boolean

  /**
   * This Future's timeout in nanoseconds.
   */
  def timeoutInNanos: Long

  /**
   * The contained value of this Future. Before this Future is completed
   * the value will be None. After completion the value will be Some(Right(t))
   * if it contains a valid result, or Some(Left(error)) if it contains
   * an exception.
   */
  def value: Option[Either[Throwable, T]]

  /**
   * Returns the successful result of this Future if it exists.
   */
  final def result: Option[T] = {
    val v = value
    if (v.isDefined) v.get.right.toOption
    else None
  }

  /**
   * Waits for the completion of this Future, then returns the completed value.
   * If the Future's timeout expires while waiting a FutureTimeoutException
   * will be thrown.
   *
   * Equivalent to calling future.await.value.
   */
  def awaitValue: Option[Either[Throwable, T]]

  /**
   * Returns the result of the Future if one is available within the specified
   * time, if the time left on the future is less than the specified time, the
   * time left on the future will be used instead of the specified time.
   * returns None if no result, Some(Right(t)) if a result, or
   * Some(Left(error)) if there was an exception
   */
  def valueWithin(time: Long, unit: TimeUnit): Option[Either[Throwable, T]]

  /**
   * Returns the contained exception of this Future if it exists.
   */
  final def exception: Option[Throwable] = {
    val v = value
    if (v.isDefined) v.get.left.toOption
    else None
  }

  /**
   * When this Future is completed, apply the provided function to the
   * Future. If the Future has already been completed, this will apply
   * immediatly.
   */
  def onComplete(func: Future[T] => Unit): Future[T]

  /**
   * When the future is compeleted with a valid result, apply the provided
   * PartialFunction to the result.
   */
  final def receive(pf: PartialFunction[Any, Unit]): Future[T] = onComplete { f =>
    val optr = f.result
    if (optr.isDefined) {
      val r = optr.get
      if (pf.isDefinedAt(r)) pf(r)
    }
  }

  /**
   * Creates a new Future by applying a PartialFunction to the successful
   * result of this Future if a match is found, or else return a MatchError.
   * If this Future is completed with an exception then the new Future will
   * also contain this exception.
   */
  final def collect[A](pf: PartialFunction[Any, A]): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        fa complete {
          if (v.isLeft) v.asInstanceOf[Either[Throwable, A]]
          else {
            try {
              val r = v.right.get
              if (pf isDefinedAt r) Right(pf(r))
              else Left(new MatchError(r))
            } catch {
              case e: Exception =>
                EventHandler.error(e, this, e.getMessage)
                Left(e)
            }
          }
        }
      }
    }
    fa
  }

  /**
   * Creates a new Future by applying a function to the successful result of
   * this Future. If this Future is completed with an exception then the new
   * Future will also contain this exception.
   */
  final def map[A](f: T => A): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        if (v.isLeft)
          fa complete v.asInstanceOf[Either[Throwable, A]]
        else {
          fa complete (try {
            Right(f(v.right.get))
          } catch {
            case e: Exception => 
              EventHandler.error(e, this, e.getMessage)
              Left(e)
          })
        }
      }
    }
    fa
  }

  /**
   * Creates a new Future by applying a function to the successful result of
   * this Future, and returns the result of the function as the new Future.
   * If this Future is completed with an exception then the new Future will
   * also contain this exception.
   */
  final def flatMap[A](f: T => Future[A]): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        if (v.isLeft)
          fa complete v.asInstanceOf[Either[Throwable, A]]
        else {
          try {
            fa.completeWith(f(v.right.get))
          } catch {
            case e: Exception => 
              EventHandler.error(e, this, e.getMessage)
              fa completeWithException e
          }
        }
      }
    }
    fa
  }

  final def foreach(f: T => Unit): Unit = onComplete { ft =>
    val optr = ft.result
    if (optr.isDefined)
      f(optr.get)
  }

  final def filter(p: T => Boolean): Future[T] = {
    val f = new DefaultCompletableFuture[T](timeoutInNanos, NANOS)
    onComplete { ft =>
      val optv = ft.value
      if (optv.isDefined) {
        val v = optv.get
        if (v.isLeft)
          f complete v
        else {
          val r = v.right.get
          f complete (try {
            if (p(r)) Right(r)
            else Left(new MatchError(r))
          } catch {
            case e: Exception => 
              EventHandler.error(e, this, e.getMessage)
              Left(e)
          })
        }
      }
    }
    f
  }

  /**
   *   Returns the current result, throws the exception is one has been raised, else returns None
   */
  final def resultOrException: Option[T] = {
    val v = value
    if (v.isDefined) {
      val r = v.get
      if (r.isLeft) throw r.left.get
      else r.right.toOption
    } else None
  }

  /* Java API */
  final def onComplete[A >: T](proc: Procedure[Future[A]]): Future[T] = onComplete(proc(_))

  final def map[A >: T, B](f: JFunc[A,B]): Future[B] = map(f(_))

  final def flatMap[A >: T, B](f: JFunc[A,Future[B]]): Future[B] = flatMap(f(_))

  final def foreach[A >: T](proc: Procedure[A]): Unit = foreach(proc(_))

  final def filter[A >: T](p: JFunc[A,Boolean]): Future[T] = filter(p(_))

}

/**
 * Essentially this is the Promise (or write-side) of a Future (read-side)
 */
trait CompletableFuture[T] extends Future[T] {
  /**
   * Completes this Future with the specified result, if not already completed,
   * returns this
   */
  def complete(value: Either[Throwable, T]): CompletableFuture[T]

  /**
   * Completes this Future with the specified result, if not already completed,
   * returns this
   */
  final def completeWithResult(result: T): CompletableFuture[T] = complete(Right(result))

  /**
   * Completes this Future with the specified exception, if not already completed,
   * returns this
   */
  final def completeWithException(exception: Throwable): CompletableFuture[T] = complete(Left(exception))

  /**
   * Completes this Future with the specified other Future, when that Future is completed,
   * unless this Future has already been completed
   * returns this
   */
  final def completeWith(other: Future[T]): CompletableFuture[T] = {
    other onComplete { f => complete(f.value.get) }
    this
  }

  /**
   * Alias for complete(Right(value))
   */
  final def << (value: T): CompletableFuture[T] = complete(Right(value))

  /**
   * Alias for completeWith(other)
   */
  final def << (other : Future[T]): CompletableFuture[T] = completeWith(other)
}

/**
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */
class DefaultCompletableFuture[T](timeout: Long, timeunit: TimeUnit) extends CompletableFuture[T] {

  def this() = this(0, MILLIS)

  def this(timeout: Long) = this(timeout, MILLIS)

  val timeoutInNanos = timeunit.toNanos(timeout)
  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _value: Option[Either[Throwable, T]] = None
  private var _listeners: List[Future[T] => Unit] = Nil

  @tailrec
  private def awaitUnsafe(wait: Long): Boolean = {
    if (_value.isEmpty && wait > 0) {
      val start = currentTimeInNanos
      val remaining = try {
        _signal.awaitNanos(wait)
      } catch {
        case e: InterruptedException =>
          wait - (currentTimeInNanos - start)
      }
      awaitUnsafe(remaining)
    } else {
      _value.isDefined
    }
  }

  def awaitValue: Option[Either[Throwable, T]] = {
    _lock.lock
    try {
      awaitUnsafe(timeoutInNanos - (currentTimeInNanos - _startTimeInNanos))
      _value
    } finally {
      _lock.unlock
    }
  }

  def valueWithin(time: Long, unit: TimeUnit): Option[Either[Throwable, T]] = {
    _lock.lock
    try {
      awaitUnsafe(unit.toNanos(time).min(timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)))
      _value
    } finally {
      _lock.unlock
    }
  }

  def await = {
    _lock.lock
    if (try { awaitUnsafe(timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)) } finally { _lock.unlock }) this
    else throw new FutureTimeoutException("Futures timed out after [" + NANOS.toMillis(timeoutInNanos) + "] milliseconds")
  }

  def awaitBlocking = {
    _lock.lock
    try {
      while (_value.isEmpty) {
        _signal.await
      }
      this
    } finally {
      _lock.unlock
    }
  }

  def isExpired: Boolean = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos) <= 0

  def value: Option[Either[Throwable, T]] = {
    _lock.lock
    try {
      _value
    } finally {
      _lock.unlock
    }
  }

  def complete(value: Either[Throwable, T]): DefaultCompletableFuture[T] = {
    _lock.lock
    val notifyTheseListeners = try {
      if (_value.isEmpty) {
        _value = Some(value)
        val existingListeners = _listeners
        _listeners = Nil
        existingListeners
      } else Nil
    } finally {
      _signal.signalAll
      _lock.unlock
    }

    if (notifyTheseListeners.nonEmpty)
      notifyTheseListeners.reverse foreach notify

    this
  }

  def onComplete(func: Future[T] => Unit): CompletableFuture[T] = {
    _lock.lock
    val notifyNow = try {
      if (_value.isEmpty) {
        _listeners ::= func
        false
      } else true
    } finally {
      _lock.unlock
    }

    if (notifyNow) notify(func)

    this
  }

  private def notify(func: Future[T] => Unit) {
    try {
      func(this)
    } catch {
      case e => EventHandler notify EventHandler.Error(e, this)
    }
  }

  private def currentTimeInNanos: Long = MILLIS.toNanos(System.currentTimeMillis)
}

/**
 * An already completed Future is seeded with it's result at creation, is useful for when you are participating in
 * a Future-composition but you already have a value to contribute.
 */
sealed class AlreadyCompletedFuture[T](suppliedValue: Either[Throwable, T]) extends CompletableFuture[T] {
  val value = Some(suppliedValue)

  def complete(value: Either[Throwable, T]): CompletableFuture[T] = this
  def onComplete(func: Future[T] => Unit): Future[T] = { func(this); this }
  def awaitValue: Option[Either[Throwable, T]] = value
  def valueWithin(time: Long, unit: TimeUnit): Option[Either[Throwable, T]] = value
  def await : Future[T] = this
  def awaitBlocking : Future[T] = this
  def isExpired: Boolean = true
  def timeoutInNanos: Long = 0
}
