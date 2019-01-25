package filodb.query.exec

import monix.reactive.Observable

import filodb.core.query._
import filodb.memory.format.{RowReader, ZeroCopyUTF8String}
import filodb.query.{Query, QueryConfig}

object HistogramQuantileMapper {
  import ZeroCopyUTF8String._
  val le = "le".utf8
}

/**
  * Calculates histogram quantile for one or more histograms whose bucket range vectors are passed
  * into the apply function.
  *
  * @param funcParams Needs one double quantile argument
  */
case class HistogramQuantileMapper(funcParams: Seq[Any]) extends RangeVectorTransformer {

  import HistogramQuantileMapper._
  require(funcParams.size == 1, "histogram_quantile function needs a single quantile argument")

  private val quantile = funcParams.head.asInstanceOf[Number].doubleValue()

  /**
    * Represents a prometheus histogram bucket for quantile calculation purposes.
    * @param le the less-than-equals boundary for histogram bucket
    * @param rate number of occurrences for the bucket per second
    */
  case class Bucket(val le: Double, var rate: Double) {
    override def toString: String = s"$le->$rate"
  }

  /**
    * Groups incoming bucket range vectors by histogram name. It then calculates quantile for each histogram
    * using the buckets supplied for it. It is assumed that each bucket value contains rate of increase for that
    * bucket.
    *
    * Important Note: The source range vectors for each bucket should NOT be the counter values themselves,
    * but should be the rate of increase for that bucket counter. The histogram_quantile function should always
    * be preceded by a rate function or a sum-of-rate function.
    */
  override def apply(source: Observable[RangeVector],
                     queryConfig: QueryConfig, limit: Int,
                     sourceSchema: ResultSchema): Observable[RangeVector] = {
    val res = source.toListL.map { rvs =>

      // first group the buckets by histogram
      val histograms = groupRangeVectorsByHistogram(rvs)

      // calculate quantile for each bucket
      val quantileResults = histograms.map { histBuckets =>

        // sort the bucket range vectors by increasing le tag value
        val sortedBucketRvs = histBuckets._2.toArray.map { bucket =>
          if (!bucket.key.labelValues.contains(le))
            throw new IllegalArgumentException("Cannot calculate histogram quantile" +
              s"because 'le' tag is absent in the time series ${bucket.key.labelValues}")
          val leStr = bucket.key.labelValues(le).toString
          val leDouble = if (leStr == "+Inf") Double.PositiveInfinity else leStr.toDouble
          leDouble -> bucket
        }.sortBy(_._1)

        val samples = sortedBucketRvs.map(_._2.rows)

        // The buckets here will be populated for each instant for quantile calculation
        val buckets = sortedBucketRvs.map { b => Bucket(b._1, 0d) }

        // create the result iterator that lazily produces quantile for each timestamp
        val quantileResult = new Iterator[RowReader] {
          val row = new TransientRow()
          override def hasNext: Boolean = samples.forall(_.hasNext)
          override def next(): RowReader = {
            for { i <- 0 until samples.length } {
              val nxt = samples(i).next()
              buckets(i).rate = nxt.getDouble(1)
              row.timestamp = nxt.getLong(0)
            }
            row.value = histogramQuantile(quantile, buckets)
            row
          }
        }
        IteratorBackedRangeVector(histBuckets._1, quantileResult)
      }
      Observable.fromIterable(quantileResults)
    }
    Observable.fromTask(res).flatten
  }

  /**
    * Given a bunch of range vectors, this function groups them after ignoring the le tag.
    * Function returns a map of the histogram range vector key to all its buckets.
    * This is essentially a helper function used to group relevant buckets together to calculate quantiles.
    */
  private def groupRangeVectorsByHistogram(rvs: Seq[RangeVector]): Map[CustomRangeVectorKey, Seq[RangeVector]] = {
    rvs.groupBy { rv =>
      val resultKey = rv.key.labelValues - le // remove the le tag from the labels
      CustomRangeVectorKey(resultKey)
    }
  }

  /**
    * Calculates histogram quantile using the bucket values.
    * Similar to prometheus implementation for consistent results.
    */
  private def histogramQuantile(q: Double, buckets: Array[Bucket]): Double = {
    val result = if (q < 0) Double.NegativeInfinity
    else if (q > 1) Double.PositiveInfinity
    else if (buckets.length < 2) Double.NaN
    else {
      if (!buckets.last.le.isPosInfinity) return Double.NaN
      else {
        makeMonotonic(buckets)
        // find rank for the quantile using total number of occurrences
        var rank = q * buckets.last.rate
        // using rank, find the le bucket which would have the identified rank
        val b = buckets.indexWhere(_.rate >= rank)

        // now calculate quantile
        if (b == buckets.length-1) return buckets(buckets.length-2).le
        else if (b == 0 && buckets.head.le <= 0) return buckets.head.le
        else {
          // interpolate quantile within le bucket
          var (bucketStart, bucketEnd, count) = (0d, buckets(b).le, buckets(b).rate)
          if (b > 0) {
            bucketStart = buckets(b-1).le
            count -= buckets(b-1).rate
            rank -= buckets(b-1).rate
          }
          bucketStart + (bucketEnd-bucketStart)*(rank/count)
        }
      }
    }
    Query.qLogger.debug(s"Quantile $q for buckets $buckets was $result")
    result
  }

  /**
    * Fixes any issue with monotonicity of supplied bucket rates.
    * Rates on increasing le buckets should monotonically increase. It may not be the case
    * if the bucket values are not atomically obtained from the same scrape,
    * or if bucket le values change over time causing NaN on missing buckets.
    */
  private def makeMonotonic(buckets: Array[Bucket]): Unit = {
    var max = 0d
    buckets.foreach { b =>
      // When bucket no longer used NaN will be seen. Non-increasing values can be seen when
      // newer buckets are introduced and not all instances are updated with that bucket.
      if (b.rate < max || b.rate.isNaN) b.rate = max // assign previous max
      else if (b.rate > max) max = b.rate // update max
    }
  }

  override protected[exec] def args: String = s"quantile=$quantile"
}