package com.netflix.atlas.standalone

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSelection, ActorSystem}
import com.netflix.atlas.core.model.Datapoint
import com.netflix.atlas.core.validation.{Rule, ValidationResult}
import com.netflix.atlas.webapi.ApiSettings
import com.netflix.atlas.webapi.PublishApi.PublishRequest
import com.typesafe.scalalogging.StrictLogging
import io.netifi.proteus.metrics.om.{MetricsSnapshotHandlerServer, _}
import io.netty.buffer.ByteBuf
import io.rsocket.transport.netty.server.TcpServerTransport
import io.rsocket.{ConnectionSetupPayload, RSocket, RSocketFactory, SocketAcceptor}
import javax.inject.{Inject, Provider, Singleton}
import org.reactivestreams.Publisher
import reactor.core.publisher
import reactor.core.scala.publisher.{Flux, Mono}

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

trait BaseMetricsSnapshotHandler extends MetricsSnapshotHandler with StrictLogging {
  private val duration = Duration.create(30, TimeUnit.SECONDS)
  private val rules = ApiSettings.validationRules

  protected def convert(meterMeasurement: MeterMeasurement, commonTags: Map[String, String]): Datapoint = {
    var tags = commonTags
    val id = meterMeasurement.getId
    tags += "name" -> id.getName

    id.getTagList
      .forEach(t => {
        tags += t.getKey -> t.getValue
      })

    logger.debug("received metric {}", meterMeasurement)
    new Datapoint(tags, meterMeasurement.getTimestamp, meterMeasurement.getValue)
  }

  protected def validate(vs: List[Datapoint]): PublishRequest = {
    val validDatapoints = List.newBuilder[Datapoint]
    val failures = List.newBuilder[ValidationResult]
    val now = System.currentTimeMillis()
    val limit = ApiSettings.maxDatapointAge
    vs.foreach { v =>
      val diff = now - v.timestamp
      val result = diff match {
        case d if d > limit =>
          val msg = s"data is too old: now = $now, timestamp = ${v.timestamp}, $d > $limit"
          ValidationResult.Fail("DataTooOld", msg)
        case d if d < -limit =>
          val msg = s"data is from future: now = $now, timestamp = ${v.timestamp}"
          ValidationResult.Fail("DataFromFuture", msg)
        case _ =>
          Rule.validate(v.tags, rules)
      }
      if (result.isSuccess) validDatapoints += v else failures += result
    }
    PublishRequest(validDatapoints.result(), failures.result())
  }

  override def streamMetrics(publisher: Publisher[MetricsSnapshot], byteBuf: ByteBuf): reactor.core.publisher.Flux[Skew] = {
    val disposable = Flux
      .from(publisher)
      .flatMap(snapshot => {
        val commonTags = snapshot.getTagsMap
        val metrics = snapshot.getMetricsList

        var tags = Map[String, String]()
        commonTags
          .forEach((k, v) => {
            tags += (k -> v)
          })

        Flux
          .fromIterable(asScalaBuffer(metrics))
          .map((metric: MeterMeasurement) => convert(metric, tags))
      })
      .subscribe()

    Flux
      .interval(duration)
      .map(_ =>
        Skew
          .newBuilder()
          .setTimestamp(System.currentTimeMillis())
          .build())
      .doFinally(_ => disposable.dispose())
      .asJava()
  }
}

@Singleton
class StandAloneProvider @Inject()(implicit val system: ActorSystem)
  extends Provider[BaseMetricsSnapshotHandler]
    with BaseMetricsSnapshotHandler
    with StrictLogging {

  val handler: RSocket = new MetricsSnapshotHandlerServer(this)

  val transport = TcpServerTransport.create("127.0.0.1", 9800)

  RSocketFactory
    .receive()
    .acceptor(new SocketAcceptor {
      override def accept(setup: ConnectionSetupPayload, sendingSocket: RSocket): publisher.Mono[RSocket] = {
        Mono.just(handler).asJava()
      }
    })
    .transport(transport)
    .start()
    .doOnError(t => logger.error(t.getMessage, t))
    .subscribe()

  logger.info("started proteus standalone lister on port 9800")

  override def get(): BaseMetricsSnapshotHandler = this
}

