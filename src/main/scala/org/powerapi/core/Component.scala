/**
 * This software is licensed under the GNU Affero General Public License, quoted below.
 *
 * This file is a part of PowerAPI.
 * 
 * Copyright (C) 2011-2014 Inria, University of Lille 1.
 * 
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI.

 * If not, please consult http://www.gnu.org/licenses/agpl-3.0.html.
 */
package org.powerapi.core

import akka.actor.{OneForOneStrategy, SupervisorStrategy, SupervisorStrategyConfigurator, ActorLogging, Actor}
import akka.actor.SupervisorStrategy.{Directive, Resume}
import akka.event.LoggingReceive
import scala.concurrent.duration.DurationInt

/**
 * Base trait for components which use Actor.
 *
 * @author Maxime Colmant <maxime.colmant@gmail.com>
 */
trait Component extends Actor with ActorLogging {
  /**
   * Default behavior when a received message is unknown.
   */
  def default: Actor.Receive = {
    case unknown => throw new UnsupportedOperationException(s"unable to process message $unknown")
  }
}

/**
 * Base trait for each PowerAPI sensor.
 * Each of them should react to a MonitorTick, sense informations and then publish a SensorReport.
 *
 * @author Maxime Colmant <maxime.colmant@gmail.com>
 */
abstract class Sensor(eventBus: MessageBus) extends Component {
  import org.powerapi.core.MonitorChannel.{MonitorTick, subscribeMonitorTick}

  override def preStart(): Unit = {
    subscribeMonitorTick(eventBus)(self)
  }

  def receive: PartialFunction[Any, Unit] = LoggingReceive {
    case msg: MonitorTick => sense(msg)
  } orElse default

  def sense(monitorTick: MonitorTick): Unit
}

/**
 * Base trait for each PowerAPI formula.
 * Each of them should react to a SensorReport, compute the power and then publish a PowerReport.
 *
 * @author Maxime Colmant <maxime.colmant@gmail.com>
 */
abstract class Formula(eventBus: MessageBus) extends Component {
  import org.powerapi.sensors.procfs.cpu.SensorReport

  type SR <: SensorReport

  override def preStart(): Unit = {
    subscribeSensorReport()
  }

  def receive: PartialFunction[Any, Unit] = LoggingReceive {
    case msg: SR => compute(msg)
  } orElse default

  def subscribeSensorReport(): Unit
  def compute(sensorReport: SR): Unit
}

/**
 * Supervisor strategy.
 *
 * @author Maxime Colmant <maxime.colmant@gmail.com>
 */
trait Supervisor extends Component {
  def handleFailure: PartialFunction[Throwable, Directive]

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(10, 1.seconds)(handleFailure orElse SupervisorStrategy.defaultStrategy.decider)
}

/**
 * This class is used for defining a default supervisor strategy for the Guardian Actor.
 * The Guardian Actor is the main actor used when system.actorOf(...) is used.
 *
 * @author Maxime Colmant <maxime.colmant@gmail.com>
 */
class GuardianFailureStrategy extends SupervisorStrategyConfigurator {
  def handleFailure: PartialFunction[Throwable, Directive] = {
    case _: UnsupportedOperationException => Resume
  }

  def create(): SupervisorStrategy = {
    OneForOneStrategy(10, 1.seconds)(handleFailure orElse SupervisorStrategy.defaultStrategy.decider)
  }
}
