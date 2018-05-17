/*
 * Copyright 2014-2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.standalone

import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.netflix.atlas.core.db.{Database, MemoryDatabase}
import com.netflix.atlas.core.model.{Datapoint, DefaultSettings, TagKey}
import com.netflix.atlas.core.norm.NormalizationCache
import com.netflix.atlas.core.validation.{Rule, ValidationResult}
import com.netflix.atlas.webapi.ApiSettings
import com.netflix.atlas.webapi.PublishApi.PublishRequest
import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.histogram.{BucketCounter, BucketFunctions}
import com.typesafe.scalalogging.StrictLogging
import io.netifi.proteus.Netifi
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
  private val clock = Clock.systemDefaultZone()

  protected def convert(proteusMeter: ProteusMeter, commonTags: Map[String, String]): List[Datapoint] = {
    logger.info("received metric {}", proteusMeter)
    val timestamp = clock.millis()
    var tags = commonTags
    val id = proteusMeter.getId
    tags += "name" -> id.getName

    id.getTagList
      .forEach(t => {
        tags += t.getKey -> t.getValue
      })

    if (id.getBaseUnit != null && !id.getBaseUnit.isEmpty) {
      tags += "unit" -> id.getBaseUnit
    }

    if (id.getDescription != null && !id.getDescription.isEmpty) {
      tags += "description" -> id.getBaseUnit
    }

     id.getType.name() match {
       case "GAUGE" => tags += TagKey.dsType -> "gauge"
       case "COUNTER" => tags += TagKey.dsType -> "counter"
    }

    asScalaBuffer(proteusMeter.getMeasureList)
      .map((mm: MeterMeasurement) => {
        val name = mm.getStatistic.getValueDescriptor.getName
        val current = tags + ("statistic" -> name)

        new Datapoint(current, timestamp, mm.getValue)
      })
      .toList
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
        val meters = snapshot.getMetersList

        var tags = Map[String, String]()
        commonTags
          .forEach((k, v) => {
            tags += (k -> v)
          })

        Flux
          .fromIterable(asScalaBuffer(meters))
          .flatMapIterable((meter: ProteusMeter) => convert(meter, tags))
      })
      .bufferTimeout(1000, Duration.create(1, TimeUnit.SECONDS))
      .subscribe(vs => {
        val request = validate(vs.toList)
        update(request.values)
        updateStats(request.failures)
      })

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

  private def updateStats(failures: List[ValidationResult]): Unit = {
    failures.foreach {
      case ValidationResult.Pass => // Ignored
      case ValidationResult.Fail(error, _) =>
        getRegistry().counter(numInvalid.withTag("error", error)).increment()
    }
  }

  private def update(vs: List[Datapoint]): Unit = {
    val now = System.currentTimeMillis()
    vs.foreach { v =>
      numReceived.record(now - v.timestamp)
      v.tags.get(TagKey.dsType) match {
        case Some("counter") => cache.updateCounter(v)
        case Some("gauge") => cache.updateGauge(v)
        case Some("rate") => cache.updateRate(v)
        case _ => cache.updateRate(v)
      }
    }
  }

  // Track the ages of data flowing into the system. Data is expected to arrive quickly and
  // should hit the backend within the step interval used.
  private val numReceived = {
    val f = BucketFunctions.age(DefaultSettings.stepSize, TimeUnit.MILLISECONDS)
    BucketCounter.get(getRegistry(), getRegistry().createId("atlas.db.numMetricsReceived"), f)
  }

  private val numInvalid = getRegistry().createId("atlas.db.numInvalid")

  private val cache = new NormalizationCache(DefaultSettings.stepSize, getMemDb().update)

  def getRegistry(): Registry

  def getMemDb(): MemoryDatabase
}

@Singleton
class ProteusProvider @Inject()(registry: Registry, db: Database)
  extends Provider[BaseMetricsSnapshotHandler]
    with BaseMetricsSnapshotHandler
    with StrictLogging {

  val accountId: Long = java.lang.Long.getLong("ACCOUNT_ID", 100)
  val minHostsAtStartup: Int = java.lang.Integer.getInteger("MIN_HOSTS_AT_STARTUP", 1)
  val poolSize: Int = Integer.getInteger("POOL_SIZE", 1)
  val accessKey: Long = java.lang.Long.getLong("ACCESS_KEY", 7685465987873703191L)
  val accessToken: String = System.getProperty("ACCESS_TOKEN", "PYYgV9XHSJ/3KqgK5wYjz+73MeA=")
  val host: String = System.getProperty("ROUTER_HOST", "localhost")
  val port: Int = Integer.getInteger("ROUTER_PORT", 8001)
  val destination: String = UUID.randomUUID.toString
  val group: String = System.getProperty("NETIFI_GROUP", "netifi.metrics")

  logger.info("host => {}", host)
  logger.info("port => {}", port)
  logger.info("group => {}", group)
  logger.info("destination => {}", destination)

  val netifi = Netifi
    .builder()
    .group(group)
    .accessKey(accessKey)
    .accessToken(accessToken)
    .accountId(accountId)
    .minHostsAtStartup(minHostsAtStartup)
    .poolSize(poolSize)
    .host(host)
    .build()

  netifi
    .addService(new MetricsSnapshotHandlerServer(this))

  override def get(): BaseMetricsSnapshotHandler = this

  override def getRegistry(): Registry = registry

  override def getMemDb(): MemoryDatabase = db.asInstanceOf[MemoryDatabase]
}

@Singleton
class StandAloneProvider @Inject()(registry: Registry, db: Database)
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

  override def getRegistry(): Registry = registry

  override def getMemDb(): MemoryDatabase = db.asInstanceOf[MemoryDatabase]
}

