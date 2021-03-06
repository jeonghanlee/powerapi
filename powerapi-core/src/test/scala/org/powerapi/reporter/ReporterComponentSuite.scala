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
package org.powerapi.reporter

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import java.util.UUID
import org.powerapi.UnitTest
import org.powerapi.core.MessageBus
import org.powerapi.core.target.{Process, Target}
import org.powerapi.core.ClockChannel.ClockTick
import org.powerapi.core.power._
import org.powerapi.module.PowerChannel.{ AggregatePowerReport, RawPowerReport, render, subscribeAggPowerReport }
import scala.concurrent.duration.DurationInt

class ReporterComponentSuite(system: ActorSystem) extends UnitTest(system) {

  def this() = this(ActorSystem("ReporterComponentSuite"))

  implicit val timeout = Timeout(1.seconds)

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
  }

  trait Bus {
    val eventBus = new MessageBus
  }

  "A reporter component" should "process PowerReport messages" in new Bus {
    val muid = UUID.randomUUID()
    val target: Target = 1
    val device = "mock"
    val tickMock = ClockTick("ticktest", 25.milliseconds)
    val aggFunction = (s: Seq[Power]) => s.foldLeft(0.0.W){ (acc, p) => acc + p }
  
    subscribeAggPowerReport(muid)(eventBus)(testActor)
    
    val aggR = AggregatePowerReport(muid, aggFunction)
    aggR += RawPowerReport("topictest", muid, Process(1), 1.W, device, tickMock)
    aggR += RawPowerReport("topictest", muid, Process(2), 2.W, device, tickMock)
    aggR += RawPowerReport("topictest", muid, Process(3), 3.W, device, tickMock)
    
    render(aggR)(eventBus)
    
    val msg = expectMsgClass(classOf[AggregatePowerReport])
    msg.targets should equal(Set(Process(1), Process(2), Process(3)))
    msg.devices should equal(Set("mock"))
    msg.power should equal(6.W)
  }
}

