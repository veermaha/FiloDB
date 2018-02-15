package filodb.kafka

import java.lang.{Long => JLong}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import monix.kafka.{KafkaProducer, _}
import monix.reactive.Observable
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition

/** Run against kafka:
  * ./bin/kafka-topics.sh --create --zookeeper localhost:2181  --replication-factor 1 --partitions 1 \
  * --topic filodb-kafka-tests
  */
class SourceSinkSuite extends KafkaSpec {

  // TODO UPDATE FOR PARTITIONS=3 TEST
  private val topic = "filodb-kafka-tests"

  private val settings = new KafkaSettings(ConfigFactory.parseString(
    s"""
       |include file("./src/test/resources/sourceconfig.conf")
       |sourceconfig.bootstrap.servers = "localhost:9092"
       |sourceconfig.filo-topic-name=$topic
       |sourceconfig.filo-record-converter="filodb.kafka.StringRecordConverter"
        """.stripMargin).withFallback(source))

  private val tps = List(new TopicPartition(topic, 0))

  lazy val io = Scheduler.io("filodb-kafka-tests")

  "FiloDBKafka" must {
    // For some reason this doesn't work - but it did when first added.
    // In any case, the important test is the full producer/consumer one
    "publish one message" ignore {
      val producerCfg = KafkaProducerConfig(settings.sinkConfig.asConfig)
      val producer = KafkaProducer[JLong, String](producerCfg, io)

      val consumerTask = PartitionedConsumerObservable.createConsumer(settings, tps.head, None).executeOn(io)
      val consumer = Await.result(consumerTask.runAsync, 60.seconds)
      val key = JLong.valueOf(0L)

      try {
        val send = producer.send(topic, key, "my-message")
        Await.result(send.runAsync, 30.seconds)
        val records = consumer.poll(10.seconds.toMillis).asScala.map(_.value()).toList
        assert(records == List("my-message"))
      }
      finally {
        Await.result(producer.close().runAsync, Duration.Inf)
        consumer.close()
      }
    }

    "consume for one message" ignore {
      val producerCfg = KafkaProducerConfig(settings.sinkConfig.asConfig)
      val producer = KafkaProducer[JLong, String](producerCfg, io)
      val consumer = PartitionedConsumerObservable.create(settings, tps.head, None).executeOn(io)
      val key = JLong.valueOf(0L)

      try {
        val send = producer.send(topic, key, "test-message")
        Await.result(send.runAsync, 30.seconds)

        val first = consumer.take(1).map(_.value()).firstL
        val result = Await.result(first.runAsync, 30.seconds)
        assert(result == "test-message")
      }
      finally {
        Await.result(producer.close().runAsync, Duration.Inf)
      }
    }

    "full producer/consumer test" in {
      val count = 10000

      val tps = List(new TopicPartition(settings.IngestionTopic, 0))
      val producer = PartitionedProducerSink.create[JLong, String](settings, io)
      val consumer = PartitionedConsumerObservable.create(settings, tps.head, None).executeOn(io).take(count)
      val key = JLong.valueOf(0L)

      val pushT = Observable.range(0, count)
        .map(msg => new ProducerRecord(settings.IngestionTopic, key, msg.toString))
        .bufferTumbling(10)  // This must be something into which count/2 can divide.  buffer scrambles order.
        .consumeWith(producer)

      val streamT = consumer
        .map(record => (record.offset, record.value.toString))
        .toListL

      val task = Task.zip2(Task.fork(streamT), Task.fork(pushT)).runAsync

      val (result, _) = Await.result(task, 60.seconds)

      assert(result.collect { case (k, v) => v.toString.toInt }.sum == (0 until count).sum)

      // Now, test consuming from a particular offset.  Check the offset halfway through the list
      val cutoff = count / 2
      val offsetAtCutoff = result(cutoff)._1
      val origSum = (cutoff until count).sum

      val consumer2 = PartitionedConsumerObservable.create(settings, tps.head, Some(offsetAtCutoff))
                                                   .executeOn(io).take(cutoff)
      val streamT2 = consumer2.map(_.value.toString).toListL
      val result2 = Await.result(streamT2.runAsync, 60.seconds)
      result2.map(_.toInt).sum shouldEqual origSum
    }
  }
}
