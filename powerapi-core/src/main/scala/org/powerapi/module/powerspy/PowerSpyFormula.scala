/*
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
 *
 * If not, please consult http://www.gnu.org/licenses/agpl-3.0.html.
 */
package org.powerapi.module.powerspy

import akka.event.LoggingReceive
import org.powerapi.core.{OSHelper, APIComponent, MessageBus}
import org.powerapi.core.MonitorChannel.{MonitorTick, subscribeMonitorTick}
import org.powerapi.core.power._
import org.powerapi.core.target.{Application, All, Process, TargetUsageRatio}
import org.powerapi.module.{Cache, CacheKey}
import org.powerapi.module.PowerChannel.publishRawPowerReport
import org.powerapi.module.powerspy.PowerSpyChannel.{PowerSpyPower, subscribePowerSpyPower}
import scala.reflect.ClassTag

/**
 * The overall power consumption is distributed among processes if
 * the target is Process/Application.
 *
 * The simple CpuSensor is used for getting the Target cpu usage ratio (UsageReport).
 *
 * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
 */
class PowerSpyFormula(eventBus: MessageBus, osHelper: OSHelper, idlePower: Power) extends APIComponent {

  override def preStart(): Unit = {
    subscribePowerSpyPower(eventBus)(self)
    subscribeMonitorTick(eventBus)(self)
    super.preStart()
  }

  def receive: PartialFunction[Any, Unit] = running(None)

  def running(pspyPower: Option[PowerSpyPower]): PartialFunction[Any, Unit] = LoggingReceive {
    case msg: PowerSpyPower => context.become(running(Some(msg)))
    case msg: MonitorTick => compute(pspyPower, msg)
  } orElse default

  // In order to compute the target's ratio
  lazy val cpuTimesCache = new Cache[(Long, Long)]

  def targetCpuUsageRatio(monitorTick: MonitorTick): TargetUsageRatio = {
    val key = CacheKey(monitorTick.muid, monitorTick.target)

    lazy val activeCpuTime = osHelper.getGlobalCpuTime.activeTime

    val processClaz = implicitly[ClassTag[Process]].runtimeClass
    val appClaz = implicitly[ClassTag[Application]].runtimeClass

    lazy val now = monitorTick.target match {
      case target if processClaz.isInstance(target) || appClaz.isInstance(target) => {
        lazy val targetCpuTime = osHelper.getTargetCpuTime(target) match {
          case Some(time) => time
          case _ => 0l
        }

        (targetCpuTime, activeCpuTime)
      }
    }

    val old = cpuTimesCache(key)(now)
    val diffTimes = (now._1 - old._1, now._2 - old._2)

    diffTimes match {
      case diff: (Long, Long) => {
        if(old == now) {
          cpuTimesCache(key) = now
          TargetUsageRatio(0.0)
        }

        else if (diff._1 > 0 && diff._2 > 0 && diff._1 <= diff._2) {
          cpuTimesCache(key) = now
          TargetUsageRatio(diff._1.toDouble / diff._2)
        }

        else TargetUsageRatio(0.0)
      }
      case _ => TargetUsageRatio(0.0)
    }
  }

  def compute(pSpyPower: Option[PowerSpyPower], monitorTick: MonitorTick): Unit = {
    pSpyPower match {
      case Some(pPower) => {
        lazy val dynamicP = if(pPower.power.toMilliWatts - idlePower.toMilliWatts > 0) pPower.power - idlePower else 0.W

        monitorTick.target match {
          case All => publishRawPowerReport(monitorTick.muid, monitorTick.target, pPower.power, "PowerSpy", monitorTick.tick)(eventBus)
          case _ => publishRawPowerReport(monitorTick.muid, monitorTick.target, dynamicP * targetCpuUsageRatio(monitorTick).ratio, "PowerSpy", monitorTick.tick)(eventBus)
        }
      }
      case _ => log.debug("{}", "no PowerSpyPower message received")
    }
  }
}
