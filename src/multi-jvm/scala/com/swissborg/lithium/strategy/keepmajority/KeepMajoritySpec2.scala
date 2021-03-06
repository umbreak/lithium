package com.swissborg.lithium

package strategy

package keepmajority

import akka.remote.transport.ThrottlerTransportAdapter.Direction
import com.swissborg.lithium.TestUtil.linksToKillForPartitions

import scala.concurrent.duration._

class KeepMajoritySpec2MultiJvmNode1 extends KeepMajoritySpec2
class KeepMajoritySpec2MultiJvmNode2 extends KeepMajoritySpec2
class KeepMajoritySpec2MultiJvmNode3 extends KeepMajoritySpec2
class KeepMajoritySpec2MultiJvmNode4 extends KeepMajoritySpec2
class KeepMajoritySpec2MultiJvmNode5 extends KeepMajoritySpec2

sealed abstract class KeepMajoritySpec2 extends FiveNodeSpec("KeepMajority", KeepMajoritySpecFiveNodeConfig) {
  override def assertions(): Unit =
    "handle scenario 2" in within(60 seconds) {
      runOn(node1) {
        linksToKillForPartitions(List(node1, node2, node3) :: List(node4) :: List(node5) :: Nil)
          .foreach {
            case (from, to) => testConductor.blackhole(from, to, Direction.Both).await
          }
      }

      enterBarrier("links-failed")

      runOn(node1, node2, node3) {
        waitForSurvivors(node1, node2, node3)
        waitForAllLeaving(node4, node5)
      }

      runOn(node4, node5) {
        waitForSelfDowning
      }

      enterBarrier("split-brain-resolved")
    }
}
