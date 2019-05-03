package akka.cluster.sbr

import akka.actor.Address
import akka.cluster.UniqueAddress
import akka.cluster.sbr.SBRFailureDetector._
import org.scalatest.{Matchers, WordSpec}

class SBRFailureDetectorStateSuite extends WordSpec with Matchers {
  val aa = UniqueAddress(Address("akka.tcp", "sys", "a", 2552), 1L)
  val bb = UniqueAddress(Address("akka.tcp", "sys", "b", 2552), 2L)
  val cc = UniqueAddress(Address("akka.tcp", "sys", "c", 2552), 3L)
  val dd = UniqueAddress(Address("akka.tcp", "sys", "d", 2552), 4L)
  val ee = UniqueAddress(Address("akka.tcp", "sys", "e", 2552), 5L)

  "SBRFailureDetectorState" must {
    "be reachable when empty" in {
      val s = SBRFailureDetectorState.empty
      s.updatedStatus(aa)._1 should ===(Some(Reachable))

      val (status, s2) = s.updatedStatus(bb)
      status should ===(Some(Reachable))

      s2.updatedStatus(bb)._1 should ===(None)
    }

    "be unreachable when there is no contention" in {
      val s = SBRFailureDetectorState.empty.unreachable(aa, bb)
      s.updatedStatus(bb)._1 should ===(Some(Unreachable))

      val s1 = SBRFailureDetectorState.empty.reachable(bb).unreachable(aa, bb)
      s1.updatedStatus(bb)._1 should ===(Some(Unreachable))
    }

    "be indirectly connected when there is a contention" in {
      val s = SBRFailureDetectorState.empty.contention(bb, cc, dd, 1)
      s.updatedStatus(dd)._1 should ===(Some(IndirectlyConnected))
    }

    "be unreachable when a contention is resolved" in {
      val s = SBRFailureDetectorState.empty.contention(aa, cc, dd, 1).unreachable(aa, dd)
      s.updatedStatus(dd)._1 should ===(Some(Unreachable))
    }

    "be unreachable only when all the contentions are resolved" in {
      val s =
        SBRFailureDetectorState.empty.contention(cc, aa, bb, 1).contention(cc, dd, bb, 1).contention(ee, aa, bb, 1)

      val (status, s1) = s.updatedStatus(bb)
      status should ===(Some(IndirectlyConnected))

      val s2            = s1.unreachable(cc, bb)
      val (status2, s3) = s2.updatedStatus(bb)
      status2 should ===(None)

      val s4 = s3.unreachable(ee, bb)
      s4.updatedStatus(bb)._1 should ===(Some(Unreachable))
    }

    "ignore a contention for old versions" in {
      val s = SBRFailureDetectorState.empty.contention(ee, aa, bb, 2).contention(cc, aa, bb, 1)

      val s1 = s.unreachable(cc, bb)
      s1.updatedStatus(bb)._1 should ===(Some(IndirectlyConnected))
    }

    "reset a contention when there is a new version" in {
      val s = SBRFailureDetectorState.empty.contention(cc, aa, bb, 1).contention(ee, aa, bb, 2)

      val s1 = s.unreachable(ee, bb)
      s1.updatedStatus(bb)._1 should ===(Some(Unreachable))
    }

    "update a contention when it is for the current version" in {
      val s = SBRFailureDetectorState.empty.contention(cc, aa, bb, 1).contention(ee, aa, bb, 1)

      val s1 = s.unreachable(cc, bb).unreachable(ee, bb)
      s1.updatedStatus(bb)._1 should ===(Some(Unreachable))
    }

    "become reachable after calling reachable" in {
      val s = SBRFailureDetectorState.empty.unreachable(aa, bb).contention(aa, bb, cc, 1).contention(dd, bb, cc, 1)

      val (status, s1) = s.reachable(bb).updatedStatus(bb)
      status should ===(Some(Reachable))

      s1.reachable(cc).updatedStatus(cc)._1 should ===(Some(Reachable))
    }

    "update contentions when a node is removed" in {
      val s =
        SBRFailureDetectorState.empty.contention(aa, bb, cc, 1).contention(aa, dd, bb, 2).contention(dd, cc, aa, 1)

      val s1 = s.remove(aa)
      s1.updatedStatus(cc)._1 should ===(Some(Unreachable))
      s1.updatedStatus(bb)._1 should ===(Some(Unreachable))

      val s2 = s.remove(bb)
      s2.updatedStatus(cc)._1 should ===(Some(Reachable))
      s2.updatedStatus(aa)._1 should ===(Some(IndirectlyConnected))
    }
  }
}
