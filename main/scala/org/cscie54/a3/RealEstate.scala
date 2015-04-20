package org.cscie54.a3

import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger, AtomicReference}
import java.lang.IllegalStateException
import java.lang.IllegalArgumentException
import java.util.concurrent.locks.{ReentrantReadWriteLock, ReadWriteLock}
import scala.collection.mutable.Queue
import java.util.NoSuchElementException
import scala.util.{Success, Failure, Try}

trait RealEstateListings {

  /*
    Returns current price of a listing for given address.
    If a listing is not tracked, it returns java.lang.IllegalArgumentException, wrapped inside Failure projection of Try.
  */
  def getCurrentPrice(address: String): Try[Int]


  /*
    If less then N number of listings are currently tracked, adds the listing and returns Success[Unit]
    If N number of listings are already tracked, returns java.lang.IllegalStateException, wrapped inside Failure projection of Try.
    If a listing with a given address already exists, it returns java.lang.IllegalArgumentException, wrapped inside Failure projection of Try.
  */
  def tryAddListing(address: String, price: Int): Try[Unit]

  /*
    If a listing with a given address already exists, it returns java.lang.IllegalArgumentException, wrapped inside Failure projection of Try.
    If less than N number of listings are currently tracked, adds the listing.
    If N number of listings are already tracked, "saves it for later" and adds it when an open slot becomes available.
    Note: In the latter case, the service should automatically add a listing (in FIFO fashion) when some other listing is removed.
    The "saved for later" collection allows duplicate entries. However, when an item from this collection is to be added to the main collection of listings,
    but it turns out to be a duplicate, it can just silently fail - perform no operation. No need to throw an exception.
  */
  def addListing(address: String, price: Int): Try[Unit]

  /*
    Updates the price of an existing listing, determined by given address.
    If a listing does not exist, it returns java.lang.IllegalArgumentException, wrapped inside Failure projection of Try.
  */
  def updatePrice(address: String, price: Int): Try[Unit]

  /*
    Stops tracking a listing. Only listings that are actively being tracked are eligile for removal.
    That is, ones "saved for later inclusion" should not be removed.
    If a listing does not exist, it returns java.lang.IllegalArgumentException, wrapped inside Failure projection of Try.
  */
  def removeListing(address: String): Try[Unit]

  /*
    Returns the current number of listings being tracked.
  */
  def getTotalNumber: Int

  /*
    Returns total value (sum of individual prices) of all listings
  */
  def getTotalValue: Long

  /*
    Retrieves all listings, sorted by price in a descending order, as a collection of tuples,
    where the first tuple element is an address and second represents a price.
  */
  //def getAllSortedByPrice: Iterable[(String, Int)]
}


class RealEstateListingsImpl (realEstListingsNum: Int) extends RealEstateListings
{
  private val readWriteLockNum : ReadWriteLock = new ReentrantReadWriteLock()
  private val readLockNum = readWriteLockNum.readLock()
  private val writeLockNum = readWriteLockNum.writeLock()

  private val lock = new Object()

  private val readWriteLockTotVal : ReadWriteLock = new ReentrantReadWriteLock()
  private val readLockTotVal = readWriteLockTotVal.readLock()
  private val writeLockTotVal = readWriteLockTotVal.writeLock()

  private val trackedListingsLimitNum = realEstListingsNum

  //private val currentTrackedListingsNum = new AtomicInteger(0)

  private var currentTrackedListingsNum: Int = 0

  //private val totalValue = new AtomicLong(0)
  private var totalValue: Long = 0

  //private val listings = new AtomicReference(Map[String, Int]())
  private val concurrentMap = new ConcurrentHashMap[String, Int]()
  private val listings = new AtomicReference(concurrentMap)

  private val backLogListings = new AtomicReference(Queue[(String,Int)]())

  /**
   * atomically update the totalValue
   * @param value
   * @return
   */
  /*private def updateTotalValue(value: Int) = {
    var v: Long = 0
    // check for tracked listing number first
    do
    {
      v = totalValue.get()
    } while (!totalValue.compareAndSet(v, v+value))
  }*/
  private def updateTotalValue(value: Int) = {
    writeLockTotVal.lock()
    try
    {
      totalValue += value
    }
    finally
    {
      writeLockTotVal.unlock()
    }
  }


  /**
   * atomically update the currentTrackedListingsNum
   * @param ifIncrement, true to increment, false to decrement
   * @return
   */
  /*
  private def changeCurrentTrackedListingsNum(ifIncrement: Boolean): Int = {
    var v: Integer = 0
    // check for tracked listing number first
    if(ifIncrement)
    {
      do {
        v = currentTrackedListingsNum.get()
      } while (!currentTrackedListingsNum.compareAndSet(v, v + 1))
      v + 1
    }
    else
    {
      do
      {
        v = currentTrackedListingsNum.get()
      } while (!currentTrackedListingsNum.compareAndSet(v, v - 1))
      v - 1
    }
  }
  */
  private def changeCurrentTrackedListingsNum(ifIncrement: Boolean) = lock.synchronized
  {
    /*
    writeLockNum.lock()
    try
    {
      if (ifIncrement) {
        currentTrackedListingsNum +=1
      }
      else {
        currentTrackedListingsNum -=1
      }
    }
    finally{
      writeLockNum.unlock()
    }*/
    if (ifIncrement) {
      currentTrackedListingsNum = currentTrackedListingsNum + 1
    }
    else {
      currentTrackedListingsNum = currentTrackedListingsNum - 1
    }

  }

  private def getPrice (address: String): Int =
  {
    val tempMap = listings.get()
    tempMap.get(address) //match
    /*{
      case Some(result) => result
      case None => -1
    }*/
  }

  def getCurrentPrice(address: String): Try[Int] = Try
  {
    val result = getPrice(address)
    if(null == result)
    {
      Failure{new IllegalArgumentException}
    }
    result
  }

  def tryAddListing(address: String, price: Int): Try[Unit] = Try
  {
    val test = getTotalNumber
    if(test <= trackedListingsLimitNum)
    {
      var tempMap =  new ConcurrentHashMap[String, Int]()
      var newTempMap = new ConcurrentHashMap[String, Int]()//Map.empty[String, Int]
      do
      {
        tempMap = listings.get()
        newTempMap = listings.get()
        if(tempMap.contains(address))
        {
          // have to roll back the currentTrackedListingsNum if failure!
          changeCurrentTrackedListingsNum(false)

          return Try {Failure{new IllegalArgumentException}}
        }
        else
        {
           newTempMap.put(address, price)
        }
      }while (!listings.compareAndSet(tempMap, newTempMap))

      // update the total value of all real estate listings
      updateTotalValue(price)
      changeCurrentTrackedListingsNum(true)
    }
    else
    {
      return Try{Failure{new IllegalStateException}}
    }
  }

  def addListing(address: String, price: Int): Try[Unit] = Try
  {
    // check for tracked listing number first
    //if(changeCurrentTrackedListingsNum(true) <= trackedListingsLimitNum)
    val test = getTotalNumber
    if(test <= trackedListingsLimitNum)
    {
      var tempMap =  new ConcurrentHashMap[String, Int]() //Map.empty[String, Int]
      var newTempMap = new ConcurrentHashMap[String, Int]()//Map.empty[String, Int]
      do
      {
        tempMap = listings.get()
        newTempMap = listings.get()
        if(tempMap.contains(address))
        {
          // have to roll back the currentTrackedListingsNum if failure!
          //changeCurrentTrackedListingsNum(false)
          return Try{Failure{new IllegalArgumentException}}
        }
        else
        {
          //newTempMap = tempMap ++ Map(address -> price)
          newTempMap.put(address, price)
        }
      }while (!listings.compareAndSet(tempMap, newTempMap))

      updateTotalValue(price)
      changeCurrentTrackedListingsNum(true)
    }
    else
    {
      // add to back log of listings
      var tempBackLogListings = Queue.empty[(String,Int)]
      var newTempBackLogListings = Queue.empty[(String,Int)]
      do
      {
        tempBackLogListings = backLogListings.get()
        newTempBackLogListings ++= tempBackLogListings
        newTempBackLogListings.enqueue((address, price))

      }while (!backLogListings.compareAndSet(tempBackLogListings, newTempBackLogListings))
    }
  }

  def updatePrice(address: String, price: Int): Try[Unit] = Try
  {

  }

  def removeListing(address: String): Try[Unit] = Try {

    var tempMap =  new ConcurrentHashMap[String, Int]()//Map.empty[String, Int]
    var newTempMap = new ConcurrentHashMap[String, Int]()//Map.empty[String, Int]
    do
    {
      tempMap = listings.get()
      newTempMap = listings.get()
      if(!tempMap.contains(address))
      {
        // there is a concern when multiple threads are spawned
        // few of them set the value, one trying to remove the value other thread trying to set,
        // since the thread execution order is not guaranteed
        // the behaviour is not deterministic, it could throw the error
        return Try {Failure{new IllegalArgumentException}}
      }
      else
      {
        //newTempMap =
          newTempMap.remove(address)  //.-(address)
        //have to set update currentTrackedListingsNum
        changeCurrentTrackedListingsNum(false)
        // update total value

        val price = getCurrentPrice(address) match
        {
          case Success(price) => price
        }

        updateTotalValue(-price)

        //finally try to push backlogged listings
        if(getTotalNumber < trackedListingsLimitNum) {
          var tempBackLogListings = Queue.empty[(String, Int)]
          var newTempBackLogListings = Queue.empty[(String, Int)]
          var listingTuple = ("", 0)
          var ifFailed = false
          do {
            tempBackLogListings = backLogListings.get()
            newTempBackLogListings ++= tempBackLogListings
            try
            {
              listingTuple = newTempBackLogListings.dequeue()
            }
            catch
            {
              case ense: NoSuchElementException => ifFailed = true
            }
            finally {} //nothing to put here

          } while (!backLogListings.compareAndSet(tempBackLogListings, newTempBackLogListings))

          if (!ifFailed)
          {
            try
            {
              tryAddListing(listingTuple._1, listingTuple._2)
            }
            catch
            {
              case eia: IllegalArgumentException => // do nothing

              case eis: IllegalStateException => // do nothing
            }
            finally {} //nothing to put here
          }
        }
      }
    }while (!listings.compareAndSet(tempMap, newTempMap))

  }

  def getTotalNumber: Int = lock.synchronized
  {
    /*readLockNum.lock()
    try
    {
      currentTrackedListingsNum
    }
    finally
    {
      readLockNum.unlock()
    }*/
    currentTrackedListingsNum
  }

  def getTotalValue: Long = {

    readLockTotVal.lock()
    try
    {
      totalValue
    }
    finally
    {
      readLockTotVal.unlock()
    }

  }

  //def getAllSortedByPrice: Iterable[(String, Int)] = ???

}

